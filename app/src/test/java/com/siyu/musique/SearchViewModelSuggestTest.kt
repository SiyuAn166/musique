package com.siyu.musique

import com.siyu.musique.data.FakeSearchRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Search suggestions: debounced per keystroke, cleared on search
 * and on blank, and never disturbed by a failed or stale fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelSuggestTest {

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
    fun `suggestions appear after the debounce, not before`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        repository.suggestionsFor = { listOf("周杰伦歌曲全集") }
        val vm = SearchViewModel(repository)
        advanceUntilIdle()
        vm.onQueryChanged("周杰")
        testScheduler.advanceTimeBy(SearchViewModel.SUGGEST_DEBOUNCE_MS - 1)
        testScheduler.runCurrent()
        assertTrue(vm.suggestions.value.isEmpty())
        assertTrue(repository.suggestionCalls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("周杰伦歌曲全集"), vm.suggestions.value)
    }

    @Test
    fun `blank query sends no request and clears suggestions`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        repository.suggestionsFor = { listOf("周杰伦歌曲全集") }
        val vm = SearchViewModel(repository)
        advanceUntilIdle()
        vm.onQueryChanged("周杰")
        advanceUntilIdle()
        assertEquals(listOf("周杰伦歌曲全集"), vm.suggestions.value)
        vm.onQueryChanged("   ")
        advanceUntilIdle()
        assertTrue(vm.suggestions.value.isEmpty())
        assertEquals(listOf("周杰"), repository.suggestionCalls)
        vm.clearSuggestions()
        assertTrue(vm.suggestions.value.isEmpty())
    }

    @Test
    fun `rapid typing fetches only the latest text`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        repository.suggestionsFor = { query -> listOf("$query-1") }
        val vm = SearchViewModel(repository)
        advanceUntilIdle()
        vm.onQueryChanged("周")
        testScheduler.advanceTimeBy(100)
        vm.onQueryChanged("周杰")
        testScheduler.advanceTimeBy(100)
        vm.onQueryChanged("周杰伦")
        advanceUntilIdle()
        assertEquals(listOf("周杰伦"), repository.suggestionCalls)
        assertEquals(listOf("周杰伦-1"), vm.suggestions.value)
    }

    @Test
    fun `search clears suggestions and drops a pending fetch`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        repository.suggestionsFor = { listOf("周杰伦歌曲全集") }
        val vm = SearchViewModel(repository)
        advanceUntilIdle()
        vm.onQueryChanged("周杰")
        advanceUntilIdle()
        assertEquals(listOf("周杰伦歌曲全集"), vm.suggestions.value)
        vm.onQueryChanged("周杰伦")
        testScheduler.advanceTimeBy(100)
        vm.search("周杰伦")
        advanceUntilIdle()
        assertTrue(vm.suggestions.value.isEmpty())
        assertEquals(listOf("周杰"), repository.suggestionCalls)
    }

    @Test
    fun `a failed fetch leaves suggestions empty`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        repository.suggestionsFor = { throw RuntimeException("suggest down") }
        val vm = SearchViewModel(repository)
        advanceUntilIdle()
        vm.onQueryChanged("周杰")
        advanceUntilIdle()
        assertTrue(vm.suggestions.value.isEmpty())
    }

    @Test
    fun `a stale fetch result is dropped when a newer query wins`() = runTest(testDispatcher) {
        val repository = FakeSearchRepository()
        repository.suggestionsDelayMs = 1000L
        repository.suggestionsFor = { query -> listOf("$query-1") }
        val vm = SearchViewModel(repository)
        advanceUntilIdle()
        vm.onQueryChanged("a")
        testScheduler.advanceTimeBy(SearchViewModel.SUGGEST_DEBOUNCE_MS)
        testScheduler.runCurrent()
        vm.onQueryChanged("ab")
        advanceUntilIdle()
        assertEquals(listOf("a", "ab"), repository.suggestionCalls)
        assertEquals(listOf("ab-1"), vm.suggestions.value)
    }
}
