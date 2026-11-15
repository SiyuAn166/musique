package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.PlaybackProgress
import com.siyu.musique.ui.PlayerUiState
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
import org.junit.Before
import org.junit.Test

/**
 * Resume after pause: the real ExoPlayer reports onPlaying
 * synchronously inside the resume call, so resumePlayback must
 * set its Buffering state BEFORE resuming, or the synchronous
 * callback's Playing state gets clobbered back to Buffering and
 * the mini bar says Loading forever. The engine double mimics
 * that synchronous delivery with [FakePlayerEngine.firePlayingOnResume].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeStateTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class UrlRepository : SearchRepository {
        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): DetailedTrack =
            DetailedTrack(track, listOf(track))

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream =
            AudioStream(30280, "https://cdn.example/q.m4s", 216670L)
    }

    private fun track() = Track(
        id = "BV1q4FTeBEMj",
        cid = "28139389900",
        title = "t",
        artist = "a",
        cover = "",
        duration = 228L.seconds,
    )

    private fun viewModel(
        repository: SearchRepository,
        engine: FakePlayerEngine,
        store: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
        logger: MusiqueLogger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
    ) = SearchViewModel(repository, engine, logger, store)

    @Test
    fun `resume with synchronous onPlaying ends Playing and restarts polling`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(UrlRepository(), engine)
            vm.play(track())
            advanceUntilIdle()
            engine.emitPlaying()
            runCurrent()
            assertEquals(PlayerUiState.Playing, vm.playerState.value)

            vm.togglePlayPause()
            assertEquals(PlayerUiState.Paused, vm.playerState.value)

            engine.firePlayingOnResume = true
            vm.togglePlayPause()
            // No manual emit: the synchronous callback inside
            // resume must survive resumePlayback's own state
            // write, or the mini bar sticks on Loading.
            assertEquals(PlayerUiState.Playing, vm.playerState.value)

            engine.positionMs = 5_000L
            advanceTimeBy(600L)
            assertEquals(PlaybackProgress(5_000L, 228_000L), vm.playbackProgress.value)

            vm.togglePlayPause()
            assertEquals(PlayerUiState.Paused, vm.playerState.value)
        }
}
