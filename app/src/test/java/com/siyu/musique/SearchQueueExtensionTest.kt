package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchPageResult
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
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
import org.junit.Before
import org.junit.Test

/**
 * A queue adopted from search results keeps growing as more
 * search pages load, instead of freezing at the page that was
 * loaded when the user tapped play. Playlist and restored
 * queues never grow, and neither does a queue whose search has
 * moved on to another query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchQueueExtensionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A repository serving scripted pages, deduped by bvid like the production one. */
    private class PagedRepository(
        private val pages: List<List<Track>>,
    ) : SearchRepository {
        private var pageIndex = 0
        private val loaded = mutableListOf<Track>()
        var loadMoreCalls: Int = 0
            private set

        override suspend fun search(query: String): List<Track> {
            pageIndex = 0
            loaded.clear()
            loaded.addAll(pages.getOrElse(0) { emptyList() })
            return loaded.toList()
        }

        override val hasMorePages: Boolean
            get() = pageIndex + 1 < pages.size

        override suspend fun loadMore(): SearchPageResult {
            loadMoreCalls++
            if (pageIndex + 1 >= pages.size) {
                return SearchPageResult(loaded.toList(), pageIndex, hasMore = false)
            }
            pageIndex++
            val fresh = pages[pageIndex].filter { page -> loaded.none { it.id == page.id } }
            loaded.addAll(fresh)
            return SearchPageResult(
                loaded.toList(),
                pageIndex,
                hasMore = pageIndex + 1 < pages.size,
            )
        }

        override suspend fun fetchDetail(track: Track): DetailedTrack =
            DetailedTrack(track, listOf(track))

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream =
            AudioStream(30280, "https://cdn.example/q-${track.id}.m4s", 216670L)
    }

    private fun track(id: String) = Track(
        id = id,
        cid = "cid-$id",
        title = "t-$id",
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
    fun `loadMore extends a search-adopted queue`() = runTest(testDispatcher) {
        val page1 = listOf("A", "B", "C", "D", "E").map(::track)
        val page2 = listOf("F", "G").map(::track)
        val repository = PagedRepository(listOf(page1, page2))
        val vm = viewModel(repository, FakePlayerEngine())
        vm.search("q")
        advanceUntilIdle()
        vm.play(page1[0])
        advanceUntilIdle()
        assertEquals(page1, vm.queue.value)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(page1 + page2, vm.queue.value)
        val state = vm.searchState.value as SearchUiState.Success
        assertEquals(page1 + page2, state.tracks)
    }

    @Test
    fun `playing near the end prefetches the next page, playing early does not`() =
        runTest(testDispatcher) {
            val page1 = listOf("A", "B", "C", "D", "E").map(::track)
            val page2 = listOf("F", "G").map(::track)
            val repository = PagedRepository(listOf(page1, page2))
            val vm = viewModel(repository, FakePlayerEngine())
            vm.search("q")
            advanceUntilIdle()

            vm.play(page1[0])
            advanceUntilIdle()
            assertEquals(0, repository.loadMoreCalls)
            assertEquals(page1, vm.queue.value)

            vm.play(page1[3])
            advanceUntilIdle()
            assertEquals(1, repository.loadMoreCalls)
            assertEquals(page1 + page2, vm.queue.value)
        }

    @Test
    fun `no prefetch when the search has no more pages`() = runTest(testDispatcher) {
        val page1 = listOf("A", "B").map(::track)
        val repository = PagedRepository(listOf(page1))
        val vm = viewModel(repository, FakePlayerEngine())
        vm.search("q")
        advanceUntilIdle()
        vm.play(page1[1])
        advanceUntilIdle()
        assertEquals(0, repository.loadMoreCalls)
        assertEquals(page1, vm.queue.value)
    }

    @Test
    fun `loadMore does not extend a playlist queue`() = runTest(testDispatcher) {
        val page1 = listOf("A", "B", "C", "D", "E").map(::track)
        val page2 = listOf("F", "G").map(::track)
        val repository = PagedRepository(listOf(page1, page2))
        val vm = viewModel(repository, FakePlayerEngine())
        vm.search("q")
        advanceUntilIdle()
        val playlistTracks = listOf("P1", "P2").map(::track)
        vm.playFromPlaylist(playlistTracks[0], Playlist("u1", "Mix", playlistTracks))
        advanceUntilIdle()
        assertEquals(playlistTracks, vm.queue.value)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(playlistTracks, vm.queue.value)
    }

    @Test
    fun `loadMore does not extend the queue after the query changed`() =
        runTest(testDispatcher) {
            val page1 = listOf("A", "B", "C", "D", "E").map(::track)
            val page2 = listOf("F", "G").map(::track)
            val repository = PagedRepository(listOf(page1, page2))
            val vm = viewModel(repository, FakePlayerEngine())
            vm.search("q1")
            advanceUntilIdle()
            vm.play(page1[0])
            advanceUntilIdle()
            assertEquals(page1, vm.queue.value)

            vm.search("q2")
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(page1, vm.queue.value)
        }

    @Test
    fun `re-running the same query does not duplicate queue tracks`() =
        runTest(testDispatcher) {
            val page1 = listOf("A", "B", "C", "D", "E").map(::track)
            val page2 = listOf("F", "G").map(::track)
            val repository = PagedRepository(listOf(page1, page2))
            val vm = viewModel(repository, FakePlayerEngine())
            vm.search("q")
            advanceUntilIdle()
            vm.play(page1[0])
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(page1 + page2, vm.queue.value)

            vm.search("q")
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(page1 + page2, vm.queue.value)
        }
}
