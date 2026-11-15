package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.GitHubReleaseJson
import com.siyu.musique.data.GitHubUpdateChecker
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.UpdateCheckResult
import com.siyu.musique.data.UpdateChecker
import com.siyu.musique.data.UpdateFailureKind
import com.siyu.musique.data.UpdateGateway
import com.siyu.musique.data.UpdateHttpResponse
import com.siyu.musique.data.isUpdateAvailable
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.SearchViewModel
import com.siyu.musique.ui.UpdateUiState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The in-app update check: release and version payload parsing,
 * the newer-version comparison, the checker against a fake
 * gateway (every failure branch), and the ViewModel's update
 * state transitions against a fake checker. The gateway fake
 * also proves the check sends no Authorization header anywhere:
 * the repo is public, so the anonymous API is all it needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var updatesDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        updatesDir = Files.createTempDirectory("musique-update-test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        updatesDir.deleteRecursively()
    }

    private class FakeUpdateGateway : UpdateGateway {
        var releaseBody: String = RELEASE_JSON
        var releaseStatus: Int = 200
        var versionBody: String = VERSION_JSON
        var versionStatus: Int = 200
        var getError: IOException? = null
        var versionGetError: IOException? = null
        var downloadedBytes: ByteArray = ByteArray(10) { it.toByte() }
        var downloadError: IOException? = null
        val getUrls = mutableListOf<String>()
        val getHeaders = mutableListOf<Map<String, String>>()

        override suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
            getUrls.add(url)
            getHeaders.add(headers)
            return if (url == GitHubUpdateChecker.LATEST_RELEASE_URL) {
                getError?.let { throw it }
                UpdateHttpResponse(releaseStatus, releaseBody)
            } else {
                versionGetError?.let { throw it }
                UpdateHttpResponse(versionStatus, versionBody)
            }
        }

        override suspend fun download(
            url: String,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            downloadError?.let { throw it }
            target.parentFile?.mkdirs()
            target.writeBytes(downloadedBytes)
            onProgress(downloadedBytes.size.toLong(), downloadedBytes.size.toLong())
        }
    }

    private class FakeUpdateChecker(
        var result: UpdateCheckResult = UpdateCheckResult.UpToDate,
    ) : UpdateChecker {
        var checkCalls = 0
        var downloadCalls = 0
        var checkGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var downloadError: IOException? = null
        var downloadBytes = ByteArray(10) { it.toByte() }

        override suspend fun checkForUpdate(): UpdateCheckResult {
            checkCalls++
            checkGate?.await()
            return result
        }

        override suspend fun downloadUpdate(
            update: UpdateCheckResult.Available,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ): File {
            downloadCalls++
            downloadError?.let { throw it }
            onProgress(5, 10)
            downloadGate?.await()
            target.parentFile?.mkdirs()
            target.writeBytes(downloadBytes)
            onProgress(10, 10)
            return target
        }
    }

    private fun viewModel(checker: UpdateChecker) = SearchViewModel(
        FakeSearchRepository(),
        FakePlayerEngine(),
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        updateChecker = checker,
        installedVersionName = "0.1.0",
        updatesDir = updatesDir,
    )

    private fun available(
        tag: String = "v0.2.0",
        versionCode: Int = 60,
        apkSize: Long = 10L,
    ) = UpdateCheckResult.Available(
        tag = tag,
        versionCode = versionCode,
        apkSize = apkSize,
        apkUrl = "https://example.com/musique.apk",
    )

    // Parsing.

    @Test
    fun `release JSON parses the tag and the assets by name`() {
        val release = GitHubReleaseJson.parseLatestRelease(RELEASE_JSON)
        assertEquals("v0.1.0", release.tagName)
        assertEquals(3, release.assets.size)
        val apk = release.assetNamed("musique.apk")
        assertEquals(17_170_432L, apk?.size)
        assertEquals(
            "https://github.com/SiyuAn166/musique/releases/download/v0.1.0/musique.apk",
            apk?.downloadUrl,
        )
        assertEquals(38L, release.assetNamed("version.json")?.size)
        assertNull(release.assetNamed("nope.apk"))
    }

    @Test
    fun `release JSON without assets parses to an empty list`() {
        val release = GitHubReleaseJson.parseLatestRelease("""{"tag_name":"v0.1.0"}""")
        assertEquals("v0.1.0", release.tagName)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `malformed asset entries are skipped, good ones survive`() {
        val raw = """{"tag_name":"v0.1.0","assets":[
            {"name":"musique.apk","size":10,"browser_download_url":"https://example.com/a.apk"},
            {"name":"broken.apk"},
            "not-an-object",
            {"name":"version.json","size":38,"browser_download_url":"https://example.com/v.json"}
        ]}"""
        val release = GitHubReleaseJson.parseLatestRelease(raw)
        assertEquals(2, release.assets.size)
        assertEquals("musique.apk", release.assets[0].name)
        assertEquals("version.json", release.assets[1].name)
    }

    @Test
    fun `release JSON without a tag throws`() {
        try {
            GitHubReleaseJson.parseLatestRelease("""{"assets":[]}""")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `malformed release JSON throws`() {
        try {
            GitHubReleaseJson.parseLatestRelease("not json")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `version JSON parses the code and the tag`() {
        val info = GitHubReleaseJson.parseVersionInfo(VERSION_JSON)
        assertEquals(57, info.versionCode)
        assertEquals("v0.1.0", info.tag)
    }

    @Test
    fun `version JSON without a versionCode throws`() {
        try {
            GitHubReleaseJson.parseVersionInfo("""{"tag":"v0.1.0"}""")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `version JSON without a tag throws`() {
        try {
            GitHubReleaseJson.parseVersionInfo("""{"versionCode":57}""")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `version JSON with a non numeric versionCode throws`() {
        try {
            GitHubReleaseJson.parseVersionInfo("""{"versionCode":"abc","tag":"v0.1.0"}""")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `the comparison is strictly newer only`() {
        assertTrue(isUpdateAvailable(57, 56))
        assertFalse(isUpdateAvailable(56, 56))
        assertFalse(isUpdateAvailable(55, 56))
    }

    // The checker against a fake gateway.

    @Test
    fun `a newer release comes back available with the asset details`() = runTest(testDispatcher) {
        val checker = GitHubUpdateChecker(FakeUpdateGateway(), installedVersionCode = 56)
        val result = checker.checkForUpdate()
        assertTrue(result is UpdateCheckResult.Available)
        result as UpdateCheckResult.Available
        assertEquals("v0.1.0", result.tag)
        assertEquals(57, result.versionCode)
        assertEquals(17_170_432L, result.apkSize)
        assertEquals(
            "https://github.com/SiyuAn166/musique/releases/download/v0.1.0/musique.apk",
            result.apkUrl,
        )
    }

    @Test
    fun `an equal or older release is up to date`() = runTest(testDispatcher) {
        assertEquals(
            UpdateCheckResult.UpToDate,
            GitHubUpdateChecker(FakeUpdateGateway(), installedVersionCode = 57).checkForUpdate(),
        )
        assertEquals(
            UpdateCheckResult.UpToDate,
            GitHubUpdateChecker(FakeUpdateGateway(), installedVersionCode = 99).checkForUpdate(),
        )
    }

    @Test
    fun `the check fetches the release then the version asset, with no auth header`() =
        runTest(testDispatcher) {
            val gateway = FakeUpdateGateway()
            GitHubUpdateChecker(gateway, installedVersionCode = 56).checkForUpdate()
            assertEquals(
                listOf(
                    GitHubUpdateChecker.LATEST_RELEASE_URL,
                    "https://github.com/SiyuAn166/musique/releases/download/v0.1.0/version.json",
                ),
                gateway.getUrls,
            )
            assertEquals(
                "application/vnd.github+json",
                gateway.getHeaders[0]["Accept"],
            )
            assertTrue(gateway.getHeaders.none { it.containsKey("Authorization") })
        }

    @Test
    fun `a transport failure is a network failure`() = runTest(testDispatcher) {
        val gateway = FakeUpdateGateway().apply { getError = IOException("down") }
        val result = GitHubUpdateChecker(gateway, installedVersionCode = 56).checkForUpdate()
        assertEquals(
            UpdateCheckResult.Failed(UpdateFailureKind.NETWORK),
            result,
        )
    }

    @Test
    fun `a 403 or 429 is a rate limit, other statuses are network failures`() =
        runTest(testDispatcher) {
            for (status in listOf(403, 429)) {
                val gateway = FakeUpdateGateway().apply { releaseStatus = status }
                assertEquals(
                    UpdateCheckResult.Failed(UpdateFailureKind.RATE_LIMITED),
                    GitHubUpdateChecker(gateway, installedVersionCode = 56).checkForUpdate(),
                )
            }
            for (status in listOf(404, 500)) {
                val gateway = FakeUpdateGateway().apply { releaseStatus = status }
                assertEquals(
                    UpdateCheckResult.Failed(UpdateFailureKind.NETWORK),
                    GitHubUpdateChecker(gateway, installedVersionCode = 56).checkForUpdate(),
                )
            }
        }

    @Test
    fun `a malformed release body is a parse failure`() = runTest(testDispatcher) {
        val gateway = FakeUpdateGateway().apply { releaseBody = "not json" }
        assertEquals(
            UpdateCheckResult.Failed(UpdateFailureKind.PARSE),
            GitHubUpdateChecker(gateway, installedVersionCode = 56).checkForUpdate(),
        )
    }

    @Test
    fun `a release without version json or without the apk is a missing asset failure`() =
        runTest(testDispatcher) {
            val noVersion = FakeUpdateGateway().apply {
                releaseBody = """{"tag_name":"v0.1.0","assets":[
                    {"name":"musique.apk","size":10,"browser_download_url":"https://example.com/a.apk"}
                ]}"""
            }
            assertEquals(
                UpdateCheckResult.Failed(UpdateFailureKind.MISSING_ASSET),
                GitHubUpdateChecker(noVersion, installedVersionCode = 56).checkForUpdate(),
            )
            val noApk = FakeUpdateGateway().apply {
                releaseBody = """{"tag_name":"v0.1.0","assets":[
                    {"name":"version.json","size":38,"browser_download_url":"https://example.com/v.json"}
                ]}"""
            }
            assertEquals(
                UpdateCheckResult.Failed(UpdateFailureKind.MISSING_ASSET),
                GitHubUpdateChecker(noApk, installedVersionCode = 56).checkForUpdate(),
            )
        }

    @Test
    fun `a failed version fetch is a network failure`() = runTest(testDispatcher) {
        val throwing = FakeUpdateGateway().apply { versionGetError = IOException("down") }
        assertEquals(
            UpdateCheckResult.Failed(UpdateFailureKind.NETWORK),
            GitHubUpdateChecker(throwing, installedVersionCode = 56).checkForUpdate(),
        )
        val missing = FakeUpdateGateway().apply { versionStatus = 404 }
        assertEquals(
            UpdateCheckResult.Failed(UpdateFailureKind.NETWORK),
            GitHubUpdateChecker(missing, installedVersionCode = 56).checkForUpdate(),
        )
    }

    @Test
    fun `a malformed version body is a parse failure`() = runTest(testDispatcher) {
        val gateway = FakeUpdateGateway().apply { versionBody = "not json" }
        assertEquals(
            UpdateCheckResult.Failed(UpdateFailureKind.PARSE),
            GitHubUpdateChecker(gateway, installedVersionCode = 56).checkForUpdate(),
        )
    }

    @Test
    fun `a download whose size matches returns the file`() = runTest(testDispatcher) {
        val gateway = FakeUpdateGateway()
        val checker = GitHubUpdateChecker(gateway, installedVersionCode = 56)
        val target = File(updatesDir, "musique.apk")
        val downloaded = checker.downloadUpdate(available(apkSize = 10L), target) { _, _ -> }
        assertEquals(target, downloaded)
        assertTrue(target.exists())
        assertEquals(10L, target.length())
    }

    @Test
    fun `a download whose size mismatches throws and deletes the file`() = runTest(testDispatcher) {
        val gateway = FakeUpdateGateway()
        val checker = GitHubUpdateChecker(gateway, installedVersionCode = 56)
        val target = File(updatesDir, "musique.apk")
        try {
            checker.downloadUpdate(available(apkSize = 999L), target) { _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            // Expected.
        }
        assertFalse(target.exists())
    }

    @Test
    fun `a download with an unknown release size skips verification`() = runTest(testDispatcher) {
        val gateway = FakeUpdateGateway()
        val checker = GitHubUpdateChecker(gateway, installedVersionCode = 56)
        val target = File(updatesDir, "musique.apk")
        checker.downloadUpdate(available(apkSize = 0L), target) { _, _ -> }
        assertTrue(target.exists())
    }

    @Test
    fun `a failed download deletes the partial file`() = runTest(testDispatcher) {
        val gateway = FakeUpdateGateway().apply { downloadError = IOException("down") }
        val checker = GitHubUpdateChecker(gateway, installedVersionCode = 56)
        val target = File(updatesDir, "musique.apk")
        target.writeBytes(ByteArray(5))
        try {
            checker.downloadUpdate(available(), target) { _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            // Expected.
        }
        assertFalse(target.exists())
    }

    // The ViewModel's update states against a fake checker.

    @Test
    fun `check finds an update, download reports progress, then offers the install`() =
        runTest(testDispatcher) {
            val checker = FakeUpdateChecker(available())
            val gate = CompletableDeferred<Unit>()
            checker.downloadGate = gate
            val vm = viewModel(checker)
            vm.checkForUpdate()
            advanceUntilIdle()
            assertEquals(
                UpdateUiState.Available(tag = "v0.2.0", apkSize = 10L),
                vm.updateState.value,
            )
            vm.downloadUpdate()
            runCurrent()
            assertEquals(UpdateUiState.Downloading(fraction = 0.5f), vm.updateState.value)
            gate.complete(Unit)
            advanceUntilIdle()
            val ready = vm.updateState.value
            assertTrue(ready is UpdateUiState.ReadyToInstall)
            ready as UpdateUiState.ReadyToInstall
            assertTrue(ready.apkFile.exists())
            assertEquals("musique.apk", ready.apkFile.name)
            assertEquals(1, checker.checkCalls)
            assertEquals(1, checker.downloadCalls)
        }

    @Test
    fun `check reports up to date`() = runTest(testDispatcher) {
        val vm = viewModel(FakeUpdateChecker(UpdateCheckResult.UpToDate))
        vm.checkForUpdate()
        advanceUntilIdle()
        assertEquals(UpdateUiState.UpToDate, vm.updateState.value)
    }

    @Test
    fun `check reports the failure kind and retry re-checks`() = runTest(testDispatcher) {
        val checker = FakeUpdateChecker(UpdateCheckResult.Failed(UpdateFailureKind.RATE_LIMITED))
        val vm = viewModel(checker)
        vm.checkForUpdate()
        advanceUntilIdle()
        assertEquals(
            UpdateUiState.Failed(UpdateFailureKind.RATE_LIMITED),
            vm.updateState.value,
        )
        checker.result = UpdateCheckResult.UpToDate
        vm.retryUpdate()
        advanceUntilIdle()
        assertEquals(UpdateUiState.UpToDate, vm.updateState.value)
        assertEquals(2, checker.checkCalls)
    }

    @Test
    fun `a failed download retries the download without a fresh check`() = runTest(testDispatcher) {
        val checker = FakeUpdateChecker(available())
        checker.downloadError = IOException("down")
        val vm = viewModel(checker)
        vm.checkForUpdate()
        advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()
        assertEquals(
            UpdateUiState.Failed(UpdateFailureKind.DOWNLOAD_FAILED),
            vm.updateState.value,
        )
        checker.downloadError = null
        vm.retryUpdate()
        advanceUntilIdle()
        assertTrue(vm.updateState.value is UpdateUiState.ReadyToInstall)
        assertEquals(1, checker.checkCalls)
        assertEquals(2, checker.downloadCalls)
    }

    @Test
    fun `a second check while checking is ignored, and dismiss waits for the check`() =
        runTest(testDispatcher) {
            val checker = FakeUpdateChecker(available())
            val gate = CompletableDeferred<Unit>()
            checker.checkGate = gate
            val vm = viewModel(checker)
            vm.checkForUpdate()
            runCurrent()
            assertEquals(UpdateUiState.Checking, vm.updateState.value)
            vm.checkForUpdate()
            vm.dismissUpdateState()
            assertEquals(UpdateUiState.Checking, vm.updateState.value)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, checker.checkCalls)
            assertTrue(vm.updateState.value is UpdateUiState.Available)
        }

    @Test
    fun `a check while downloading is ignored`() = runTest(testDispatcher) {
        val checker = FakeUpdateChecker(available())
        val gate = CompletableDeferred<Unit>()
        checker.downloadGate = gate
        val vm = viewModel(checker)
        vm.checkForUpdate()
        advanceUntilIdle()
        vm.downloadUpdate()
        runCurrent()
        assertTrue(vm.updateState.value is UpdateUiState.Downloading)
        vm.checkForUpdate()
        vm.dismissUpdateState()
        assertTrue(vm.updateState.value is UpdateUiState.Downloading)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, checker.checkCalls)
        assertTrue(vm.updateState.value is UpdateUiState.ReadyToInstall)
    }

    @Test
    fun `dismissing an available update clears the pending update`() = runTest(testDispatcher) {
        val checker = FakeUpdateChecker(available())
        val vm = viewModel(checker)
        vm.checkForUpdate()
        advanceUntilIdle()
        vm.dismissUpdateState()
        assertEquals(UpdateUiState.Idle, vm.updateState.value)
        vm.downloadUpdate()
        advanceUntilIdle()
        assertEquals(0, checker.downloadCalls)
        assertEquals(UpdateUiState.Idle, vm.updateState.value)
    }

    @Test
    fun `download without a pending update does nothing`() = runTest(testDispatcher) {
        val checker = FakeUpdateChecker(available())
        val vm = viewModel(checker)
        vm.downloadUpdate()
        advanceUntilIdle()
        assertEquals(0, checker.downloadCalls)
        assertEquals(UpdateUiState.Idle, vm.updateState.value)
    }

    @Test
    fun `retry without a failure does nothing`() = runTest(testDispatcher) {
        val checker = FakeUpdateChecker(available())
        val vm = viewModel(checker)
        vm.retryUpdate()
        advanceUntilIdle()
        assertEquals(0, checker.checkCalls)
        assertEquals(0, checker.downloadCalls)
    }

    @Test
    fun `a throwing checker lands in the failed state instead of crashing`() =
        runTest(testDispatcher) {
            val checker = object : UpdateChecker {
                override suspend fun checkForUpdate(): UpdateCheckResult =
                    throw RuntimeException("boom")

                override suspend fun downloadUpdate(
                    update: UpdateCheckResult.Available,
                    target: File,
                    onProgress: (Long, Long?) -> Unit,
                ): File = throw RuntimeException("boom")
            }
            val vm = viewModel(checker)
            vm.checkForUpdate()
            advanceUntilIdle()
            assertEquals(
                UpdateUiState.Failed(UpdateFailureKind.NETWORK),
                vm.updateState.value,
            )
        }

    companion object {
        private const val RELEASE_JSON = """{
            "tag_name": "v0.1.0",
            "name": "Musique v0.1.0",
            "assets": [
                {
                    "name": "musique.apk",
                    "size": 17170432,
                    "browser_download_url": "https://github.com/SiyuAn166/musique/releases/download/v0.1.0/musique.apk"
                },
                {
                    "name": "version.json",
                    "size": 38,
                    "browser_download_url": "https://github.com/SiyuAn166/musique/releases/download/v0.1.0/version.json"
                },
                {
                    "name": "Source code (zip)",
                    "size": 12345,
                    "browser_download_url": "https://example.com/source.zip"
                }
            ]
        }"""
        private const val VERSION_JSON = """{"versionCode": 57, "tag": "v0.1.0"}"""
    }
}
