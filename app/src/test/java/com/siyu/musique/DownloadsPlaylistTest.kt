package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.AudioFileDownloader
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.DownloadRecord
import com.siyu.musique.data.DownloadStore
import com.siyu.musique.data.InMemoryAppCache
import com.siyu.musique.data.InMemoryDownloadStore
import com.siyu.musique.data.InMemoryPlaybackModeStore
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.data.downloadFileName
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.player.PlaybackControlsRegistry
import com.siyu.musique.ui.SearchViewModel
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
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
 * The synthetic Downloads playlist: its position in the exposed
 * playlists flow, how it follows the download records, and the
 * guards that keep it out of manual playlist edits and out of
 * the playlist store. The detail screen's delete path (one
 * [SearchViewModel.deleteDownload] call per record) is covered
 * through the record and file removal it performs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsPlaylistTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var downloadsDir: File

    private fun track(id: String, cid: String, title: String = "Song $id") = Track(
        id = id,
        cid = cid,
        title = title,
        artist = "Singer",
        cover = "",
        duration = 200.seconds,
    )

    private fun record(track: Track, qualityId: Int) = DownloadRecord(
        track = track,
        qualityId = qualityId,
        fileName = downloadFileName(track, qualityId),
        sizeBytes = 5L,
        downloadedAtMs = 1000L,
    )

    private fun seedFile(record: DownloadRecord) {
        val file = File(downloadsDir, record.fileName)
        file.parentFile?.mkdirs()
        file.writeBytes("audio".toByteArray())
    }

    /** A repository returning one canned stream, for downloadTrack. */
    private class DownloadRepository : SearchRepository {
        override suspend fun search(query: String) = emptyList<Track>()

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            val filled = if (track.cid.isEmpty()) track.copy(cid = "999") else track
            return DetailedTrack(filled, listOf(filled))
        }

        override suspend fun fetchPlayUrl(track: Track, quality: AudioQuality): AudioStream =
            AudioStream(
                qualityId = 30280,
                baseUrl = "https://cdn.example/${track.id}_${track.cid}_30280.m4s",
                bandwidth = 1L,
            )

        override suspend fun fetchSuggestions(keyword: String): List<String> = emptyList()
    }

    /** A downloader that writes fixed bytes. */
    private class WritingDownloader : AudioFileDownloader {
        override suspend fun download(
            url: String,
            target: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            target.parentFile?.mkdirs()
            target.writeBytes("audio-bytes".toByteArray())
            onProgress(11L, 11L)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        downloadsDir = Files.createTempDirectory("musique-downloads-playlist-test").toFile()
    }

    @After
    fun tearDown() {
        PlaybackControlsRegistry.handler = null
        Dispatchers.resetMain()
        downloadsDir.deleteRecursively()
    }

    private fun viewModel(
        downloadStore: DownloadStore = InMemoryDownloadStore(),
        playlistStore: InMemoryPlaylistStore = InMemoryPlaylistStore(),
    ) = SearchViewModel(
        DownloadRepository(),
        FakePlayerEngine(),
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        playlistStore,
        InMemoryPlaybackModeStore(),
        InMemoryAppCache(),
        downloadStore,
        WritingDownloader(),
        downloadsDir,
    )

    /** The Downloads playlist as the detail screen looks it up, by id. */
    private fun SearchViewModel.downloadsPlaylist(): Playlist =
        playlists.value.first { it.id == Playlist.DOWNLOADS_ID }

    @Test
    fun `downloads playlist appears after the default and before user playlists, empty at first`() =
        runTest(testDispatcher) {
            val custom = Playlist("u1", "Mix", emptyList())
            val store = InMemoryPlaylistStore(listOf(Playlist.default(), custom))
            val vm = viewModel(playlistStore = store)
            advanceUntilIdle()

            assertEquals(
                listOf(Playlist.default(), Playlist.downloads(emptyList()), custom),
                vm.playlists.value,
            )
        }

    @Test
    fun `downloads playlist lists one row per song in download order`() =
        runTest(testDispatcher) {
            val trackA = track("BV1a", "111")
            val trackB = track("BV1b", "222")
            // The same song at two qualities lists once.
            val store = InMemoryDownloadStore(
                listOf(
                    record(trackA, 30280),
                    record(trackA, 30232),
                    record(trackB, 30280),
                ),
            )
            val vm = viewModel(downloadStore = store)
            advanceUntilIdle()

            assertEquals(listOf(trackA, trackB), vm.downloadsPlaylist().tracks)
        }

    @Test
    fun `downloads playlist updates when a download finishes`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.downloadsPlaylist().tracks.isEmpty())

        vm.downloadTrack(track("BV1a", "111"))
        advanceUntilIdle()

        val tracks = vm.downloadsPlaylist().tracks
        assertEquals(1, tracks.size)
        assertEquals("BV1a", tracks.single().id)
    }

    @Test
    fun `deleting a download removes its row, its record, and its file`() =
        runTest(testDispatcher) {
            val local = record(track("BV1a", "111"), 30280)
            seedFile(local)
            val vm = viewModel(downloadStore = InMemoryDownloadStore(listOf(local)))
            advanceUntilIdle()
            assertEquals(1, vm.downloadsPlaylist().tracks.size)

            vm.deleteDownload(local)
            advanceUntilIdle()

            assertTrue(vm.downloadsPlaylist().tracks.isEmpty())
            assertTrue(vm.downloadRecords.value.isEmpty())
            assertFalse(File(downloadsDir, local.fileName).exists())
        }

    @Test
    fun `deleting every quality of a song removes its row`() = runTest(testDispatcher) {
        val trackA = track("BV1a", "111")
        val high = record(trackA, 30280)
        val low = record(trackA, 30232)
        seedFile(high)
        seedFile(low)
        val vm = viewModel(downloadStore = InMemoryDownloadStore(listOf(high, low)))
        advanceUntilIdle()
        assertEquals(1, vm.downloadsPlaylist().tracks.size)

        // The detail screen deletes one record per call, for
        // every record of the selected song.
        vm.deleteDownload(high)
        vm.deleteDownload(low)
        advanceUntilIdle()

        assertTrue(vm.downloadsPlaylist().tracks.isEmpty())
        assertTrue(vm.downloadRecords.value.isEmpty())
    }

    @Test
    fun `addTrackToPlaylist to downloads is a no-op returning false`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            assertFalse(vm.addTrackToPlaylist(Playlist.DOWNLOADS_ID, track("BV1a", "111")))
            advanceUntilIdle()

            assertTrue(vm.downloadsPlaylist().tracks.isEmpty())
        }

    @Test
    fun `removeTracksFromPlaylist on downloads is a no-op`() = runTest(testDispatcher) {
        val local = record(track("BV1a", "111"), 30280)
        val vm = viewModel(downloadStore = InMemoryDownloadStore(listOf(local)))
        advanceUntilIdle()

        vm.removeTracksFromPlaylist(Playlist.DOWNLOADS_ID, setOf(local.track.key))
        advanceUntilIdle()

        assertEquals(1, vm.downloadsPlaylist().tracks.size)
        assertEquals(1, vm.downloadRecords.value.size)
    }

    @Test
    fun `deletePlaylist cannot delete the downloads playlist`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.deletePlaylist(Playlist.DOWNLOADS_ID)
        advanceUntilIdle()

        assertEquals(
            listOf(Playlist.default(), Playlist.downloads(emptyList())),
            vm.playlists.value,
        )
    }

    @Test
    fun `the downloads playlist is never persisted`() = runTest(testDispatcher) {
        val store = InMemoryPlaylistStore()
        val vm = viewModel(playlistStore = store)
        advanceUntilIdle()

        vm.downloadTrack(track("BV1a", "111"))
        vm.createPlaylist("Mix")
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1b", "222"))
        advanceUntilIdle()

        val stored = store.load().orEmpty()
        assertTrue(stored.none { it.isDownloads })
        assertEquals(2, stored.size)
    }

    @Test
    fun `detail lookup finds the synthetic playlist by id`() = runTest(testDispatcher) {
        val trackA = track("BV1a", "111")
        val vm = viewModel(
            downloadStore = InMemoryDownloadStore(listOf(record(trackA, 30280))),
        )
        advanceUntilIdle()

        // The lookup the detail screen performs for its route id.
        val found = vm.playlists.value.firstOrNull { it.id == Playlist.DOWNLOADS_ID }
        assertEquals(Playlist.downloads(listOf(trackA)), found)
    }
}
