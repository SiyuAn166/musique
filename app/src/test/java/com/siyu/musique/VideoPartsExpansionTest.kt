package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.SearchViewModel
import kotlin.time.Duration.Companion.seconds
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Parts expansion for the search results and playlist lists:
 * [SearchViewModel.loadVideoParts] caches per video, and once a
 * video is known to have parts, a part is its own playlist
 * entry, separate from the whole collection, in both add orders.
 * A video with a single known part keeps the old rule: the
 * collection and its filled-in form are one entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPartsExpansionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A repository serving scripted parts per bvid, with a failure switch for fetchDetail. */
    private class PartsRepository(
        private val partsByVideo: Map<String, List<Track>>,
    ) : SearchRepository {
        var detailCalls: Int = 0
            private set
        var failDetail: Boolean = false

        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            detailCalls++
            if (failDetail) throw RuntimeException("detail boom")
            val parts = partsByVideo[track.id] ?: listOf(track)
            return DetailedTrack(parts.first(), parts)
        }

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.id}.m4s", 216670L)
    }

    /** A repository whose parts probe is separate from details, pinning the cheap path. */
    private class PartsProbeRepository(
        private val parts: List<Track>,
    ) : SearchRepository {
        var fetchPartsCalls: Int = 0
            private set
        var detailCalls: Int = 0
            private set

        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            detailCalls++
            throw RuntimeException("details must not be called for the probe")
        }

        override suspend fun fetchParts(track: Track): List<Track> {
            fetchPartsCalls++
            return parts
        }

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.id}.m4s", 216670L)
    }

    /** A repository whose parts probe waits on a gate, so tests can observe a load in flight. */
    private class SuspendingPartsRepository : SearchRepository {
        val gate = CompletableDeferred<List<Track>>()
        var fetchPartsCalls: Int = 0
            private set

        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): DetailedTrack =
            throw RuntimeException("details must not be called for the probe")

        override suspend fun fetchParts(track: Track): List<Track> {
            fetchPartsCalls++
            return gate.await()
        }

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.id}.m4s", 216670L)
    }

    private fun track(id: String, cid: String, title: String = "$id:$cid") = Track(
        id = id,
        cid = cid,
        title = title,
        artist = "artist",
        cover = "",
        duration = 200.seconds,
    )

    private fun viewModel(
        repository: SearchRepository,
        engine: FakePlayerEngine = FakePlayerEngine(),
        store: InMemoryPlaylistStore = InMemoryPlaylistStore(),
    ) = SearchViewModel(
        repository,
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        store,
    )

    private fun defaultTracks(vm: SearchViewModel): List<Track> =
        vm.playlists.value.first { it.isDefault }.tracks

    @Test
    fun `loadVideoParts caches parts and fetches once`() = runTest(testDispatcher) {
        val parts = listOf(track("BV1PARTS", "1"), track("BV1PARTS", "2"), track("BV1PARTS", "3"))
        val repository = PartsRepository(mapOf("BV1PARTS" to parts))
        val vm = viewModel(repository)
        advanceUntilIdle()

        val video = track("BV1PARTS", cid = "")
        vm.loadVideoParts(video)
        advanceUntilIdle()
        assertEquals(1, repository.detailCalls)
        assertEquals(parts, vm.videoParts.value["BV1PARTS"])

        vm.loadVideoParts(video)
        advanceUntilIdle()
        assertEquals(1, repository.detailCalls)
    }

    @Test
    fun `loadVideoParts failure caches nothing and the next tap retries`() =
        runTest(testDispatcher) {
            val parts = listOf(track("BV1PARTS", "1"), track("BV1PARTS", "2"))
            val repository = PartsRepository(mapOf("BV1PARTS" to parts))
            repository.failDetail = true
            val vm = viewModel(repository)
            advanceUntilIdle()

            val video = track("BV1PARTS", cid = "")
            vm.loadVideoParts(video)
            advanceUntilIdle()
            assertEquals(1, repository.detailCalls)
            assertEquals(null, vm.videoParts.value["BV1PARTS"])

            repository.failDetail = false
            vm.loadVideoParts(video)
            advanceUntilIdle()
            assertEquals(2, repository.detailCalls)
            assertEquals(parts, vm.videoParts.value["BV1PARTS"])
        }

    @Test
    fun `a part can be added when the collection is already present, once parts are known`() =
        runTest(testDispatcher) {
            val parts = listOf(track("BV1PARTS", "1"), track("BV1PARTS", "2"), track("BV1PARTS", "3"))
            val vm = viewModel(PartsRepository(mapOf("BV1PARTS" to parts)))
            advanceUntilIdle()

            assertTrue(
                vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PARTS", cid = "")),
            )
            vm.loadVideoParts(track("BV1PARTS", cid = ""))
            advanceUntilIdle()
            assertTrue(
                vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PARTS", cid = "2")),
            )
            advanceUntilIdle()
            assertEquals(2, defaultTracks(vm).size)
        }

    @Test
    fun `the collection can be added when a part is already present, once parts are known`() =
        runTest(testDispatcher) {
            val parts = listOf(track("BV1PARTS", "1"), track("BV1PARTS", "2"), track("BV1PARTS", "3"))
            val vm = viewModel(PartsRepository(mapOf("BV1PARTS" to parts)))
            advanceUntilIdle()

            assertTrue(
                vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PARTS", cid = "2")),
            )
            vm.loadVideoParts(track("BV1PARTS", cid = ""))
            advanceUntilIdle()
            assertTrue(
                vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PARTS", cid = "")),
            )
            advanceUntilIdle()
            assertEquals(2, defaultTracks(vm).size)
        }

    @Test
    fun `adding the same part twice is a no-op`() = runTest(testDispatcher) {
        val parts = listOf(track("BV1PARTS", "1"), track("BV1PARTS", "2"))
        val vm = viewModel(PartsRepository(mapOf("BV1PARTS" to parts)))
        advanceUntilIdle()

        assertTrue(
            vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PARTS", cid = "1")),
        )
        vm.loadVideoParts(track("BV1PARTS", cid = ""))
        advanceUntilIdle()
        assertFalse(
            vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PARTS", cid = "1")),
        )
        advanceUntilIdle()
        assertEquals(1, defaultTracks(vm).size)
    }

    @Test
    fun `partsLoadingIds holds the video while the load runs, then clears`() = runTest(testDispatcher) {
        val parts = listOf(track("BV1LOAD", "1"), track("BV1LOAD", "2"))
        val repository = SuspendingPartsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.loadVideoParts(track("BV1LOAD", cid = ""))
        runCurrent()
        assertTrue(vm.partsLoadingIds.value.contains("BV1LOAD"))
        assertEquals(1, repository.fetchPartsCalls)
        repository.gate.complete(parts)
        advanceUntilIdle()
        assertTrue(vm.partsLoadingIds.value.isEmpty())
        assertEquals(parts, vm.videoParts.value["BV1LOAD"])
    }

    @Test
    fun `partsLoadingIds clears when the load fails`() = runTest(testDispatcher) {
        val repository = PartsRepository(emptyMap())
        repository.failDetail = true
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.loadVideoParts(track("BV1FAIL", cid = ""))
        advanceUntilIdle()
        assertTrue(vm.partsLoadingIds.value.isEmpty())
        assertFalse(vm.videoParts.value.containsKey("BV1FAIL"))
    }

    @Test
    fun `loadVideoParts uses the parts probe, not details`() = runTest(testDispatcher) {
        val parts = listOf(track("BV1PROBE", "1"), track("BV1PROBE", "2"))
        val repository = PartsProbeRepository(parts)
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.loadVideoParts(track("BV1PROBE", cid = ""))
        advanceUntilIdle()
        assertEquals(1, repository.fetchPartsCalls)
        assertEquals(0, repository.detailCalls)
        assertEquals(parts, vm.videoParts.value["BV1PROBE"])
    }

    @Test
    fun `a single-part video keeps the old duplicate rule after its parts load`() =
        runTest(testDispatcher) {
            val parts = listOf(track("BV1SINGLE", "7"))
            val vm = viewModel(PartsRepository(mapOf("BV1SINGLE" to parts)))
            advanceUntilIdle()

            assertTrue(
                vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1SINGLE", cid = "")),
            )
            vm.loadVideoParts(track("BV1SINGLE", cid = ""))
            advanceUntilIdle()
            assertFalse(
                vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1SINGLE", cid = "7")),
            )
            advanceUntilIdle()
            assertEquals(1, defaultTracks(vm).size)
        }
}
