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
 * Multi-part videos: tapping one from search plays every part in
 * order (the parts become the queue), the player shows the part
 * playing while the search row keeps the video title, and playlist
 * playback is untouched (the playlist stays the queue).
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

    private fun viewModel(repository: SearchRepository, engine: FakePlayerEngine) =
        SearchViewModel(
            repository,
            engine,
            MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
            InMemoryQualityPreferenceStore(),
        )

    private val threeParts = listOf(
        part("101", "001. Part One", 228),
        part("102", "002. Part Two", 241),
        part("103", "003. Part Three", 217),
    )

    @Test
    fun `playing a multi-part search track queues all parts in order`() =
        runTest(testDispatcher) {
            val repository = PartsRepository(threeParts)
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.search("x")
            advanceUntilIdle()
            vm.play(repository.videoTrack)
            advanceUntilIdle()
            assertEquals("001. Part One", vm.currentTrack.value?.title)
            assertEquals("101", vm.currentTrack.value?.cid)

            vm.playNext()
            advanceUntilIdle()
            assertEquals("002. Part Two", vm.currentTrack.value?.title)

            vm.playNext()
            advanceUntilIdle()
            assertEquals("003. Part Three", vm.currentTrack.value?.title)

            // Past the last part, next is a no-op: the queue ends.
            vm.playNext()
            advanceUntilIdle()
            assertEquals("003. Part Three", vm.currentTrack.value?.title)

            vm.playPrevious()
            advanceUntilIdle()
            assertEquals("002. Part Two", vm.currentTrack.value?.title)

            // The search row keeps the video-level title, not the part name.
            val state = vm.searchState.value as SearchUiState.Success
            assertEquals("Video Title", state.tracks[0].title)
        }

    @Test
    fun `track end auto-advances to the next part`() = runTest(testDispatcher) {
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
    fun `playlist play does not adopt the parts queue`() = runTest(testDispatcher) {
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
        vm.playNext()
        advanceUntilIdle()
        assertEquals("Other", vm.currentTrack.value?.title)
        assertTrue(engine.playCalls.size == 2)
    }
}
