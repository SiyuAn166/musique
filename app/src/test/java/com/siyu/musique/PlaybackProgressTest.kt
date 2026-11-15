package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.ui.PlaybackProgress
import com.siyu.musique.ui.PlayerUiState
import com.siyu.musique.ui.SearchUiState
import com.siyu.musique.ui.SearchViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * Step 4 ViewModel behavior: progress polling, seek clamping,
 * auto-advance on track end, and quality preference changes.
 *
 * Polling runs on a 500ms loop while playing, so tests advance
 * virtual time with [advanceTimeBy] while polling is active and
 * never call [advanceUntilIdle] until polling has stopped (pause,
 * end, or error): advancing to idle with an active poll loop would
 * never finish. Advance strictly past the interval (600ms, not
 * 500ms): a task scheduled exactly at +500ms does not run within
 * advanceTimeBy(500) in this coroutines-test version, and a failed
 * assert before the pause would leave polling running and hang the
 * runTest cleanup the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A repository that records the quality preference of every play URL resolve. */
    private class RecordingRepository : SearchRepository {
        val playUrlPreferences = mutableListOf<AudioQuality>()
        var playUrlCalls: Int = 0
            private set

        override suspend fun search(query: String): List<Track> =
            FakeSearchRepository.FAKE_TRACKS

        override suspend fun fetchDetail(track: Track): DetailedTrack =
            DetailedTrack(track, listOf(track))

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream {
            playUrlCalls++
            playUrlPreferences.add(preference)
            return AudioStream(30280, "https://cdn.example/q-${track.id}.m4s", 216670L)
        }
    }

    private fun track(durationSeconds: Long = 228L) = Track(
        id = "BV1q4FTeBEMj",
        cid = "28139389900",
        title = "t",
        artist = "a",
        cover = "",
        duration = durationSeconds.seconds,
    )

    private fun viewModel(
        repository: SearchRepository,
        engine: FakePlayerEngine,
        store: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
        logger: MusiqueLogger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
    ) = SearchViewModel(repository, engine, logger, store)

    @Test
    fun `seekTo clamps to the track duration and calls engine seekTo`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(RecordingRepository(), engine)
        vm.play(track())
        advanceUntilIdle()
        vm.seekTo(1_000_000L)
        assertEquals(listOf(228_000L), engine.seekCalls)
        vm.seekTo(-5L)
        assertEquals(listOf(228_000L, 0L), engine.seekCalls)
        vm.seekTo(60_000L)
        assertEquals(listOf(228_000L, 0L, 60_000L), engine.seekCalls)
        assertEquals(PlaybackProgress(60_000L, 228_000L), vm.playbackProgress.value)
    }

    @Test
    fun `seekTo uses the engine duration when the engine knows it`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        engine.durationMsValue = 100_000L
        val vm = viewModel(RecordingRepository(), engine)
        vm.play(track())
        advanceUntilIdle()
        vm.seekTo(500_000L)
        assertEquals(listOf(100_000L), engine.seekCalls)
    }

    @Test
    fun `seekTo does nothing with no loaded item`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(RecordingRepository(), engine)
        vm.seekTo(5_000L)
        assertTrue(engine.seekCalls.isEmpty())
        assertEquals(PlaybackProgress(0L, 0L), vm.playbackProgress.value)
    }

    @Test
    fun `progress polling emits engine positions while playing and stops on pause`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(RecordingRepository(), engine)
            vm.play(track())
            advanceUntilIdle()
            engine.positionMs = 10_000L
            engine.emitPlaying()
            runCurrent()
            assertEquals(PlaybackProgress(10_000L, 228_000L), vm.playbackProgress.value)
            engine.positionMs = 10_500L
            // Advance strictly past one poll interval: a task
            // scheduled exactly at +500ms does not run within
            // advanceTimeBy(500) in this coroutines-test version.
            advanceTimeBy(600L)
            assertEquals(PlaybackProgress(10_500L, 228_000L), vm.playbackProgress.value)
            engine.emitPaused()
            engine.positionMs = 20_000L
            advanceTimeBy(2_000L)
            assertEquals(PlaybackProgress(10_500L, 228_000L), vm.playbackProgress.value)
        }

    @Test
    fun `progress does not poll before playing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(RecordingRepository(), engine)
        vm.play(track())
        advanceUntilIdle()
        engine.positionMs = 30_000L
        advanceTimeBy(2_000L)
        assertEquals(PlaybackProgress(0L, 228_000L), vm.playbackProgress.value)
    }

    @Test
    fun `seeking while polling keeps the slider and the poll in agreement`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(RecordingRepository(), engine)
            vm.play(track())
            advanceUntilIdle()
            engine.emitPlaying()
            vm.seekTo(50_000L)
            assertEquals(PlaybackProgress(50_000L, 228_000L), vm.playbackProgress.value)
            advanceTimeBy(500L)
            // The poll samples the engine, which the seek just moved.
            assertEquals(PlaybackProgress(50_000L, 228_000L), vm.playbackProgress.value)
            engine.emitPaused()
        }

    @Test
    fun `auto-advance plays the next track on ended`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(FakeSearchRepository(), engine)
        vm.search("Demo")
        advanceUntilIdle()
        val tracks = (vm.searchState.value as SearchUiState.Success).tracks
        vm.play(tracks[0])
        advanceUntilIdle()
        engine.emitPlaying()
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals(2, engine.playCalls.size)
        assertEquals(tracks[1].id, vm.currentTrack.value?.id)
        assertTrue(vm.playerState.value is PlayerUiState.Fetching)
    }

    @Test
    fun `ended on the last track stops paused without playing anything new`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(FakeSearchRepository(), engine)
            vm.search("Demo")
            advanceUntilIdle()
            val tracks = (vm.searchState.value as SearchUiState.Success).tracks
            vm.play(tracks.last())
            advanceUntilIdle()
            engine.emitPlaying()
            engine.emitEnded()
            advanceUntilIdle()
            assertEquals(1, engine.playCalls.size)
            assertTrue(vm.playerState.value is PlayerUiState.Paused)
            assertFalse(vm.isPlaying.value)
        }

    @Test
    fun `quality change re-resolves at the new quality and seeks back to the position`() =
        runTest(testDispatcher) {
            val repository = RecordingRepository()
            val store = InMemoryQualityPreferenceStore()
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine, store)
            vm.play(track())
            advanceUntilIdle()
            engine.emitPlaying()
            engine.positionMs = 60_000L
            vm.setQualityPreference(AudioQuality.Q132)
            advanceUntilIdle()
            assertEquals(listOf(AudioQuality.BEST, AudioQuality.Q132), repository.playUrlPreferences)
            assertEquals(2, engine.playCalls.size)
            assertEquals(listOf(60_000L), engine.seekCalls)
            assertEquals(PlaybackProgress(60_000L, 228_000L), vm.playbackProgress.value)
            assertEquals(AudioQuality.Q132, vm.qualityPreference.value)
            assertEquals(AudioQuality.Q132, store.load())
        }

    @Test
    fun `quality change while paused keeps the track paused`() = runTest(testDispatcher) {
        val repository = RecordingRepository()
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        vm.play(track())
        advanceUntilIdle()
        engine.emitPlaying()
        engine.emitPaused()
        engine.positionMs = 30_000L
        vm.setQualityPreference(AudioQuality.Q64)
        advanceUntilIdle()
        assertEquals(2, engine.playCalls.size)
        assertEquals(listOf(30_000L), engine.seekCalls)
        assertEquals(1, engine.pauseCalls)
        assertTrue(vm.playerState.value is PlayerUiState.Paused)
        assertFalse(vm.isPlaying.value)
    }

    @Test
    fun `quality change with nothing loaded only affects the next play`() =
        runTest(testDispatcher) {
            val repository = RecordingRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.setQualityPreference(AudioQuality.Q132)
            advanceUntilIdle()
            assertEquals(0, repository.playUrlCalls)
            assertTrue(engine.playCalls.isEmpty())
            vm.play(track())
            advanceUntilIdle()
            assertEquals(listOf(AudioQuality.Q132), repository.playUrlPreferences)
        }

    @Test
    fun `setting the same quality is a no-op`() = runTest(testDispatcher) {
        val repository = RecordingRepository()
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        vm.play(track())
        advanceUntilIdle()
        vm.setQualityPreference(AudioQuality.BEST)
        advanceUntilIdle()
        assertEquals(1, repository.playUrlCalls)
        assertEquals(1, engine.playCalls.size)
        assertTrue(engine.seekCalls.isEmpty())
    }

    @Test
    fun `viewmodel loads the stored quality preference at startup`() = runTest(testDispatcher) {
        val store = InMemoryQualityPreferenceStore(AudioQuality.Q64)
        val vm = viewModel(RecordingRepository(), FakePlayerEngine(), store)
        advanceUntilIdle()
        assertEquals(AudioQuality.Q64, vm.qualityPreference.value)
    }

    @Test
    fun `default quality preference is best when nothing is stored`() = runTest(testDispatcher) {
        val vm = viewModel(RecordingRepository(), FakePlayerEngine())
        advanceUntilIdle()
        assertEquals(AudioQuality.BEST, vm.qualityPreference.value)
    }

    @Test
    fun `engine unknown duration falls back to the track duration in progress`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            engine.durationMsValue = PlayerEngine.UNKNOWN_DURATION
            val vm = viewModel(RecordingRepository(), engine)
            vm.play(track(durationSeconds = 183L))
            advanceUntilIdle()
            engine.emitPlaying()
            assertEquals(PlaybackProgress(0L, 183_000L), vm.playbackProgress.value)
            engine.emitPaused()
        }
}
