package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryPlaybackStateStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SavedPlayback
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Remembering playback across restarts: a saved queue, track,
 * and position restore paused (never autoplaying), resuming
 * seeks to the saved position, playing/pausing/seeking save,
 * and a tap that lands before the restore wins over it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRestoreTest {

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

    private class RestoreRepository(
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
        stateStore: InMemoryPlaybackStateStore,
    ) = SearchViewModel(
        RestoreRepository(listOf(trackA, trackB)),
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        playbackStateStore = stateStore,
    )

    private fun savedState() = SavedPlayback(
        queue = listOf(trackA, trackB),
        currentKey = trackB.key,
        positionMs = 45_000L,
    )

    @Test
    fun `restore shows the saved track paused at the saved position without playing`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(engine, InMemoryPlaybackStateStore(savedState()))
            advanceUntilIdle()
            assertEquals("B", vm.currentTrack.value?.title)
            assertTrue(vm.playerState.value is PlayerUiState.Paused)
            assertFalse(vm.isPlaying.value)
            assertEquals(45_000L, vm.playbackProgress.value.positionMs)
            assertEquals(0, engine.playCalls.size)
        }

    @Test
    fun `restore with a part key reconstructs the part track at the saved position`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val videoRow = Track(
                id = "BV1multi",
                cid = "",
                title = "Multi",
                artist = "A",
                cover = "",
                duration = 600.seconds,
            )
            val store = InMemoryPlaybackStateStore(
                SavedPlayback(
                    queue = listOf(videoRow, trackA),
                    currentKey = "BV1multi:cid-part-2",
                    positionMs = 30_000L,
                ),
            )
            val vm = viewModel(engine, store)
            advanceUntilIdle()
            assertEquals("Multi", vm.currentTrack.value?.title)
            assertEquals("cid-part-2", vm.currentTrack.value?.cid)
            assertTrue(vm.playerState.value is PlayerUiState.Paused)
            assertFalse(vm.isPlaying.value)
            assertEquals(30_000L, vm.playbackProgress.value.positionMs)
            assertEquals(0, engine.playCalls.size)
            assertEquals(listOf(videoRow, trackA), vm.queue.value)
        }

    @Test
    fun `restore with a key naming no video restores nothing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val store = InMemoryPlaybackStateStore(
            SavedPlayback(
                queue = listOf(trackA, trackB),
                currentKey = "BV1missing:cid-x",
                positionMs = 30_000L,
            ),
        )
        val vm = viewModel(engine, store)
        advanceUntilIdle()
        assertNull(vm.currentTrack.value)
        assertTrue(vm.playerState.value is PlayerUiState.Idle)
    }

    @Test
    fun `resuming restored playback seeks to the saved position`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, InMemoryPlaybackStateStore(savedState()))
        advanceUntilIdle()
        vm.togglePlayPause()
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        assertEquals(listOf(45_000L), engine.seekCalls)
        assertEquals("B", vm.currentTrack.value?.title)
    }

    @Test
    fun `playing saves the queue and track`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val store = InMemoryPlaybackStateStore()
        val vm = viewModel(engine, store)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        assertEquals(
            SavedPlayback(listOf(trackA, trackB), trackA.key, 0L),
            store.load(),
        )
    }

    @Test
    fun `pausing after a seek saves the position`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val store = InMemoryPlaybackStateStore()
        val vm = viewModel(engine, store)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        engine.emitPlaying()
        vm.seekTo(30_000L)
        vm.pausePlayback()
        advanceUntilIdle()
        assertEquals(30_000L, store.load()?.positionMs)
        assertEquals(trackA.key, store.load()?.currentKey)
    }

    @Test
    fun `a tap before the restore wins over it`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, InMemoryPlaybackStateStore(savedState()))
        vm.search("x")
        vm.play(trackA)
        advanceUntilIdle()
        assertEquals("A", vm.currentTrack.value?.title)
        assertEquals(1, engine.playCalls.size)
    }

    @Test
    fun `nothing saved means a fresh start`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, InMemoryPlaybackStateStore())
        advanceUntilIdle()
        assertNull(vm.currentTrack.value)
        assertTrue(vm.playerState.value is PlayerUiState.Idle)
    }
}
