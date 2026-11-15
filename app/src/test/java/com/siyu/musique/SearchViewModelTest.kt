package com.siyu.musique

import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.ui.SearchUiState
import com.siyu.musique.ui.SearchViewModel
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
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle, no automatic search (Step 1 contract change)`() =
        runTest(testDispatcher) {
            // Step 0 auto-searched "" on init and the fake returned all
            // 5 tracks. Step 1: blank sends no request, so the
            // ViewModel starts Idle and waits for a real query.
            val viewModel = SearchViewModel(FakeSearchRepository())
            advanceUntilIdle()
            assertTrue(viewModel.searchState.value is SearchUiState.Idle)
        }

    @Test
    fun `search filters to the matching track`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(FakeSearchRepository())
        advanceUntilIdle()
        viewModel.search("夜曲")
        advanceUntilIdle()
        val state = viewModel.searchState.value as SearchUiState.Success
        assertEquals(1, state.tracks.size)
        assertEquals("BV1q4FTeBEMj", state.tracks[0].id)
    }

    @Test
    fun `search with no matches reports Empty, not an error`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(FakeSearchRepository())
        advanceUntilIdle()
        viewModel.search("no such song at all")
        advanceUntilIdle()
        assertTrue(viewModel.searchState.value is SearchUiState.Empty)
    }

    @Test
    fun `play sets the current track and playing state`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(FakeSearchRepository())
        advanceUntilIdle()
        val track = FakeSearchRepository.FAKE_TRACKS[0]
        viewModel.play(track)
        assertEquals(track, viewModel.currentTrack.value)
        assertTrue(viewModel.isPlaying.value)
    }

    @Test
    fun `toggle pauses and resumes`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(FakeSearchRepository())
        advanceUntilIdle()
        viewModel.play(FakeSearchRepository.FAKE_TRACKS[0])
        viewModel.togglePlayPause()
        assertFalse(viewModel.isPlaying.value)
        viewModel.togglePlayPause()
        assertTrue(viewModel.isPlaying.value)
    }

    @Test
    fun `toggle with nothing playing does nothing`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(FakeSearchRepository())
        advanceUntilIdle()
        viewModel.togglePlayPause()
        assertFalse(viewModel.isPlaying.value)
    }

    @Test
    fun `next and previous move inside the queue and stop at the ends`() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(FakeSearchRepository())
        // Step 1: no auto-search on init, so search first. "Demo"
        // matches the four demo tracks, which form the queue here.
        viewModel.search("Demo")
        advanceUntilIdle()
        val state = viewModel.searchState.value as SearchUiState.Success
        val tracks = state.tracks
        assertEquals(4, tracks.size)
        viewModel.play(tracks[0])
        viewModel.playNext()
        assertEquals(tracks[1], viewModel.currentTrack.value)
        viewModel.playPrevious()
        assertEquals(tracks[0], viewModel.currentTrack.value)
        // Previous at the first track stays put.
        viewModel.playPrevious()
        assertEquals(tracks[0], viewModel.currentTrack.value)
        // Next at the last track stays put.
        viewModel.play(tracks.last())
        viewModel.playNext()
        assertEquals(tracks.last(), viewModel.currentTrack.value)
    }
}
