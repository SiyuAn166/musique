package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
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

/** Volume control: full by default, clamped to [0, 1], applied to the engine, kept across plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class VolumeControlTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class StubRepository : SearchRepository {
        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): Track = track

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream =
            AudioStream(30280, "https://cdn.example/q-${track.id}.m4s", 216670L)
    }

    private fun viewModel(engine: FakePlayerEngine) = SearchViewModel(
        StubRepository(),
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
    )

    @Test
    fun `volume is full by default`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        assertEquals(1f, vm.volume.value)
        assertEquals(1f, engine.volume)
        assertEquals(emptyList<Float>(), engine.volumeCalls)
    }

    @Test
    fun `setVolume applies to the engine and the state`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.setVolume(0.5f)
        assertEquals(0.5f, vm.volume.value)
        assertEquals(0.5f, engine.volume)
        assertEquals(listOf(0.5f), engine.volumeCalls)
    }

    @Test
    fun `setVolume clamps to 0 and 1`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.setVolume(2f)
        assertEquals(1f, vm.volume.value)
        assertEquals(1f, engine.volume)
        vm.setVolume(-1f)
        assertEquals(0f, vm.volume.value)
        assertEquals(0f, engine.volume)
    }

    @Test
    fun `volume is not reset by playing a new track`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine)
        vm.setVolume(0.5f)
        vm.play(
            Track(
                id = "BV1q4FTeBEMj",
                cid = "28139389900",
                title = "t",
                artist = "a",
                cover = "",
                duration = 228.seconds,
            ),
        )
        advanceUntilIdle()
        assertEquals(0.5f, vm.volume.value)
        assertEquals(0.5f, engine.volume)
        assertEquals(listOf(0.5f), engine.volumeCalls)
    }
}
