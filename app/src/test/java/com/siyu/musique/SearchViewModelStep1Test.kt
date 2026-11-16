package com.siyu.musique

import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import com.siyu.musique.ui.PlayerUiState
import com.siyu.musique.ui.PlaybackFailure
import com.siyu.musique.ui.SearchErrorKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelStep1Test {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class ThrowingRepository(private val error: Throwable) : SearchRepository {
        var searchCalls = 0
        override suspend fun search(query: String): List<Track> {
            searchCalls++
            throw error
        }

        override suspend fun fetchDetail(track: Track): DetailedTrack =
            DetailedTrack(track, listOf(track))
    }

    private class DetailFillingRepository(
        private val delegate: SearchRepository = FakeSearchRepository(),
    ) : SearchRepository by delegate {
        var detailCalls = 0
        override suspend fun fetchDetail(track: Track): DetailedTrack {
            detailCalls++
            val filled = track.copy(cid = "999", title = "Detailed Title")
            return DetailedTrack(filled, listOf(filled))
        }
    }

    @Test
    fun `initial state is Idle, no automatic search`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.searchState.value is SearchUiState.Idle)
        assertEquals(0, repository.searchCalls)
    }

    @Test
    fun `blank search returns to Idle without calling the repository`() =
        runTest(testDispatcher) {
            val repository = FakeSearchRepository()
            val viewModel = SearchViewModel(repository)
            viewModel.search("   ")
            advanceUntilIdle()
            assertTrue(viewModel.searchState.value is SearchUiState.Idle)
            assertEquals(0, repository.searchCalls)
        }

    @Test
    fun `network failure maps to the NETWORK error state`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(
            ThrowingRepository(BiliNetworkException("no network")),
        )
        viewModel.search("test")
        advanceUntilIdle()
        val state = viewModel.searchState.value as SearchUiState.Error
        assertEquals(SearchErrorKind.NETWORK, state.kind)
        assertNull(state.apiCode)
        assertEquals("test", state.query)
    }

    @Test
    fun `minus 352 maps to the BLOCKED error state`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(
            ThrowingRepository(BiliApiException(-352, "risk")),
        )
        viewModel.search("test")
        advanceUntilIdle()
        val state = viewModel.searchState.value as SearchUiState.Error
        assertEquals(SearchErrorKind.BLOCKED, state.kind)
        assertNull(state.apiCode)
    }

    @Test
    fun `minus 799 maps to the RATE_LIMITED error state`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(
            ThrowingRepository(BiliApiException(-799, "too frequent")),
        )
        viewModel.search("test")
        advanceUntilIdle()
        val state = viewModel.searchState.value as SearchUiState.Error
        assertEquals(SearchErrorKind.RATE_LIMITED, state.kind)
    }

    @Test
    fun `parse failure maps to the PARSE error state`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(
            ThrowingRepository(BiliParseException("bad json")),
        )
        viewModel.search("test")
        advanceUntilIdle()
        val state = viewModel.searchState.value as SearchUiState.Error
        assertEquals(SearchErrorKind.PARSE, state.kind)
    }

    @Test
    fun `other API codes map to the API error state with the code`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(
            ThrowingRepository(BiliApiException(-101, "not logged in")),
        )
        viewModel.search("test")
        advanceUntilIdle()
        val state = viewModel.searchState.value as SearchUiState.Error
        assertEquals(SearchErrorKind.API, state.kind)
        assertEquals(-101, state.apiCode)
    }

    @Test
    fun `retry re-runs the last query`() = runTest(testDispatcher) {
        val repository = ThrowingRepository(BiliNetworkException("no network"))
        val viewModel = SearchViewModel(repository)
        viewModel.search("hello")
        advanceUntilIdle()
        assertEquals(1, repository.searchCalls)
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(2, repository.searchCalls)
    }

    @Test
    fun `retry before any search does nothing`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        val viewModel = SearchViewModel(repository)
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(0, repository.searchCalls)
        assertTrue(viewModel.searchState.value is SearchUiState.Idle)
    }

    @Test
    fun `play fills an empty cid from details and leaves the list row unchanged`() = runTest(testDispatcher) {
        val repository = DetailFillingRepository()
        val viewModel = SearchViewModel(repository)
        viewModel.search("夜曲")
        advanceUntilIdle()
        val track = (viewModel.searchState.value as SearchUiState.Success).tracks[0]
        val noCid = track.copy(cid = "")
        viewModel.play(noCid)
        advanceUntilIdle()
        assertEquals("999", viewModel.currentTrack.value?.cid)
        assertEquals("Detailed Title", viewModel.currentTrack.value?.title)
        // The list row keeps the search result as-is: the
        // detailed form belongs to the playing track only, so a
        // collection row never turns into its first song.
        val kept = (viewModel.searchState.value as SearchUiState.Success).tracks[0]
        assertEquals(track, kept)
        assertEquals(1, repository.detailCalls)
    }

    @Test
    fun `play with a filled cid resolves without blocking on details, then learns parts in the background`() =
        runTest(testDispatcher) {
        val repository = DetailFillingRepository()
        val viewModel = SearchViewModel(repository)
        val track = Track(
            id = "BV1q4FTeBEMj",
            cid = "28139389900",
            title = "t",
            artist = "a",
            cover = "",
            duration = 228.seconds,
        )
        viewModel.play(track)
        advanceUntilIdle()
        // The resolve itself never waits on details; exactly one
        // background call learns the video's parts afterwards.
        assertEquals(1, repository.detailCalls)
        // Step 3 fills playUrl on the current track when the URL
        // resolves; everything else about the track is unchanged.
        assertEquals(track, viewModel.currentTrack.value?.copy(playUrl = ""))
    }

    @Test
    fun `play surfaces a player error when the detail call fails (Step 3 contract change)`() =
        runTest(testDispatcher) {
            // Step 1 kept the tapped track and pretended to play: the
            // detail failure was best-effort and silent. Step 3
            // corrects that: playback needs the cid, so a detail
            // failure is a player error with a visible message and
            // Retry, and nothing pretends to play.
            val repository = object : SearchRepository by FakeSearchRepository() {
                override suspend fun fetchDetail(track: Track): DetailedTrack {
                    throw BiliNetworkException("no network")
                }
            }
            val viewModel = SearchViewModel(repository)
            val track = Track(
                id = "BV1q4FTeBEMj",
                cid = "",
                title = "t",
                artist = "a",
                cover = "",
                duration = 228.seconds,
            )
            viewModel.play(track)
            advanceUntilIdle()
            assertEquals(track, viewModel.currentTrack.value)
            assertFalse(viewModel.isPlaying.value)
            val error = viewModel.playerState.value as PlayerUiState.Error
            assertEquals(
                PlaybackFailure.Resolve(SearchErrorKind.NETWORK),
                error.failure,
            )
        }
}
