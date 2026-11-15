package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryPlaybackModeStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.PlaybackMode
import com.siyu.musique.data.PlaybackModeStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.RepeatMode
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Playback modes: sequential stops at the ends, repeat-all wraps,
 * repeat-one replays in place on track end, shuffle picks another
 * queue member and previous walks back through the shuffle
 * history, and the mode persists across ViewModels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackModesTest {

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

    private class ModeRepository(
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
        repository: SearchRepository,
        engine: FakePlayerEngine,
        modeStore: PlaybackModeStore = InMemoryPlaybackModeStore(),
        random: Random = Random(1234),
    ) = SearchViewModel(
        repository,
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        playbackModeStore = modeStore,
        random = random,
    )

    private fun threeTrackViewModel(engine: FakePlayerEngine) =
        viewModel(ModeRepository(listOf(trackA, trackB, trackC)), engine)

    @Test
    fun `sequential play stops at both ends of the queue`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = threeTrackViewModel(engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.playNext()
        advanceUntilIdle()
        assertEquals("B", vm.currentTrack.value?.title)
        vm.playNext()
        advanceUntilIdle()
        assertEquals("C", vm.currentTrack.value?.title)
        vm.playNext()
        advanceUntilIdle()
        assertEquals("C", vm.currentTrack.value?.title)
        assertEquals(3, engine.playCalls.size)
        vm.playPrevious()
        advanceUntilIdle()
        assertEquals("B", vm.currentTrack.value?.title)
        vm.playPrevious()
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
        vm.playPrevious()
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
    }

    @Test
    fun `repeat all wraps around both ends`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = threeTrackViewModel(engine)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, vm.repeatMode.value)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.playPrevious()
        advanceUntilIdle()
        assertEquals("C", vm.currentTrack.value?.title)
        vm.playNext()
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
        // Auto-advance wraps too: the last track ends, the first plays.
        vm.playNext()
        advanceUntilIdle()
        vm.playNext()
        advanceUntilIdle()
        assertEquals("C", vm.currentTrack.value?.title)
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
    }

    @Test
    fun `repeat one replays the same track in place on end`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = threeTrackViewModel(engine)
        vm.cycleRepeatMode()
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ONE, vm.repeatMode.value)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
        // No fresh URL, no new play call: seek to the start and resume.
        assertEquals(1, engine.playCalls.size)
        assertEquals(listOf(0L), engine.seekCalls)
        assertEquals(1, engine.resumeCalls)
        // Manual next still moves on; repeat-one only affects track end.
        vm.playNext()
        advanceUntilIdle()
        assertEquals("B", vm.currentTrack.value?.title)
    }

    @Test
    fun `repeat button cycles off all one off`() = runTest(testDispatcher) {
        val vm = threeTrackViewModel(FakePlayerEngine())
        advanceUntilIdle()
        assertEquals(RepeatMode.OFF, vm.repeatMode.value)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, vm.repeatMode.value)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ONE, vm.repeatMode.value)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.OFF, vm.repeatMode.value)
    }

    @Test
    fun `shuffle next stays in the queue and never repeats the current track`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = threeTrackViewModel(engine)
            vm.toggleShuffle()
            assertTrue(vm.shuffleEnabled.value)
            vm.search("x")
            advanceUntilIdle()
            vm.play(trackA)
            advanceUntilIdle()
            var previous = vm.currentTrack.value
            repeat(10) {
                vm.playNext()
                advanceUntilIdle()
                val current = vm.currentTrack.value
                assertTrue(current?.id in setOf("BV1a", "BV1b", "BV1c"))
                assertNotEquals(previous?.key, current?.key)
                previous = current
            }
        }

    @Test
    fun `shuffle previous walks back through the shuffle history`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = threeTrackViewModel(engine)
        vm.toggleShuffle()
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.playNext()
        advanceUntilIdle()
        val second = vm.currentTrack.value
        vm.playNext()
        advanceUntilIdle()
        vm.playPrevious()
        advanceUntilIdle()
        assertEquals(second?.key, vm.currentTrack.value?.key)
        vm.playPrevious()
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
    }

    @Test
    fun `a new queue clears the shuffle history`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = threeTrackViewModel(engine)
        vm.toggleShuffle()
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.playNext()
        advanceUntilIdle()
        // Playing another track adopts the queue again, clearing history.
        vm.play(trackC)
        advanceUntilIdle()
        vm.playPrevious()
        advanceUntilIdle()
        // Sequential previous of C is B; stale history would have given A.
        assertEquals("B", vm.currentTrack.value?.title)
    }

    @Test
    fun `shuffle with a one-track queue replays the track`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(ModeRepository(listOf(trackA)), engine)
        vm.toggleShuffle()
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.playNext()
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
        assertEquals(2, engine.playCalls.size)
    }

    @Test
    fun `playback mode persists and loads on a fresh view model`() = runTest(testDispatcher) {
        val store = InMemoryPlaybackModeStore()
        val vm = viewModel(ModeRepository(listOf(trackA)), FakePlayerEngine(), store)
        vm.cycleRepeatMode()
        vm.toggleShuffle()
        advanceUntilIdle()
        assertEquals(PlaybackMode(RepeatMode.ALL, shuffleEnabled = true), store.load())
        val fresh = viewModel(ModeRepository(listOf(trackA)), FakePlayerEngine(), store)
        advanceUntilIdle()
        assertEquals(RepeatMode.ALL, fresh.repeatMode.value)
        assertTrue(fresh.shuffleEnabled.value)
    }
}
