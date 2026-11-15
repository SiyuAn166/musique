package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.HttpGateway
import com.siyu.musique.core.network.HttpRequest
import com.siyu.musique.core.network.HttpResponse
import com.siyu.musique.core.network.InMemoryBootstrapStore
import com.siyu.musique.core.network.WbiKeyStore
import com.siyu.musique.data.BiliSearchRepository
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.SearchPageResult
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.SearchUiState
import com.siyu.musique.ui.SearchViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

/**
 * Search pagination: the page parameter, hasMore, append + dedupe,
 * reset on a new search, and every loadMore no-op, at the ApiService,
 * repository, and ViewModel layers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPaginationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    companion object {
        private fun videoItem(bvid: String): String =
            """{"author":"artist-$bvid","bvid":"$bvid","title":"song $bvid","pic":"//i0.hdslb.com/x.jpg","duration":"3:00"}"""

        private fun searchData(page: Int, numPages: Int, vararg bvids: String): String {
            val items = bvids.joinToString(",") { videoItem(it) }
            return """{"page":$page,"pagesize":20,"numResults":1000,"numPages":$numPages,"result":[{"result_type":"video","data":[$items]}]}"""
        }

        private fun track(id: String) = Track(
            id = id,
            cid = "1",
            title = "title $id",
            artist = "artist",
            cover = "",
            duration = 60.seconds,
        )

        private val PAGES = listOf(
            listOf(track("BV1AAAAAAA01"), track("BV1BBBBBBB02")),
            listOf(track("BV1BBBBBBB02"), track("BV1CCCCCCC03")),
            listOf(track("BV1DDDDDDD04")),
        )
    }

    private fun service(gateway: FakeGateway): ApiService = ApiService(
        BiliClient(
            gateway = gateway,
            cookieJar = CookieJar(),
            wbiKeys = WbiKeyStore(
                fetcher = {
                    WbiKeyStore.WbiKeys(WbiSignerTest.IMG_KEY, WbiSignerTest.SUB_KEY)
                },
            ),
            sleeper = {},
            jitter = { 0.5 },
            nowSeconds = { 1794710448L },
        ),
    )

    /** Answers bootstrap calls plus a 3-page search: page 2 repeats one bvid from page 1. */
    private val pageHandler: (HttpRequest) -> HttpResponse = { request ->
        when {
            request.url.contains("finger/spi") ->
                envelopeResponse(0, BiliBootstrapTest.FINGER_DATA)
            request.url.contains("GenWebTicket") ->
                envelopeResponse(0, BiliBootstrapTest.TICKET_DATA)
            request.url == "https://www.bilibili.com/" ->
                plainResponse(200, "<html/>")
            request.url.contains("search/all/v2") -> {
                val page = Regex("page=(\\d+)").find(request.url)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                when (page) {
                    1 -> envelopeResponse(
                        0,
                        searchData(1, 3, "BV1AAAAAAA01", "BV1BBBBBBB02"),
                    )
                    2 -> envelopeResponse(
                        0,
                        searchData(2, 3, "BV1BBBBBBB02", "BV1CCCCCCC03"),
                    )
                    else -> envelopeResponse(0, searchData(3, 3, "BV1DDDDDDD04"))
                }
            }
            request.url.contains("wbi/view") ->
                envelopeResponse(0, ApiServiceTest.VIEW_DATA)
            request.url.contains("pagelist") ->
                envelopeResponse(0, ApiServiceTest.PAGELIST_DATA)
            request.url.contains("/nav") ->
                envelopeResponse(-101, ApiServiceTest.NAV_DATA)
            else -> error("unexpected ${request.url}")
        }
    }

    private class Stack(val gateway: HttpGateway) {
        val keys = WbiKeyStore()
        val jar = CookieJar()
        val client = BiliClient(
            gateway = gateway,
            cookieJar = jar,
            wbiKeys = keys,
            sleeper = {},
            jitter = { 0.5 },
            nowSeconds = { 1794710448L },
        )
        val api = ApiService(client)
        val bootstrap = BiliBootstrap(
            client = client,
            cookieJar = jar,
            store = InMemoryBootstrapStore(),
            wbiKeys = keys,
            generateIdentity = {
                BiliBootstrap.DeviceIdentity("UUID-TEST", "LSID-TEST", "1794710392")
            },
            nowSeconds = { 1794710392L },
            nowMillis = { 1794710392000L },
        )

        init {
            keys.fetcher = { api.fetchNavKeys() }
        }
    }

    /** Delegates to [delegate], suspending on [gate] for requests matching [gateOn]. */
    private class GatedGateway(
        private val gate: CompletableDeferred<Unit>,
        private val delegate: FakeGateway,
        private val gateOn: (HttpRequest) -> Boolean,
    ) : HttpGateway {
        var gatedRequests: Int = 0
            private set

        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (gateOn(request)) {
                gatedRequests++
                gate.await()
            }
            return delegate.execute(request)
        }
    }

    /** A repository serving scripted pages, with a gate and a failure switch for loadMore. */
    private class PagedFakeRepository(
        private val pages: List<List<Track>>,
    ) : SearchRepository {
        var searchCalls: Int = 0
            private set
        var loadMoreCalls: Int = 0
            private set
        var loadMoreGate: CompletableDeferred<Unit>? = null
        var failLoadMoreWith: Throwable? = null
        private var pageIndex: Int = 0
        private val loaded = mutableListOf<Track>()

        override val hasMorePages: Boolean
            get() = pageIndex in 1 until pages.size

        override suspend fun search(query: String): List<Track> {
            searchCalls++
            pageIndex = 1
            loaded.clear()
            loaded.addAll(pages[0])
            return loaded.toList()
        }

        override suspend fun fetchDetail(track: Track): Track = track

        override suspend fun loadMore(): SearchPageResult {
            loadMoreCalls++
            failLoadMoreWith?.let { throw it }
            loadMoreGate?.await()
            if (pageIndex >= pages.size) {
                return SearchPageResult(loaded.toList(), pageIndex, hasMore = false)
            }
            val page = pages[pageIndex]
            pageIndex++
            val seen = loaded.mapTo(mutableSetOf()) { it.id }
            loaded.addAll(page.filter { seen.add(it.id) })
            return SearchPageResult(loaded.toList(), pageIndex, hasMorePages)
        }
    }

    // ApiService: the page parameter and hasMore.

    @Test
    fun `search sends the requested page parameter, unsigned`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, searchData(2, 3, "BV1AAAAAAA01")))
        val page = service(gateway).search("kw", page = 2)
        val url = gateway.requests.single().url
        assertTrue(url.contains("page=2"))
        assertTrue(!url.contains("w_rid"))
        assertEquals(1, page.tracks.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `hasMore is false on the last page`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, searchData(3, 3, "BV1AAAAAAA01")))
        val page = service(gateway).search("kw", page = 3)
        assertFalse(page.hasMore)
    }

    @Test
    fun `missing numPages means no more pages`() = runTest {
        val gateway = FakeGateway()
        val data = """{"page":1,"result":[{"result_type":"video","data":[${videoItem("BV1AAAAAAA01")}]}]}"""
        gateway.enqueue(envelopeResponse(0, data))
        val page = service(gateway).search("kw")
        assertFalse(page.hasMore)
    }

    @Test
    fun `missing page falls back to the requested page`() = runTest {
        val gateway = FakeGateway()
        val data = """{"numPages":3,"result":[{"result_type":"video","data":[${videoItem("BV1AAAAAAA01")}]}]}"""
        gateway.enqueue(envelopeResponse(0, data))
        gateway.enqueue(envelopeResponse(0, data))
        assertTrue(service(gateway).search("kw", page = 1).hasMore)
        assertFalse(service(gateway).search("kw", page = 3).hasMore)
    }

    // Repository: append, dedupe, reset, no-ops.

    @Test
    fun `loadMore appends the next page and dedupes by bvid`() = runTest {
        val stack = Stack(FakeGateway().apply { handler = pageHandler })
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        val first = repository.search("kw")
        assertEquals(listOf("BV1AAAAAAA01", "BV1BBBBBBB02"), first.map { it.id })
        assertTrue(repository.hasMorePages)
        val second = repository.loadMore()
        assertEquals(
            listOf("BV1AAAAAAA01", "BV1BBBBBBB02", "BV1CCCCCCC03"),
            second.tracks.map { it.id },
        )
        assertEquals(2, second.page)
        assertTrue(second.hasMore)
        assertTrue(repository.hasMorePages)
        val third = repository.loadMore()
        assertEquals(4, third.tracks.size)
        assertEquals(3, third.page)
        assertFalse(third.hasMore)
        assertFalse(repository.hasMorePages)
    }

    @Test
    fun `page loads are logged with query page count and hasMore`() = runTest {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val stack = Stack(FakeGateway().apply { handler = pageHandler })
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope, logger)
        advanceUntilIdle()
        repository.search("kw")
        repository.loadMore()
        val messages = logger.buffer.lines().map { it.message }
        assertTrue(
            messages.any { it == "search page query=\"kw\" page=2 count=2 hasMore=true" },
        )
    }

    @Test
    fun `a new search resets to page 1`() = runTest {
        val fake = FakeGateway().apply { handler = pageHandler }
        val stack = Stack(fake)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        repository.search("kw")
        repository.loadMore()
        val again = repository.search("kw")
        assertEquals(listOf("BV1AAAAAAA01", "BV1BBBBBBB02"), again.map { it.id })
        assertTrue(repository.hasMorePages)
        val more = repository.loadMore()
        assertEquals(3, more.tracks.size)
        assertEquals(2, more.page)
        // page=2 was requested twice: once before the reset, once after.
        assertEquals(2, fake.requests.count { it.url.contains("page=2") })
    }

    @Test
    fun `loadMore with no search is a no-op`() = runTest {
        val fake = FakeGateway().apply { handler = pageHandler }
        val stack = Stack(fake)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        val result = repository.loadMore()
        assertTrue(result.tracks.isEmpty())
        assertEquals(0, result.page)
        assertFalse(result.hasMore)
        assertTrue(fake.requests.none { it.url.contains("search/all/v2") })
    }

    @Test
    fun `loadMore after the last page is a no-op`() = runTest {
        val fake = FakeGateway().apply { handler = pageHandler }
        val stack = Stack(fake)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        repository.search("kw")
        repository.loadMore()
        val last = repository.loadMore()
        assertFalse(last.hasMore)
        val countBefore = fake.requests.count { it.url.contains("search/all/v2") }
        val noop = repository.loadMore()
        assertEquals(last.tracks, noop.tracks)
        assertEquals(3, noop.page)
        assertEquals(countBefore, fake.requests.count { it.url.contains("search/all/v2") })
    }

    @Test
    fun `loadMore while a load is in flight is a no-op`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeGateway().apply { handler = pageHandler }
        val gateway = GatedGateway(gate, fake) { it.url.contains("page=2") }
        val stack = Stack(gateway)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        repository.search("kw")
        val pending = async { repository.loadMore() }
        advanceUntilIdle()
        assertEquals(1, gateway.gatedRequests)
        val noop = repository.loadMore()
        assertEquals(listOf("BV1AAAAAAA01", "BV1BBBBBBB02"), noop.tracks.map { it.id })
        assertEquals(1, noop.page)
        assertTrue(noop.hasMore)
        assertEquals(1, gateway.gatedRequests)
        gate.complete(Unit)
        val loaded = pending.await()
        assertEquals(
            listOf("BV1AAAAAAA01", "BV1BBBBBBB02", "BV1CCCCCCC03"),
            loaded.tracks.map { it.id },
        )
        assertEquals(2, loaded.page)
    }

    @Test
    fun `loadMore after a BV lookup is a no-op`() = runTest {
        val fake = FakeGateway().apply { handler = pageHandler }
        val stack = Stack(fake)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        val tracks = repository.search("BV1q4FTeBEMj")
        assertEquals(1, tracks.size)
        assertFalse(repository.hasMorePages)
        val countBefore = fake.requests.count { it.url.contains("search/all/v2") }
        val noop = repository.loadMore()
        assertEquals(tracks, noop.tracks)
        assertEquals(countBefore, fake.requests.count { it.url.contains("search/all/v2") })
    }

    // ViewModel: Success state, loading row state, logging, failure.

    @Test
    fun `Success carries hasMore from the repository after search`() =
        runTest(testDispatcher) {
            val vm = SearchViewModel(PagedFakeRepository(PAGES), FakePlayerEngine())
            vm.search("kw")
            advanceUntilIdle()
            val state = vm.searchState.value as SearchUiState.Success
            assertEquals(2, state.tracks.size)
            assertTrue(state.hasMore)
            assertFalse(state.loadingMore)
        }

    @Test
    fun `loadMore appends pages and clears hasMore on the last page`() =
        runTest(testDispatcher) {
            val vm = SearchViewModel(PagedFakeRepository(PAGES), FakePlayerEngine())
            vm.search("kw")
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            var state = vm.searchState.value as SearchUiState.Success
            assertEquals(
                listOf("BV1AAAAAAA01", "BV1BBBBBBB02", "BV1CCCCCCC03"),
                state.tracks.map { it.id },
            )
            assertTrue(state.hasMore)
            assertFalse(state.loadingMore)
            vm.loadMore()
            advanceUntilIdle()
            state = vm.searchState.value as SearchUiState.Success
            assertEquals(4, state.tracks.size)
            assertFalse(state.hasMore)
        }

    @Test
    fun `loadMore is a no-op without a Success state or more pages`() =
        runTest(testDispatcher) {
            val repository = PagedFakeRepository(PAGES)
            val vm = SearchViewModel(repository, FakePlayerEngine())
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(0, repository.loadMoreCalls)
            // A repository that never pages reports hasMore false.
            val vm2 = SearchViewModel(FakeSearchRepository(), FakePlayerEngine())
            vm2.search("Demo")
            advanceUntilIdle()
            val state = vm2.searchState.value as SearchUiState.Success
            assertFalse(state.hasMore)
            vm2.loadMore()
            advanceUntilIdle()
            assertEquals(state, vm2.searchState.value)
        }

    @Test
    fun `loadMore shows the loading state and ignores a second call while in flight`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repository = PagedFakeRepository(PAGES)
            repository.loadMoreGate = gate
            val vm = SearchViewModel(repository, FakePlayerEngine())
            vm.search("kw")
            advanceUntilIdle()
            vm.loadMore()
            var state = vm.searchState.value as SearchUiState.Success
            assertTrue(state.loadingMore)
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(1, repository.loadMoreCalls)
            gate.complete(Unit)
            advanceUntilIdle()
            state = vm.searchState.value as SearchUiState.Success
            assertFalse(state.loadingMore)
            assertEquals(3, state.tracks.size)
        }

    @Test
    fun `a new search cancels the pending load and resets the list`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repository = PagedFakeRepository(PAGES)
            repository.loadMoreGate = gate
            val vm = SearchViewModel(repository, FakePlayerEngine())
            vm.search("kw")
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            vm.search("kw")
            advanceUntilIdle()
            var state = vm.searchState.value as SearchUiState.Success
            assertEquals(2, state.tracks.size)
            assertFalse(state.loadingMore)
            gate.complete(Unit)
            advanceUntilIdle()
            // The stale page never lands on the new search.
            state = vm.searchState.value as SearchUiState.Success
            assertEquals(2, state.tracks.size)
        }

    @Test
    fun `retry re-runs page 1 after pages were loaded`() = runTest(testDispatcher) {
        val repository = PagedFakeRepository(PAGES)
        val vm = SearchViewModel(repository, FakePlayerEngine())
        vm.search("kw")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(3, (vm.searchState.value as SearchUiState.Success).tracks.size)
        vm.retry()
        advanceUntilIdle()
        val state = vm.searchState.value as SearchUiState.Success
        assertEquals(2, state.tracks.size)
        assertTrue(state.hasMore)
        assertEquals(2, repository.searchCalls)
    }

    @Test
    fun `loadMore logs the page load with query page count and hasMore`() =
        runTest(testDispatcher) {
            val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
            val vm = SearchViewModel(PagedFakeRepository(PAGES), FakePlayerEngine(), logger)
            vm.search("kw")
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            val messages = logger.buffer.lines().map { it.message }
            assertTrue(
                messages.any {
                    it == "search page loaded query=\"kw\" page=2 count=3 hasMore=true"
                },
            )
        }

    @Test
    fun `loadMore failure keeps the list and clears the loading row`() =
        runTest(testDispatcher) {
            val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
            val repository = PagedFakeRepository(PAGES)
            val vm = SearchViewModel(repository, FakePlayerEngine(), logger)
            vm.search("kw")
            advanceUntilIdle()
            repository.failLoadMoreWith = BiliNetworkException("no network")
            vm.loadMore()
            advanceUntilIdle()
            val state = vm.searchState.value as SearchUiState.Success
            assertEquals(2, state.tracks.size)
            assertTrue(state.hasMore)
            assertFalse(state.loadingMore)
            val messages = logger.buffer.lines().map { it.message }
            assertTrue(messages.any { it.contains("search page failed") })
            // A retry works once the failure clears.
            repository.failLoadMoreWith = null
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(3, (vm.searchState.value as SearchUiState.Success).tracks.size)
        }
}
