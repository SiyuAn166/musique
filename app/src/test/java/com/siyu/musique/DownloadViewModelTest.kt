package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.AudioFileDownloader
import com.siyu.musique.data.DownloadRecord
import com.siyu.musique.data.DownloadStore
import com.siyu.musique.data.InMemoryAppCache
import com.siyu.musique.data.InMemoryDownloadStore
import com.siyu.musique.data.InMemoryPlaybackModeStore
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.data.downloadFileName
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.player.PlaybackControlsRegistry
import com.siyu.musique.player.PlaybackError
import com.siyu.musique.ui.PlayerUiState
import com.siyu.musique.ui.SearchViewModel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Downloads end to end through the ViewModel: downloading
 * (completion, failure, retry, progress, cid-first resolve),
 * playback preferring local files with the quality fallback
 * chain, self-healing when a local file errors, deletion, and
 * orphan cleanup at startup. The downloader is a fake, the
 * repository counts its network calls, and the downloads dir
 * is a fresh temp dir per test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var downloadsDir: File

    private fun track(id: String, cid: String, title: String = "Song $id") = Track(
        id = id,
        cid = cid,
        title = title,
        artist = "Singer",
        cover = "https://cover.example/$id.jpg",
        duration = 200.seconds,
    )

    private fun record(track: Track, qualityId: Int, sizeBytes: Long = 5L) = DownloadRecord(
        track = track,
        qualityId = qualityId,
        fileName = downloadFileName(track, qualityId),
        sizeBytes = sizeBytes,
        downloadedAtMs = 1000L,
    )

    private fun seedFile(record: DownloadRecord, bytes: ByteArray = "audio".toByteArray()) {
        val file = File(downloadsDir, record.fileName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /** A repository that counts fetchDetail/fetchPlayUrl calls and returns one canned stream. */
    private class DownloadRepository(
        var playUrlQualityId: Int = 30280,
        var backupUrls: List<String> = emptyList(),
    ) : SearchRepository {
        var fetchDetailCalls = 0
        var fetchPlayUrlCalls = 0
        val fetchPlayUrlPreferences = mutableListOf<AudioQuality>()

        override suspend fun search(query: String) = emptyList<Track>()

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            fetchDetailCalls++
            // Fills an empty cid, like the real details call.
            val filled = if (track.cid.isEmpty()) track.copy(cid = "999") else track
            return DetailedTrack(filled, listOf(filled))
        }

        override suspend fun fetchPlayUrl(track: Track, quality: AudioQuality): AudioStream {
            fetchPlayUrlCalls++
            fetchPlayUrlPreferences += quality
            return AudioStream(
                qualityId = playUrlQualityId,
                baseUrl = "https://cdn.example/${track.id}_${track.cid}_$playUrlQualityId.m4s",
                bandwidth = 1L,
                backupUrls = backupUrls,
            )
        }

        override suspend fun fetchSuggestions(keyword: String): List<String> = emptyList()
    }

    /** A downloader that writes fixed bytes and reports one progress step. */
    private class WritingDownloader(
        private val bytes: ByteArray = "audio-bytes".toByteArray(),
    ) : AudioFileDownloader {
        var calls = 0
        var lastUrl: String? = null

        override suspend fun download(
            url: String,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            calls++
            lastUrl = url
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    /** A downloader that writes a partial file, then fails until [failWith] is cleared. */
    private class FailingDownloader(
        var failWith: Throwable? = IOException("connection reset"),
    ) : AudioFileDownloader {
        var calls = 0

        override suspend fun download(
            url: String,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            calls++
            target.parentFile?.mkdirs()
            target.writeBytes("partial".toByteArray())
            onProgress(7L, 100L)
            failWith?.let { throw it }
            target.writeBytes("audio".toByteArray())
            onProgress(100L, 100L)
        }
    }

    /** A downloader that fails for [failUrls] and writes fixed bytes for any other URL. */
    private class MirrorFailoverDownloader(
        private val failUrls: Set<String>,
    ) : AudioFileDownloader {
        val attemptedUrls = mutableListOf<String>()

        override suspend fun download(
            url: String,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            attemptedUrls += url
            target.parentFile?.mkdirs()
            if (url in failUrls) {
                target.writeBytes("partial".toByteArray())
                throw IOException("connection reset")
            }
            target.writeBytes("audio".toByteArray())
            onProgress(5L, 5L)
        }
    }

    /** A downloader that pauses mid-download at [gate], so progress state can be asserted. */
    private class GatedDownloader : AudioFileDownloader {
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun download(
            url: String,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            calls++
            onProgress(50L, 100L)
            gate.await()
            target.parentFile?.mkdirs()
            target.writeBytes("audio".toByteArray())
            onProgress(100L, 100L)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        downloadsDir = Files.createTempDirectory("musique-download-test").toFile()
    }

    @After
    fun tearDown() {
        PlaybackControlsRegistry.handler = null
        Dispatchers.resetMain()
        downloadsDir.deleteRecursively()
    }

    private fun viewModel(
        engine: FakePlayerEngine,
        repository: DownloadRepository,
        downloadStore: DownloadStore,
        downloader: AudioFileDownloader,
    ) = SearchViewModel(
        repository,
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        InMemoryPlaylistStore(),
        InMemoryPlaybackModeStore(),
        InMemoryAppCache(),
        downloadStore,
        downloader,
        downloadsDir,
    )

    @Test
    fun `download completion adds a record with the right file name quality and size`() =
        runTest(testDispatcher) {
            val repository = DownloadRepository()
            val store = InMemoryDownloadStore()
            val vm = viewModel(FakePlayerEngine(), repository, store, WritingDownloader())
            advanceUntilIdle()

            val track = track("BV1a", "111")
            vm.downloadTrack(track)
            advanceUntilIdle()

            val records = vm.downloadRecords.value
            assertEquals(1, records.size)
            assertEquals("BV1a_111_30280.m4a", records.single().fileName)
            assertEquals(30280, records.single().qualityId)
            assertEquals("audio-bytes".toByteArray().size.toLong(), records.single().sizeBytes)
            assertEquals("", records.single().track.playUrl)
            assertTrue(File(downloadsDir, "BV1a_111_30280.m4a").exists())
            assertTrue(vm.downloadProgress.value.isEmpty())
            assertEquals(1, store.load()?.size)
        }

    @Test
    fun `download resolves the cid first when the track has none`() = runTest(testDispatcher) {
        val repository = DownloadRepository()
        val vm = viewModel(
            FakePlayerEngine(),
            repository,
            InMemoryDownloadStore(),
            WritingDownloader(),
        )
        advanceUntilIdle()

        vm.downloadTrack(track("BV1x", ""))
        advanceUntilIdle()

        assertEquals(1, repository.fetchDetailCalls)
        assertEquals("BV1x_999_30280.m4a", vm.downloadRecords.value.single().fileName)
        assertEquals("999", vm.downloadRecords.value.single().track.cid)
    }

    @Test
    fun `download failure adds no record deletes the partial file and retry succeeds`() =
        runTest(testDispatcher) {
            val repository = DownloadRepository()
            val downloader = FailingDownloader()
            val vm = viewModel(
                FakePlayerEngine(),
                repository,
                InMemoryDownloadStore(),
                downloader,
            )
            advanceUntilIdle()

            val track = track("BV1a", "111")
            vm.downloadTrack(track)
            advanceUntilIdle()

            assertTrue(vm.downloadRecords.value.isEmpty())
            assertFalse(File(downloadsDir, "BV1a_111_30280.m4a").exists())
            val failed = vm.downloadProgress.value["BV1a:111:30280"]
            assertTrue(failed?.failed == true)

            downloader.failWith = null
            vm.downloadTrack(track)
            advanceUntilIdle()

            assertEquals(1, vm.downloadRecords.value.size)
            assertTrue(vm.downloadProgress.value.isEmpty())
            assertTrue(File(downloadsDir, "BV1a_111_30280.m4a").exists())
        }

    @Test
    fun `download falls over to the backup mirror when the base mirror fails`() =
        runTest(testDispatcher) {
            val repository = DownloadRepository()
            val backupUrl = "https://backup.example/BV1a_111_30280.m4s"
            repository.backupUrls = listOf(backupUrl)
            val baseUrl = "https://cdn.example/BV1a_111_30280.m4s"
            val downloader = MirrorFailoverDownloader(failUrls = setOf(baseUrl))
            val vm = viewModel(
                FakePlayerEngine(),
                repository,
                InMemoryDownloadStore(),
                downloader,
            )
            advanceUntilIdle()

            vm.downloadTrack(track("BV1a", "111"))
            advanceUntilIdle()

            assertEquals(listOf(baseUrl, backupUrl), downloader.attemptedUrls)
            assertEquals(1, vm.downloadRecords.value.size)
            // The failed attempt's partial bytes never mix into
            // the retry: the target is rewritten from the start.
            assertEquals("audio", File(downloadsDir, "BV1a_111_30280.m4a").readText())
            assertTrue(vm.downloadProgress.value.isEmpty())
        }

    @Test
    fun `download fails only after every mirror fails`() = runTest(testDispatcher) {
        val repository = DownloadRepository()
        val baseUrl = "https://cdn.example/BV1a_111_30280.m4s"
        val backupUrl = "https://backup.example/BV1a_111_30280.m4s"
        repository.backupUrls = listOf(backupUrl)
        val downloader = MirrorFailoverDownloader(failUrls = setOf(baseUrl, backupUrl))
        val vm = viewModel(
            FakePlayerEngine(),
            repository,
            InMemoryDownloadStore(),
            downloader,
        )
        advanceUntilIdle()

        vm.downloadTrack(track("BV1a", "111"))
        advanceUntilIdle()

        assertEquals(listOf(baseUrl, backupUrl), downloader.attemptedUrls)
        assertTrue(vm.downloadRecords.value.isEmpty())
        assertFalse(File(downloadsDir, "BV1a_111_30280.m4a").exists())
        assertTrue(vm.downloadProgress.value["BV1a:111:30280"]?.failed == true)
    }

    @Test
    fun `download progress reaches the exposed state while downloading`() =
        runTest(testDispatcher) {
            val downloader = GatedDownloader()
            val vm = viewModel(
                FakePlayerEngine(),
                DownloadRepository(),
                InMemoryDownloadStore(),
                downloader,
            )
            advanceUntilIdle()

            vm.downloadTrack(track("BV1a", "111"))
            advanceUntilIdle()

            val progress = vm.downloadProgress.value["BV1a:111:30280"]
            assertEquals(0.5f, progress?.fraction)
            assertFalse(progress?.failed == true)

            downloader.gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, vm.downloadRecords.value.size)
            assertTrue(vm.downloadProgress.value.isEmpty())
        }

    @Test
    fun `a second tap while downloading is ignored`() = runTest(testDispatcher) {
        val downloader = GatedDownloader()
        val vm = viewModel(
            FakePlayerEngine(),
            DownloadRepository(),
            InMemoryDownloadStore(),
            downloader,
        )
        advanceUntilIdle()

        val track = track("BV1a", "111")
        vm.downloadTrack(track)
        vm.downloadTrack(track)
        advanceUntilIdle()
        assertEquals(1, downloader.calls)

        downloader.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, vm.downloadRecords.value.size)
    }

    @Test
    fun `deleting while downloading cancels the job and clears the progress entry`() =
        runTest(testDispatcher) {
            val downloader = GatedDownloader()
            val vm = viewModel(
                FakePlayerEngine(),
                DownloadRepository(),
                InMemoryDownloadStore(),
                downloader,
            )
            advanceUntilIdle()

            val track = track("BV1a", "111")
            vm.downloadTrack(track)
            advanceUntilIdle()
            assertTrue(vm.downloadProgress.value.isNotEmpty())

            vm.deleteDownload(record(track, 30280))
            advanceUntilIdle()
            assertTrue(vm.downloadProgress.value.isEmpty())
            assertTrue(vm.downloadRecords.value.isEmpty())
        }

    @Test
    fun `downloading an already downloaded quality is a no-op`() = runTest(testDispatcher) {
        val track = track("BV1a", "111")
        val existing = record(track, 30280)
        seedFile(existing)
        val repository = DownloadRepository()
        val downloader = WritingDownloader()
        val vm = viewModel(
            FakePlayerEngine(),
            repository,
            InMemoryDownloadStore(listOf(existing)),
            downloader,
        )
        advanceUntilIdle()

        vm.downloadTrack(track)
        advanceUntilIdle()

        assertEquals(0, downloader.calls)
        assertEquals(1, vm.downloadRecords.value.size)
        assertTrue(vm.downloadProgress.value.isEmpty())
    }

    @Test
    fun `download uses the current quality preference`() = runTest(testDispatcher) {
        val repository = DownloadRepository(playUrlQualityId = 30216)
        val vm = viewModel(
            FakePlayerEngine(),
            repository,
            InMemoryDownloadStore(),
            WritingDownloader(),
        )
        advanceUntilIdle()
        vm.setQualityPreference(AudioQuality.Q64)
        advanceUntilIdle()

        vm.downloadTrack(track("BV1a", "111"))
        advanceUntilIdle()

        assertEquals(listOf(AudioQuality.Q64), repository.fetchPlayUrlPreferences)
        assertEquals("BV1a_111_30216.m4a", vm.downloadRecords.value.single().fileName)
    }

    @Test
    fun `play prefers the current quality file with no play URL call`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val local = record(track, 30280)
            seedFile(local)
            val repository = DownloadRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(engine, repository, InMemoryDownloadStore(listOf(local)), WritingDownloader())
            advanceUntilIdle()

            vm.play(track)
            advanceUntilIdle()

            assertEquals(1, engine.playCalls.size)
            val url = engine.playCalls.single().first
            assertTrue(url.startsWith("file:"))
            assertTrue(url.endsWith("BV1a_111_30280.m4a"))
            assertEquals(0, repository.fetchPlayUrlCalls)
            // The file plays without resolving a URL; exactly one
            // background detail call learns the video's parts so
            // part-aware navigation works for downloads too. It
            // never blocks playback and fails silently offline.
            assertEquals(1, repository.fetchDetailCalls)
            assertEquals("192K", vm.resolvedQualityLabel.value)
        }

    @Test
    fun `play falls back to another downloaded quality when the current one is absent`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val low = record(track, 30216)
            seedFile(low)
            val repository = DownloadRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(engine, repository, InMemoryDownloadStore(listOf(low)), WritingDownloader())
            advanceUntilIdle()
            vm.setQualityPreference(AudioQuality.Q192)
            advanceUntilIdle()

            vm.play(track)
            advanceUntilIdle()

            assertEquals(1, engine.playCalls.size)
            assertTrue(engine.playCalls.single().first.endsWith("BV1a_111_30216.m4a"))
            assertEquals(0, repository.fetchPlayUrlCalls)
            assertEquals("64K", vm.resolvedQualityLabel.value)
        }

    @Test
    fun `play prefers the current quality file over a lower downloaded one`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val low = record(track, 30216)
            val high = record(track, 30280)
            seedFile(low)
            seedFile(high)
            val engine = FakePlayerEngine()
            val vm = viewModel(
                engine,
                DownloadRepository(),
                InMemoryDownloadStore(listOf(low, high)),
                WritingDownloader(),
            )
            advanceUntilIdle()
            vm.setQualityPreference(AudioQuality.Q192)
            advanceUntilIdle()

            vm.play(track)
            advanceUntilIdle()

            assertTrue(engine.playCalls.single().first.endsWith("BV1a_111_30280.m4a"))
        }

    @Test
    fun `play picks the Hi-Res file over the 192K file when both are downloaded`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val hires = record(track, 30251)
            val high = record(track, 30280)
            seedFile(hires)
            seedFile(high)
            val engine = FakePlayerEngine()
            val vm = viewModel(
                engine,
                DownloadRepository(),
                InMemoryDownloadStore(listOf(high, hires)),
                WritingDownloader(),
            )
            advanceUntilIdle()

            vm.play(track)
            advanceUntilIdle()

            // The ids do not sort by quality: Hi-Res 30251 sits
            // below 192K 30280 numerically, so a raw max would
            // pick the wrong file.
            assertTrue(engine.playCalls.single().first.endsWith("BV1a_111_30251.m4a"))
            assertEquals("Hi-Res", vm.resolvedQualityLabel.value)
        }

    @Test
    fun `play falls back to the network when nothing is downloaded`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val repository = DownloadRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(
                engine,
                repository,
                InMemoryDownloadStore(),
                WritingDownloader(),
            )
            advanceUntilIdle()

            vm.play(track)
            advanceUntilIdle()

            assertEquals(1, engine.playCalls.size)
            assertTrue(engine.playCalls.single().first.startsWith("https://"))
            assertEquals(1, repository.fetchPlayUrlCalls)
        }

    @Test
    fun `a stale record whose file is gone is dropped and play goes to the network`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val store = InMemoryDownloadStore(listOf(record(track, 30280)))
            val repository = DownloadRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(engine, repository, store, WritingDownloader())
            advanceUntilIdle()

            vm.play(track)
            advanceUntilIdle()

            assertTrue(engine.playCalls.single().first.startsWith("https://"))
            assertTrue(vm.downloadRecords.value.isEmpty())
            assertTrue(store.load().isNullOrEmpty())
        }

    @Test
    fun `local file error deletes the file and record and falls back to the network once`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val local = record(track, 30280)
            seedFile(local)
            val repository = DownloadRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(
                engine,
                repository,
                InMemoryDownloadStore(listOf(local)),
                WritingDownloader(),
            )
            advanceUntilIdle()
            vm.play(track)
            advanceUntilIdle()
            assertTrue(engine.playCalls.single().first.startsWith("file:"))
            engine.emitPlaying()

            engine.emitError(PlaybackError("cannot decode", isHttpFailure = false))
            advanceUntilIdle()

            assertFalse(File(downloadsDir, local.fileName).exists())
            assertTrue(vm.downloadRecords.value.isEmpty())
            assertEquals(2, engine.playCalls.size)
            assertTrue(engine.playCalls[1].first.startsWith("https://"))
            assertEquals(1, repository.fetchPlayUrlCalls)

            // The one retry is spent: a second error stops in the error state.
            engine.emitError(PlaybackError("http gone", isHttpFailure = true))
            advanceUntilIdle()
            assertTrue(vm.playerState.value is PlayerUiState.Error)
            assertEquals(2, engine.playCalls.size)
        }

    @Test
    fun `delete removes the file and the record`() = runTest(testDispatcher) {
        val track = track("BV1a", "111")
        val local = record(track, 30280)
        seedFile(local)
        val store = InMemoryDownloadStore(listOf(local))
        val vm = viewModel(FakePlayerEngine(), DownloadRepository(), store, WritingDownloader())
        advanceUntilIdle()

        vm.deleteDownload(local)
        advanceUntilIdle()

        assertFalse(File(downloadsDir, local.fileName).exists())
        assertTrue(vm.downloadRecords.value.isEmpty())
        assertTrue(store.load().isNullOrEmpty())
    }

    @Test
    fun `startup cleanup removes orphan files and keeps recorded ones`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val local = record(track, 30280)
            seedFile(local)
            val orphan = File(downloadsDir, "BV1orphan_111_30280.m4a")
            orphan.writeBytes("partial".toByteArray())
            val subdir = File(downloadsDir, "nested")
            subdir.mkdirs()

            val vm = viewModel(
                FakePlayerEngine(),
                DownloadRepository(),
                InMemoryDownloadStore(listOf(local)),
                WritingDownloader(),
            )
            advanceUntilIdle()

            assertFalse(orphan.exists())
            assertTrue(File(downloadsDir, local.fileName).exists())
            assertTrue(subdir.exists())
            assertEquals(1, vm.downloadRecords.value.size)
        }

    @Test
    fun `playing from the downloads playlist adopts the downloads list as the queue and plays from file`() =
        runTest(testDispatcher) {
            val trackA = track("BV1a", "111")
            val trackB = track("BV1b", "222")
            val recordA = record(trackA, 30280)
            val recordB = record(trackB, 30280)
            seedFile(recordA)
            seedFile(recordB)
            val engine = FakePlayerEngine()
            val vm = viewModel(
                engine,
                DownloadRepository(),
                InMemoryDownloadStore(listOf(recordA, recordB)),
                WritingDownloader(),
            )
            advanceUntilIdle()

            val downloads = vm.playlists.value.first { it.id == Playlist.DOWNLOADS_ID }
            vm.playFromPlaylist(trackA, downloads)
            advanceUntilIdle()

            assertEquals(listOf(trackA, trackB), vm.queue.value)
            assertTrue(engine.playCalls.single().first.endsWith("BV1a_111_30280.m4a"))

            vm.playNext()
            advanceUntilIdle()
            assertEquals("BV1b", vm.currentTrack.value?.id)
            assertTrue(engine.playCalls[1].first.endsWith("BV1b_222_30280.m4a"))
        }

    @Test
    fun `playing from the downloads playlist on the loaded track is a no-op`() =
        runTest(testDispatcher) {
            val track = track("BV1a", "111")
            val local = record(track, 30280)
            seedFile(local)
            val engine = FakePlayerEngine()
            val vm = viewModel(
                engine,
                DownloadRepository(),
                InMemoryDownloadStore(listOf(local)),
                WritingDownloader(),
            )
            advanceUntilIdle()
            vm.play(track)
            advanceUntilIdle()
            assertEquals(1, engine.playCalls.size)

            val downloads = vm.playlists.value.first { it.id == Playlist.DOWNLOADS_ID }
            vm.playFromPlaylist(track, downloads)
            advanceUntilIdle()
            assertEquals(1, engine.playCalls.size)
        }
}
