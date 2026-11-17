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
import org.junit.Before
import org.junit.Test

/**
 * The playing queue the queue sheet shows: it follows whatever
 * list the current track was played from, survives a restore,
 * and jumping inside it never rewrites it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueListTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(id: String, title: String) = Track(
        id = id,
        cid = "cid-$id",
        title = title,
        artist = "A",
        cover = "",
        duration = 200.seconds,
    )

    private val trackA = track("BV1a", "A")
    private val trackB = track("BV1b", "B")
    private val trackC = track("BV1c", "C")

    private class QueueRepository(
        private val results: List<Track>,
    ) : SearchRepository {
        override suspend fun search(query: String): List<Track> = results

        override suspend fun fetchDetail(track: Track): DetailedTrack =
            DetailedTrack(track, listOf(track))

        override suspend fun fetchPlayUrl(
            track: Track,
            preference: AudioQuality,
        ): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.id}.m4s", 216670L)
    }

    private fun viewModel(
        engine: FakePlayerEngine,
    ) = SearchViewModel(
        QueueRepository(listOf(trackA, trackB)),
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
    )

    @Test
    fun `queue follows the adopted search list`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        assertEquals(listOf(trackA, trackB), vm.queue.value)
    }

    @Test
    fun `queue follows a playlist`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        val playlist = Playlist.default().copy(tracks = listOf(trackC, trackA))
        vm.playFromPlaylist(trackC, playlist)
        advanceUntilIdle()
        assertEquals(listOf(trackC, trackA), vm.queue.value)
    }

    @Test
    fun `playing from the queue jumps without changing it`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.playFromQueue(trackB)
        advanceUntilIdle()
        assertEquals("B", vm.currentTrack.value?.title)
        assertEquals(listOf(trackA, trackB), vm.queue.value)
        assertEquals(2, engine.playCalls.size)
    }

    @Test
    fun `playing the current track from the queue does nothing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.playFromQueue(trackA)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        assertEquals("A", vm.currentTrack.value?.title)
    }

}
