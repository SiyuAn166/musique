package com.siyu.musique

import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.InMemoryBootstrapStore
import com.siyu.musique.core.network.WbiKeyStore
import com.siyu.musique.data.BiliSearchRepository
import com.siyu.musique.model.Track
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BiliSearchRepositoryTest {

    private class Stack(val gateway: FakeGateway) {
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
            gateway.handler = { request ->
                when {
                    request.url.contains("finger/spi") ->
                        envelopeResponse(0, BiliBootstrapTest.FINGER_DATA)
                    request.url.contains("GenWebTicket") ->
                        envelopeResponse(0, BiliBootstrapTest.TICKET_DATA)
                    request.url == "https://www.bilibili.com/" ->
                        plainResponse(200, "<html/>")
                    request.url.contains("search/all/v2") ->
                        envelopeResponse(0, ApiServiceTest.SEARCH_DATA)
                    request.url.contains("wbi/view") ->
                        envelopeResponse(0, ApiServiceTest.VIEW_DATA)
                    request.url.contains("pagelist") ->
                        envelopeResponse(0, ApiServiceTest.PAGELIST_DATA)
                    request.url.contains("/nav") ->
                        envelopeResponse(-101, ApiServiceTest.NAV_DATA)
                    else -> error("unexpected ${request.url}")
                }
            }
        }
    }

    @Test
    fun `blank query sends no request and returns an empty list`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        stack.gateway.requests.clear()
        assertTrue(repository.search("   ").isEmpty())
        assertTrue(stack.gateway.requests.isEmpty())
    }

    @Test
    fun `keyword search returns parsed tracks`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        val tracks = repository.search("周杰伦")
        assertEquals(2, tracks.size)
        assertEquals("BV1q4FTeBEMj", tracks[0].id)
        assertTrue(stack.gateway.requests.any { it.url.contains("search/all/v2") })
    }

    @Test
    fun `BV input goes straight to details, never to search`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        stack.gateway.requests.clear()
        val tracks = repository.search("BV1q4FTeBEMj")
        assertEquals(1, tracks.size)
        assertEquals("28139389900", tracks[0].cid)
        assertTrue(stack.gateway.requests.any { it.url.contains("wbi/view") })
        assertTrue(stack.gateway.requests.none { it.url.contains("search/all/v2") })
    }

    @Test
    fun `AV input converts to BV first`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        stack.gateway.requests.clear()
        val tracks = repository.search("av1415480")
        assertEquals(1, tracks.size)
        val detailUrl = stack.gateway.requests.first { it.url.contains("wbi/view") }.url
        assertTrue(detailUrl.contains("bvid=BV1fx411N7bU"))
    }

    @Test
    fun `AV input with non-digits falls through to keyword search`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        stack.gateway.requests.clear()
        repository.search("avXYZ")
        assertTrue(stack.gateway.requests.any { it.url.contains("search/all/v2") })
    }

    @Test
    fun `fetchDetail merges the pagelist fallback into the search track`() = runTest {
        val gateway = FakeGateway()
        val stack = Stack(gateway)
        gateway.handler = { request ->
            when {
                request.url.contains("finger/spi") ->
                    envelopeResponse(0, BiliBootstrapTest.FINGER_DATA)
                request.url.contains("GenWebTicket") ->
                    envelopeResponse(0, BiliBootstrapTest.TICKET_DATA)
                request.url == "https://www.bilibili.com/" ->
                    plainResponse(200, "<html/>")
                request.url.contains("wbi/view") ->
                    envelopeResponse(-412, "null", message = "banned")
                request.url.contains("pagelist") ->
                    envelopeResponse(0, ApiServiceTest.PAGELIST_DATA)
                request.url.contains("/nav") ->
                    envelopeResponse(-101, ApiServiceTest.NAV_DATA)
                else -> error("unexpected ${request.url}")
            }
        }
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        val searchTrack = Track(
            id = "BV1q4FTeBEMj",
            cid = "",
            title = "Search Title",
            artist = "Search Artist",
            cover = "https://cover.example/x.jpg",
            duration = 100.seconds,
        )
        val detailed = repository.fetchDetail(searchTrack)
        assertEquals("28139389900", detailed.cid)
        assertEquals("Search Title", detailed.title)
        assertEquals("Search Artist", detailed.artist)
        assertEquals("https://cover.example/x.jpg", detailed.cover)
        assertEquals(228_000L, detailed.duration.inWholeMilliseconds)
    }

    @Test
    fun `fetchDetail keeps the search track cover when the detail cover differs`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        val searchTrack = Track(
            id = "BV1q4FTeBEMj",
            cid = "",
            title = "Search Title",
            artist = "Search Artist",
            cover = "https://cover.example/x.jpg",
            duration = 100.seconds,
        )
        val detailed = repository.fetchDetail(searchTrack)
        assertEquals("28139389900", detailed.cid)
        assertEquals("https://cover.example/x.jpg", detailed.cover)
    }

    @Test
    fun `fetchDetail fills an empty cover from the detail response, upgraded to https`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        val searchTrack = Track(
            id = "BV1q4FTeBEMj",
            cid = "",
            title = "Search Title",
            artist = "Search Artist",
            cover = "",
            duration = 100.seconds,
        )
        val detailed = repository.fetchDetail(searchTrack)
        assertEquals("28139389900", detailed.cid)
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/25c409c43790cc13bf9ebc473b36c4f650527653.jpg",
            detailed.cover,
        )
    }
}
