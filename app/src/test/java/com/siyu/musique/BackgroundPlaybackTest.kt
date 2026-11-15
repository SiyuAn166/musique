package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.player.PlaybackControlsRegistry
import com.siyu.musique.player.PlayerMediaMetadata
import com.siyu.musique.ui.PlayerUiState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The background playback wiring at the ViewModel level: song
 * metadata reaches the engine for the notification, pause and
 * resume work as standalone actions (not only through the
 * toggle), and the ViewModel answers the media session's
 * commands through the controls registry. The service itself
 * is Android-only and statically reviewed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundPlaybackTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        PlaybackControlsRegistry.handler = null
        Dispatchers.resetMain()
    }

    private fun track(id: String, title: String) = Track(
        id = id,
        cid = "cid-$id",
        title = title,
        artist = "Singer $title",
        cover = "https://cover.example/$id.jpg",
        duration = 200.seconds,
    )

    private val trackA = track("BV1a", "A")
    private val trackB = track("BV1b", "B")

    private class MetadataRepository(
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

    private fun viewModel(engine: FakePlayerEngine) = SearchViewModel(
        MetadataRepository(listOf(trackA, trackB)),
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
    )

    @Test
    fun `play passes song metadata to the engine`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        assertEquals(
            PlayerMediaMetadata("A", "Singer A", "https://cover.example/BV1a.jpg"),
            engine.lastMetadata,
        )
    }

    @Test
    fun `pausePlayback pauses only while playing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.pausePlayback()
        assertEquals(0, engine.pauseCalls)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        engine.emitPlaying()
        vm.pausePlayback()
        assertEquals(1, engine.pauseCalls)
        assertFalse(vm.isPlaying.value)
        assertTrue(vm.playerState.value is PlayerUiState.Paused)
        vm.pausePlayback()
        assertEquals(1, engine.pauseCalls)
    }

    @Test
    fun `resumePlayback resumes a paused track with media`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        engine.emitPlaying()
        engine.emitPaused()
        vm.resumePlayback()
        assertEquals(1, engine.resumeCalls)
        assertTrue(vm.isPlaying.value)
    }

    @Test
    fun `resumePlayback replays a paused track without media`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        // Pause while fetching: no media reached the engine yet.
        vm.togglePlayPause()
        assertTrue(vm.playerState.value is PlayerUiState.Paused)
        vm.resumePlayback()
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        assertEquals("A", vm.currentTrack.value?.title)
    }

    @Test
    fun `the view model answers media session commands through the registry`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(engine)
            assertSame(vm, PlaybackControlsRegistry.handler)
            vm.search("x")
            advanceUntilIdle()
            vm.play(trackA)
            advanceUntilIdle()
            engine.emitPlaying()
            PlaybackControlsRegistry.handler?.onToggleRequested()
            assertFalse(vm.isPlaying.value)
            PlaybackControlsRegistry.handler?.onToggleRequested()
            advanceUntilIdle()
            assertTrue(vm.isPlaying.value)
            PlaybackControlsRegistry.handler?.onNextRequested()
            advanceUntilIdle()
            assertEquals("B", vm.currentTrack.value?.title)
            PlaybackControlsRegistry.handler?.onPreviousRequested()
            advanceUntilIdle()
            assertEquals("A", vm.currentTrack.value?.title)
        }
}
