package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.SearchUiState
import com.siyu.musique.ui.SearchViewModel
import kotlin.random.Random
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Multi-part videos: the queue always keeps the list the video
 * came from, and next/previous/auto-advance walk the video's
 * parts within that list before moving to the next queue item.
 * The search row keeps the video title, and playlist playback
 * walks parts the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartsQueueTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun part(cid: String, title: String, durationSeconds: Long) = Track(
        id = "BV1multi",
        cid = cid,
        title = title,
        artist = "A",
        cover = "https://cover.example/x.jpg",
        duration = durationSeconds.seconds,
    )

    private class PartsRepository(
        private val parts: List<Track>,
    ) : SearchRepository {
        val videoTrack = Track(
            id = "BV1multi",
            cid = "",
            title = "Video Title",
            artist = "A",
            cover = "https://cover.example/x.jpg",
            duration = 600.seconds,
        )
        val otherTrack = Track(
            id = "BV1other",
            cid = "111",
            title = "Other",
            artist = "B",
            cover = "",
            duration = 200.seconds,
        )

        override suspend fun search(query: String): List<Track> =
            listOf(videoTrack, otherTrack)

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            val filled = track.copy(cid = parts[0].cid, title = "Video Title")
            return DetailedTrack(filled, parts)
        }

        override suspend fun fetchPlayUrl(
            track: Track,
            preference: AudioQuality,
        ): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.id}-${track.cid}.m4s", 216670L)
    }

    private fun viewModel(
        repository: SearchRepository,
        engine: FakePlayerEngine,
        random: Random = Random.Default,
    ) =
        SearchViewModel(
            repository,
            engine,
            MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
            InMemoryQualityPreferenceStore(),
            random = random,
        )

    private val threeParts = listOf(
        part("101", "001. Part One", 228),
        part("102", "002. Part Two", 241),
        part("103", "003. Part Three", 217),
    )

    @Test
    fun `playing a multi-part search track keeps the search list and walks parts within it`() =
        runTest(testDispatcher) {
            val repository = PartsRepository(threeParts)
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.search("x")
            advanceUntilIdle()
            vm.play(repository.videoTrack)
            advanceUntilIdle()
            // The queue keeps the search list; the parts never replace it.
            assertEquals(
                listOf(repository.videoTrack, repository.otherTrack),
                vm.queue.value,
            )
            assertEquals(threeParts, vm.currentParts.value)
            // The video-level track plays first, with the first part's cid.
            assertEquals("Video Title", vm.currentTrack.value?.title)
            assertEquals("101", vm.currentTrack.value?.cid)

            vm.playNext()
            advanceUntilIdle()
            assertEquals("002. Part Two", vm.currentTrack.value?.title)

            vm.playNext()
            advanceUntilIdle()
            assertEquals("003. Part Three", vm.currentTrack.value?.title)

            // Past the last part, next moves to the next queue item.
            vm.playNext()
            advanceUntilIdle()
            assertEquals("Other", vm.currentTrack.value?.title)

            // Past the last queue item, next is a no-op.
            vm.playNext()
            advanceUntilIdle()
            assertEquals("Other", vm.currentTrack.value?.title)

            // Previous returns to the video; the first part of the
            // first item has no previous.
            vm.playPrevious()
            advanceUntilIdle()
            assertEquals("Video Title", vm.currentTrack.value?.title)
            vm.playPrevious()
            advanceUntilIdle()
            assertEquals("Video Title", vm.currentTrack.value?.title)

            // The search row keeps the video-level title, not the part name.
            val state = vm.searchState.value as SearchUiState.Success
            assertEquals("Video Title", state.tracks[0].title)
        }

    @Test
    fun `previous walks back through parts then to the previous queue item`() =
        runTest(testDispatcher) {
            val repository = PartsRepository(threeParts)
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            val playlist = Playlist(
                id = "p1",
                name = "P",
                tracks = listOf(repository.otherTrack, repository.videoTrack),
            )
            vm.playFromPlaylist(repository.videoTrack, playlist)
            advanceUntilIdle()
            vm.playNext()
            advanceUntilIdle()
            assertEquals("002. Part Two", vm.currentTrack.value?.title)
            vm.playPrevious()
            advanceUntilIdle()
            assertEquals("001. Part One", vm.currentTrack.value?.title)
            vm.playPrevious()
            advanceUntilIdle()
            assertEquals("Other", vm.currentTrack.value?.title)
        }

    @Test
    fun `track end auto-advances through parts then to the next queue item`() =
        runTest(testDispatcher) {
            val repository = PartsRepository(threeParts)
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.search("x")
            advanceUntilIdle()
            vm.play(repository.videoTrack)
            advanceUntilIdle()
            engine.emitEnded()
            advanceUntilIdle()
            assertEquals("002. Part Two", vm.currentTrack.value?.title)
            engine.emitEnded()
            advanceUntilIdle()
            assertEquals("003. Part Three", vm.currentTrack.value?.title)
            engine.emitEnded()
            advanceUntilIdle()
            assertEquals("Other", vm.currentTrack.value?.title)
        }

    @Test
    fun `repeat all wraps from the last part of the last item to the first item`() =
        runTest(testDispatcher) {
            val repository = PartsRepository(threeParts)
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.cycleRepeatMode() // OFF -> ALL
            val playlist = Playlist(
                id = "p1",
                name = "P",
                tracks = listOf(repository.otherTrack, repository.videoTrack),
            )
            vm.playFromPlaylist(repository.videoTrack, playlist)
            advanceUntilIdle()
            vm.playNext()
            advanceUntilIdle()
            vm.playNext()
            advanceUntilIdle()
            assertEquals("003. Part Three", vm.currentTrack.value?.title)
            vm.playNext()
            advanceUntilIdle()
            assertEquals("Other", vm.currentTrack.value?.title)
        }

    @Test
    fun `repeat one replays the part in place on end`() = runTest(testDispatcher) {
        val repository = PartsRepository(threeParts)
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        vm.cycleRepeatMode()
        vm.cycleRepeatMode() // OFF -> ALL -> ONE
        vm.search("x")
        advanceUntilIdle()
        vm.play(repository.videoTrack)
        advanceUntilIdle()
        vm.playNext()
        advanceUntilIdle()
        assertEquals("002. Part Two", vm.currentTrack.value?.title)
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals("002. Part Two", vm.currentTrack.value?.title)
        assertEquals(2, engine.playCalls.size)
        assertEquals(listOf(0L), engine.seekCalls)
        assertEquals(1, engine.resumeCalls)
        // Manual next still advances to the next part.
        vm.playNext()
        advanceUntilIdle()
        assertEquals("003. Part Three", vm.currentTrack.value?.title)
    }

    @Test
    fun `shuffle jumps between queue items without walking parts`() = runTest(testDispatcher) {
        val repository = PartsRepository(threeParts)
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine, Random(42))
        vm.toggleShuffle()
        vm.search("x")
        advanceUntilIdle()
        vm.play(repository.videoTrack)
        advanceUntilIdle()
        repeat(6) {
            vm.playNext()
            advanceUntilIdle()
            // Shuffle lands on queue items only: the video resolves
            // to its first part, never to a later part directly.
            assertTrue(vm.currentTrack.value?.title in setOf("Video Title", "Other"))
        }
        assertEquals(
            listOf(repository.videoTrack, repository.otherTrack),
            vm.queue.value,
        )
    }

    @Test
    fun `single-part video keeps the search list as the queue`() = runTest(testDispatcher) {
        val repository = PartsRepository(listOf(threeParts[0]))
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(repository.videoTrack)
        advanceUntilIdle()
        vm.playNext()
        advanceUntilIdle()
        assertEquals("Other", vm.currentTrack.value?.title)
    }

    @Test
    fun `playlist play walks parts then continues the playlist`() = runTest(testDispatcher) {
        val repository = PartsRepository(threeParts)
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        val playlist = Playlist(
            id = "p1",
            name = "P",
            tracks = listOf(repository.videoTrack, repository.otherTrack),
        )
        vm.playFromPlaylist(repository.videoTrack, playlist)
        advanceUntilIdle()
        // The video-level track plays, not part one.
        assertEquals("Video Title", vm.currentTrack.value?.title)
        assertEquals(
            listOf(repository.videoTrack, repository.otherTrack),
            vm.queue.value,
        )
        vm.playNext()
        advanceUntilIdle()
        assertEquals("002. Part Two", vm.currentTrack.value?.title)
        vm.playNext()
        advanceUntilIdle()
        assertEquals("003. Part Three", vm.currentTrack.value?.title)
        vm.playNext()
        advanceUntilIdle()
        assertEquals("Other", vm.currentTrack.value?.title)
        assertTrue(engine.playCalls.size == 4)
    }
}
