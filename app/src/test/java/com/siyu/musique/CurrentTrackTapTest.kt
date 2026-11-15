package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Playlist
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tapping the track that is already loaded changes nothing: no
 * new resolve, no restart. Only switching songs restarts
 * playback. The error state is the exception: after a failure the
 * tap retries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentTrackTapTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(id: String, title: String, cid: String = "cid-$id") = Track(
        id = id,
        cid = cid,
        title = title,
        artist = "A",
        cover = "",
        duration = 200.seconds,
    )

    private val trackA = track("BV1a", "A")
    private val trackB = track("BV1b", "B")

    private class TapRepository(
        private val results: List<Track>,
    ) : SearchRepository {
        var fetchPlayUrlCalls = 0
        var failPlayUrl = false

        override suspend fun search(query: String): List<Track> = results

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            val detailed = track.copy(cid = "cid-${track.id}")
            return DetailedTrack(detailed, listOf(detailed))
        }

        override suspend fun fetchPlayUrl(
            track: Track,
            preference: AudioQuality,
        ): AudioStream {
            fetchPlayUrlCalls++
            if (failPlayUrl) throw BiliNetworkException("boom")
            return AudioStream(30280, "https://cdn.example/${track.id}.m4s", 216670L)
        }
    }

    private fun viewModel(
        repository: SearchRepository,
        engine: FakePlayerEngine,
    ) = SearchViewModel(
        repository,
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
    )

    @Test
    fun `tapping the playing track does nothing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val repository = TapRepository(listOf(trackA, trackB))
        val vm = viewModel(repository, engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        vm.play(trackA)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        assertEquals(1, repository.fetchPlayUrlCalls)
        assertEquals("A", vm.currentTrack.value?.title)
    }

    @Test
    fun `tapping another track switches`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(TapRepository(listOf(trackA, trackB)), engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        vm.play(trackB)
        advanceUntilIdle()
        assertEquals(2, engine.playCalls.size)
        assertEquals("B", vm.currentTrack.value?.title)
    }

    @Test
    fun `tapping the failed track retries`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val repository = TapRepository(listOf(trackA))
        repository.failPlayUrl = true
        val vm = viewModel(repository, engine)
        vm.search("x")
        advanceUntilIdle()
        vm.play(trackA)
        advanceUntilIdle()
        assertTrue(vm.playerState.value is PlayerUiState.Error)
        assertEquals(0, engine.playCalls.size)
        repository.failPlayUrl = false
        vm.play(trackA)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        assertTrue(vm.playerState.value !is PlayerUiState.Error)
    }

    @Test
    fun `tapping the row again while its detailed version plays does nothing`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val row = track("BV1a", "A", cid = "")
            val vm = viewModel(TapRepository(listOf(row)), engine)
            vm.search("x")
            advanceUntilIdle()
            vm.play(row)
            advanceUntilIdle()
            assertEquals("cid-BV1a", vm.currentTrack.value?.cid)
            assertEquals(1, engine.playCalls.size)
            vm.play(row)
            advanceUntilIdle()
            assertEquals(1, engine.playCalls.size)
        }

    @Test
    fun `tapping the playlist track again does nothing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(TapRepository(listOf(trackA)), engine)
        val playlist = Playlist.default().copy(tracks = listOf(trackA))
        vm.playFromPlaylist(trackA, playlist)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        vm.playFromPlaylist(trackA, playlist)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
    }
}
