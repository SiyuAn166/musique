package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.NoOpUpdateApkInstaller
import com.siyu.musique.data.SessionApkInstaller
import com.siyu.musique.data.UpdateApkInstaller
import com.siyu.musique.data.UpdateCheckResult
import com.siyu.musique.data.UpdateChecker
import com.siyu.musique.data.UpdateFailureKind
import com.siyu.musique.data.UpdateInstallSession
import com.siyu.musique.data.UpdateInstallSessionFactory
import com.siyu.musique.data.UpdateInstallStatusRegistry
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session install path: the session ordering behind a fake
 * (write, commit, delete; abandon and keep the file on any
 * failure), and the ViewModel's install states against a fake
 * installer, including retry reusing the staged file and the
 * status receiver's failure report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionUpdateInstallTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var updatesDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        updatesDir = Files.createTempDirectory("musique-session-install-test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        updatesDir.deleteRecursively()
        UpdateInstallStatusRegistry.handler = null
    }

    private class FakeSession(
        private val events: MutableList<String>,
    ) : UpdateInstallSession {
        var writeError: IOException? = null
        var commitError: IOException? = null
        var abandonError: RuntimeException? = null
        var writtenFile: File? = null

        override fun writeApk(apkFile: File) {
            events.add("write")
            writtenFile = apkFile
            writeError?.let { throw it }
        }

        override fun commit() {
            events.add("commit")
            commitError?.let { throw it }
        }

        override fun abandon() {
            events.add("abandon")
            abandonError?.let { throw it }
        }
    }

    private class FakeSessionFactory(
        private val events: MutableList<String>,
        val session: FakeSession,
    ) : UpdateInstallSessionFactory {
        var openError: IOException? = null
        var openCalls = 0
        var openedFile: File? = null

        override fun openSession(apkFile: File): UpdateInstallSession {
            events.add("open")
            openCalls++
            openedFile = apkFile
            openError?.let { throw it }
            return session
        }
    }

    private class FakeApkInstaller(
        var result: Boolean = true,
    ) : UpdateApkInstaller {
        var installCalls = 0
        var installedFile: File? = null
        var installGate: CompletableDeferred<Unit>? = null

        override suspend fun install(apkFile: File): Boolean {
            installCalls++
            installedFile = apkFile
            installGate?.await()
            return result
        }
    }

    private class FakeUpdateChecker(
        var result: UpdateCheckResult = UpdateCheckResult.UpToDate,
    ) : UpdateChecker {
        var checkCalls = 0
        var downloadCalls = 0
        var downloadBytes = ByteArray(10) { it.toByte() }

        override suspend fun checkForUpdate(): UpdateCheckResult {
            checkCalls++
            return result
        }

        override suspend fun downloadUpdate(
            update: UpdateCheckResult.Available,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ): File {
            downloadCalls++
            target.parentFile?.mkdirs()
            target.writeBytes(downloadBytes)
            onProgress(10, 10)
            return target
        }
    }

    private fun viewModel(
        checker: UpdateChecker,
        installer: UpdateApkInstaller = NoOpUpdateApkInstaller,
    ) = SearchViewModel(
        FakeSearchRepository(),
        FakePlayerEngine(),
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        updateChecker = checker,
        updateApkInstaller = installer,
        installedVersionName = "0.1.0",
        updatesDir = updatesDir,
    )

    private fun available() = UpdateCheckResult.Available(
        tag = "v0.2.0",
        versionCode = 60,
        apkSize = 10L,
        apkUrl = "https://example.com/musique.apk",
    )

    private fun stagedApk(): File = File(updatesDir, "musique.apk").apply {
        writeBytes(ByteArray(10) { it.toByte() })
    }

    // The session ordering behind a fake.

    @Test
    fun `install writes, commits, then deletes the staged file`() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val session = FakeSession(events)
        val installer = SessionApkInstaller(FakeSessionFactory(events, session)) { file ->
            events.add("delete")
            file.delete()
        }
        val apk = stagedApk()
        assertTrue(installer.install(apk))
        assertEquals(listOf("open", "write", "commit", "delete"), events)
        assertEquals(apk, session.writtenFile)
        assertFalse(apk.exists())
    }

    @Test
    fun `a write failure abandons the session and keeps the file`() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val session = FakeSession(events).apply { writeError = IOException("no space") }
        val installer = SessionApkInstaller(FakeSessionFactory(events, session))
        val apk = stagedApk()
        assertFalse(installer.install(apk))
        assertEquals(listOf("open", "write", "abandon"), events)
        assertTrue(apk.exists())
    }

    @Test
    fun `a commit failure abandons the session and keeps the file`() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val session = FakeSession(events).apply { commitError = IOException("rejected") }
        val installer = SessionApkInstaller(FakeSessionFactory(events, session))
        val apk = stagedApk()
        assertFalse(installer.install(apk))
        assertEquals(listOf("open", "write", "commit", "abandon"), events)
        assertTrue(apk.exists())
    }

    @Test
    fun `an open failure keeps the file and abandons nothing`() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val factory = FakeSessionFactory(events, FakeSession(events)).apply {
            openError = IOException("no session")
        }
        val installer = SessionApkInstaller(factory)
        val apk = stagedApk()
        assertFalse(installer.install(apk))
        assertEquals(listOf("open"), events)
        assertTrue(apk.exists())
    }

    @Test
    fun `an abandon that throws does not escape install`() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val session = FakeSession(events).apply {
            writeError = IOException("no space")
            abandonError = RuntimeException("abandon boom")
        }
        val installer = SessionApkInstaller(FakeSessionFactory(events, session))
        assertFalse(installer.install(stagedApk()))
        assertEquals(listOf("open", "write", "abandon"), events)
    }

    @Test
    fun `a delete that reports failure still counts the install as committed`() =
        runTest(testDispatcher) {
            val events = mutableListOf<String>()
            val installer = SessionApkInstaller(FakeSessionFactory(events, FakeSession(events))) {
                events.add("delete")
                false
            }
            val apk = stagedApk()
            assertTrue(installer.install(apk))
            assertEquals(listOf("open", "write", "commit", "delete"), events)
            assertTrue(apk.exists())
        }

    // The ViewModel's install states against a fake installer.

    private suspend fun TestScope.readyToInstall(
        checker: FakeUpdateChecker,
        installer: UpdateApkInstaller,
    ): SearchViewModel {
        val vm = viewModel(checker, installer)
        vm.checkForUpdate()
        advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()
        assertTrue(vm.updateState.value is UpdateUiState.ReadyToInstall)
        return vm
    }

    @Test
    fun `install commits and leaves the dialog offering the install`() = runTest(testDispatcher) {
        val installer = FakeApkInstaller(result = true)
        val vm = readyToInstall(FakeUpdateChecker(available()), installer)
        val ready = vm.updateState.value as UpdateUiState.ReadyToInstall
        vm.installUpdate(ready.apkFile)
        advanceUntilIdle()
        assertEquals(UpdateUiState.ReadyToInstall(ready.apkFile), vm.updateState.value)
        assertEquals(1, installer.installCalls)
        assertEquals(ready.apkFile, installer.installedFile)
    }

    @Test
    fun `a failed install shows the failure line and keeps the staged file`() =
        runTest(testDispatcher) {
            val installer = FakeApkInstaller(result = false)
            val vm = readyToInstall(FakeUpdateChecker(available()), installer)
            val ready = vm.updateState.value as UpdateUiState.ReadyToInstall
            vm.installUpdate(ready.apkFile)
            advanceUntilIdle()
            assertEquals(
                UpdateUiState.Failed(UpdateFailureKind.INSTALL_FAILED),
                vm.updateState.value,
            )
            assertTrue(ready.apkFile.exists())
        }

    @Test
    fun `retry after a failed install reuses the staged file without downloading again`() =
        runTest(testDispatcher) {
            val checker = FakeUpdateChecker(available())
            val installer = FakeApkInstaller(result = false)
            val vm = readyToInstall(checker, installer)
            val ready = vm.updateState.value as UpdateUiState.ReadyToInstall
            vm.installUpdate(ready.apkFile)
            advanceUntilIdle()
            installer.result = true
            vm.retryUpdate()
            advanceUntilIdle()
            assertEquals(2, installer.installCalls)
            assertEquals(1, checker.downloadCalls)
            assertEquals(UpdateUiState.ReadyToInstall(ready.apkFile), vm.updateState.value)
        }

    @Test
    fun `retry after a reported install failure downloads again when the file is gone`() =
        runTest(testDispatcher) {
            val checker = FakeUpdateChecker(available())
            val installer = FakeApkInstaller(result = true)
            val vm = readyToInstall(checker, installer)
            val ready = vm.updateState.value as UpdateUiState.ReadyToInstall
            vm.installUpdate(ready.apkFile)
            advanceUntilIdle()
            // The commit deleted the staged file; the system then
            // reports the install failed.
            ready.apkFile.delete()
            UpdateInstallStatusRegistry.handler?.onInstallFailed()
            assertEquals(
                UpdateUiState.Failed(UpdateFailureKind.INSTALL_FAILED),
                vm.updateState.value,
            )
            vm.retryUpdate()
            advanceUntilIdle()
            assertEquals(1, installer.installCalls)
            assertEquals(2, checker.downloadCalls)
            assertTrue(vm.updateState.value is UpdateUiState.ReadyToInstall)
        }

    @Test
    fun `the status handler reports failure only while the install is offered`() =
        runTest(testDispatcher) {
            val vm = readyToInstall(FakeUpdateChecker(available()), FakeApkInstaller())
            UpdateInstallStatusRegistry.handler?.onInstallFailed()
            assertEquals(
                UpdateUiState.Failed(UpdateFailureKind.INSTALL_FAILED),
                vm.updateState.value,
            )
            val dismissed = readyToInstall(FakeUpdateChecker(available()), FakeApkInstaller())
            dismissed.dismissUpdateState()
            assertEquals(UpdateUiState.Idle, dismissed.updateState.value)
            UpdateInstallStatusRegistry.handler?.onInstallFailed()
            assertEquals(UpdateUiState.Idle, dismissed.updateState.value)
        }

    @Test
    fun `install is ignored in the wrong states and while one runs`() = runTest(testDispatcher) {
        val installer = FakeApkInstaller(result = true)
        val vm = viewModel(FakeUpdateChecker(available()), installer)
        vm.installUpdate(File(updatesDir, "musique.apk"))
        advanceUntilIdle()
        assertEquals(0, installer.installCalls)

        val gate = CompletableDeferred<Unit>()
        installer.installGate = gate
        val ready = readyToInstall(FakeUpdateChecker(available()), installer)
        val readyState = ready.updateState.value as UpdateUiState.ReadyToInstall
        ready.installUpdate(readyState.apkFile)
        runCurrent()
        ready.installUpdate(readyState.apkFile)
        assertEquals(1, installer.installCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, installer.installCalls)
    }

    @Test
    fun `a second install tap after a commit is ignored`() = runTest(testDispatcher) {
        val installer = FakeApkInstaller(result = true)
        val vm = readyToInstall(FakeUpdateChecker(available()), installer)
        val ready = vm.updateState.value as UpdateUiState.ReadyToInstall
        vm.installUpdate(ready.apkFile)
        advanceUntilIdle()
        vm.installUpdate(ready.apkFile)
        advanceUntilIdle()
        assertEquals(1, installer.installCalls)
    }
}
