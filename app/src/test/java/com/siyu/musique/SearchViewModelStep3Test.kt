package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.player.PlaybackError
import com.siyu.musique.ui.PlayerUiState
import com.siyu.musique.ui.SearchUiState
import com.siyu.musique.ui.SearchViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelStep3Test {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A repository with scripted detail and play URL answers, plus a gate to stall resolution. */
    private class ScriptedRepository(
        private var detailAnswer: (Track) -> Track = { it.copy(cid = "999") },
        private val playUrlAnswers: ArrayDeque<Result<AudioStream>> = ArrayDeque(
            listOf(Result.success(AudioStream(30280, "https://cdn.example/a.m4s", 216670L))),
        ),
    ) : SearchRepository {
        var detailCalls: Int = 0
            private set
        var playUrlCalls: Int = 0
            private set
        val playUrlTracks = mutableListOf<Track>()

        /** When set, fetchPlayUrl suspends until the gate completes. */
        var playUrlGate: CompletableDeferred<Unit>? = null

        fun failDetailWith(error: Throwable) {
            detailAnswer = { throw error }
        }

        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): Track {
            detailCalls++
            return detailAnswer(track)
        }

        override suspend fun fetchPlayUrl(track: Track): AudioStream {
            playUrlCalls++
            playUrlTracks.add(track)
            playUrlGate?.await()
            return playUrlAnswers.removeFirst().getOrThrow()
        }
    }

    private fun track(id: String = "BV1q4FTeBEMj", cid: String = "28139389900") = Track(
        id = id,
        cid = cid,
        title = "t",
        artist = "a",
        cover = "",
        duration = 228.seconds,
    )

    private fun viewModel(
        repository: SearchRepository,
        engine: FakePlayerEngine,
        logger: MusiqueLogger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
    ) = SearchViewModel(repository, engine, logger)

    @Test
    fun `play resolves the URL and hands it to the engine with a bvid cid quality cache key`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(ScriptedRepository(), engine)
            vm.play(track())
            assertTrue(vm.playerState.value is PlayerUiState.Fetching)
            advanceUntilIdle()
            assertEquals(1, engine.playCalls.size)
            assertEquals("https://cdn.example/a.m4s", engine.playCalls[0].first)
            assertEquals("BV1q4FTeBEMj:28139389900:30280", engine.playCalls[0].second)
            assertEquals("https://cdn.example/a.m4s", vm.currentTrack.value?.playUrl)
        }

    @Test
    fun `play fills an empty cid from details before resolving the URL`() = runTest(testDispatcher) {
        val repository = ScriptedRepository()
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        vm.play(track(cid = ""))
        advanceUntilIdle()
        assertEquals(1, repository.detailCalls)
        assertEquals(1, repository.playUrlCalls)
        assertEquals("999", repository.playUrlTracks.single().cid)
        assertEquals("999", vm.currentTrack.value?.cid)
        assertEquals(1, engine.playCalls.size)
    }

    @Test
    fun `engine callbacks drive buffering playing and paused states`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(ScriptedRepository(), engine)
        vm.play(track())
        advanceUntilIdle()
        engine.emitBuffering()
        assertTrue(vm.playerState.value is PlayerUiState.Buffering)
        assertTrue(vm.isPlaying.value)
        engine.emitPlaying()
        assertTrue(vm.playerState.value is PlayerUiState.Playing)
        engine.emitPaused()
        assertTrue(vm.playerState.value is PlayerUiState.Paused)
        assertFalse(vm.isPlaying.value)
    }

    @Test
    fun `toggle pauses and resumes through the engine`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(ScriptedRepository(), engine)
        vm.play(track())
        advanceUntilIdle()
        engine.emitPlaying()
        vm.togglePlayPause()
        assertEquals(1, engine.pauseCalls)
        assertTrue(vm.playerState.value is PlayerUiState.Paused)
        vm.togglePlayPause()
        assertEquals(1, engine.resumeCalls)
        engine.emitPlaying()
        assertTrue(vm.playerState.value is PlayerUiState.Playing)
    }

    @Test
    fun `toggle with nothing playing does nothing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(ScriptedRepository(), engine)
        vm.togglePlayPause()
        assertEquals(0, engine.pauseCalls)
        assertEquals(0, engine.resumeCalls)
        assertTrue(vm.playerState.value is PlayerUiState.Idle)
    }

    @Test
    fun `detail failure surfaces a player error, and retry replays the track`() =
        runTest(testDispatcher) {
            val repository = ScriptedRepository()
            repository.failDetailWith(BiliNetworkException("no network"))
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.play(track(cid = ""))
            advanceUntilIdle()
            val error = vm.playerState.value as PlayerUiState.Error
            assertTrue(error.message.contains("Network problem"))
            assertFalse(vm.isPlaying.value)
            assertEquals(track(cid = ""), vm.currentTrack.value)
            assertTrue(engine.playCalls.isEmpty())
            assertEquals(0, repository.playUrlCalls)
        }

    @Test
    fun `retryPlayback replays the current track after an error`() = runTest(testDispatcher) {
        val repository = ScriptedRepository(
            playUrlAnswers = ArrayDeque(
                listOf(
                    Result.failure(BiliNetworkException("no network")),
                    Result.success(AudioStream(30280, "https://cdn.example/retry.m4s", 1L)),
                ),
            ),
        )
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        vm.play(track())
        advanceUntilIdle()
        assertTrue(vm.playerState.value is PlayerUiState.Error)
        vm.retryPlayback()
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        assertEquals("https://cdn.example/retry.m4s", engine.playCalls[0].first)
    }

    @Test
    fun `HTTP player error refetches the URL once, then errors on the second failure`() =
        runTest(testDispatcher) {
            val repository = ScriptedRepository(
                playUrlAnswers = ArrayDeque(
                    listOf(
                        Result.success(AudioStream(30280, "https://cdn.example/first.m4s", 1L)),
                        Result.success(AudioStream(30280, "https://cdn.example/second.m4s", 1L)),
                    ),
                ),
            )
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.play(track())
            advanceUntilIdle()
            assertEquals(1, repository.playUrlCalls)
            engine.emitError(PlaybackError("HTTP 403", isHttpFailure = true))
            advanceUntilIdle()
            assertEquals(2, repository.playUrlCalls)
            assertEquals(2, engine.playCalls.size)
            assertEquals("https://cdn.example/second.m4s", engine.playCalls[1].first)
            engine.emitError(PlaybackError("HTTP 403", isHttpFailure = true))
            advanceUntilIdle()
            assertEquals(2, repository.playUrlCalls)
            assertTrue(vm.playerState.value is PlayerUiState.Error)
        }

    @Test
    fun `non-HTTP player error stops in the error state without refetching`() =
        runTest(testDispatcher) {
            val repository = ScriptedRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(repository, engine)
            vm.play(track())
            advanceUntilIdle()
            engine.emitError(PlaybackError("Decoder failed", isHttpFailure = false))
            advanceUntilIdle()
            assertEquals(1, repository.playUrlCalls)
            val error = vm.playerState.value as PlayerUiState.Error
            assertTrue(error.message.contains("Decoder failed"))
            assertFalse(vm.isPlaying.value)
        }

    @Test
    fun `a stale resolve for an older tap is discarded`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = ScriptedRepository()
        repository.playUrlGate = gate
        val engine = FakePlayerEngine()
        val vm = viewModel(repository, engine)
        val first = track(id = "BV1AAAAAAA01")
        val second = track(id = "BV1BBBBBBB02")
        vm.play(first)
        advanceUntilIdle()
        // The first resolve is still gated; the second tap supersedes it.
        vm.play(second)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
        assertEquals(second, vm.currentTrack.value?.copy(playUrl = ""))
        assertEquals("BV1BBBBBBB02", vm.currentTrack.value?.id)
    }

    @Test
    fun `stale engine callbacks for an older intent are ignored`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        // Two taps, so two scripted URL answers: the second tap's
        // resolve must succeed for the stale callback to be stale.
        val repository = ScriptedRepository(
            playUrlAnswers = ArrayDeque(
                listOf(
                    Result.success(AudioStream(30280, "https://cdn.example/a.m4s", 1L)),
                    Result.success(AudioStream(30280, "https://cdn.example/b.m4s", 1L)),
                ),
            ),
        )
        val vm = viewModel(repository, engine)
        vm.play(track(id = "BV1AAAAAAA01"))
        advanceUntilIdle()
        // A new tap stops the engine; a late error from the old item
        // must not put the new item in the error state.
        vm.play(track(id = "BV1BBBBBBB02"))
        engine.emitError(PlaybackError("HTTP 403", isHttpFailure = true))
        advanceUntilIdle()
        assertFalse(vm.playerState.value is PlayerUiState.Error)
        assertEquals(2, engine.playCalls.size)
    }

    @Test
    fun `next and previous play through the engine inside the queue`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(FakeSearchRepository(), engine)
            vm.search("Demo")
            advanceUntilIdle()
            val tracks = (vm.searchState.value as SearchUiState.Success).tracks
            vm.play(tracks[0])
            advanceUntilIdle()
            assertEquals(1, engine.playCalls.size)
            vm.playNext()
            advanceUntilIdle()
            assertEquals(tracks[1].id, vm.currentTrack.value?.id)
            assertEquals(2, engine.playCalls.size)
            vm.playPrevious()
            advanceUntilIdle()
            assertEquals(tracks[0].id, vm.currentTrack.value?.id)
            assertEquals(3, engine.playCalls.size)
            // Next at the last track stays put and plays nothing new.
            vm.play(tracks.last())
            advanceUntilIdle()
            val callsBefore = engine.playCalls.size
            vm.playNext()
            advanceUntilIdle()
            assertEquals(callsBefore, engine.playCalls.size)
        }

    @Test
    fun `play logs the intent and the quality without the URL`() = runTest(testDispatcher) {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val engine = FakePlayerEngine()
        val vm = viewModel(ScriptedRepository(), engine, logger)
        vm.play(track())
        advanceUntilIdle()
        val messages = logger.buffer.lines().map { it.message }
        assertTrue(messages.any { it.contains("play intent=1 bvid=BV1q4FTeBEMj") })
        assertTrue(messages.any { it.contains("player play intent=1 quality=30280") })
        assertTrue(messages.none { it.contains("cdn.example") })
        // The ViewModel exposes the same lines to the log screen.
        assertEquals(messages.size, vm.logLines.value.size)
    }

    @Test
    fun `clearLogs drops the buffered lines`() = runTest(testDispatcher) {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val vm = viewModel(ScriptedRepository(), FakePlayerEngine(), logger)
        vm.play(track())
        advanceUntilIdle()
        assertTrue(vm.logLines.value.isNotEmpty())
        vm.clearLogs()
        assertTrue(vm.logLines.value.isEmpty())
    }
}
