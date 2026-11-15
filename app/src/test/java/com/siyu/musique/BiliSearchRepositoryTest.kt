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

        /** The body the suggest endpoint answers with; tests override it to fail. */
        var suggestBody: String = ApiServiceTest.SUGGEST_BODY

        init {
            keys.fetcher = { api.fetchNavKeys() }
            gateway.handler = { request ->
                when {
                    request.url.contains("main/suggest") ->
                        plainResponse(200, suggestBody)
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
    fun `suggestions return the parsed values`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        assertEquals(
            listOf("周杰伦歌曲全集", "周杰伦", "周杰伦mv"),
            repository.fetchSuggestions("周杰伦"),
        )
    }

    @Test
    fun `blank suggestion query sends no request`() = runTest {
        val stack = Stack(FakeGateway())
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        stack.gateway.requests.clear()
        assertTrue(repository.fetchSuggestions("   ").isEmpty())
        assertTrue(stack.gateway.requests.isEmpty())
    }

    @Test
    fun `suggestion failure yields an empty list instead of throwing`() = runTest {
        val stack = Stack(FakeGateway())
        stack.suggestBody = """{"code":3,"result":{"tag":[]}}"""
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        assertTrue(repository.fetchSuggestions("周杰伦").isEmpty())
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
        val detailed = repository.fetchDetail(searchTrack).track
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
        val detailed = repository.fetchDetail(searchTrack).track
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
        val detailed = repository.fetchDetail(searchTrack).track
        assertEquals("28139389900", detailed.cid)
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/25c409c43790cc13bf9ebc473b36c4f650527653.jpg",
            detailed.cover,
        )
    }

    @Test
    fun `fetchDetail turns every part into its own track`() = runTest {
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
                    envelopeResponse(0, ApiServiceTest.VIEW_DATA_MULTI)
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
        assertEquals("28139389900", detailed.track.cid)
        assertEquals(3, detailed.parts.size)
        assertEquals("001. 夜曲-周杰伦", detailed.parts[0].title)
        assertEquals("28139389900", detailed.parts[0].cid)
        assertEquals(228_000L, detailed.parts[0].duration.inWholeMilliseconds)
        assertEquals("002. 青花瓷-周杰伦", detailed.parts[1].title)
        assertEquals("28139389901", detailed.parts[1].cid)
        assertEquals("003. 稻香-周杰伦", detailed.parts[2].title)
        // Parts share the video's artist and the known-good search cover.
        assertTrue(detailed.parts.all { it.artist == "无损音乐合集" })
        assertTrue(detailed.parts.all { it.cover == "https://cover.example/x.jpg" })
        assertTrue(detailed.parts.all { it.id == "BV1q4FTeBEMj" })
    }

    @Test
    fun `fetchParts maps pagelist parts to tracks without a signed details call`() = runTest {
        val gateway = FakeGateway()
        val stack = Stack(gateway)
        val pagelistBody =
            """[{"cid":111,"page":1,"part":"First Song","duration":200},""" +
                """{"cid":222,"page":2,"part":"","duration":0}]"""
        gateway.handler = { request ->
            when {
                request.url.contains("finger/spi") ->
                    envelopeResponse(0, BiliBootstrapTest.FINGER_DATA)
                request.url.contains("GenWebTicket") ->
                    envelopeResponse(0, BiliBootstrapTest.TICKET_DATA)
                request.url == "https://www.bilibili.com/" ->
                    plainResponse(200, "<html/>")
                request.url.contains("pagelist") ->
                    envelopeResponse(0, pagelistBody)
                request.url.contains("/nav") ->
                    envelopeResponse(-101, ApiServiceTest.NAV_DATA)
                else -> error("unexpected ${request.url}")
            }
        }
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        gateway.requests.clear()
        val video = Track(
            id = "BV1PROBE",
            cid = "",
            title = "Video Title",
            artist = "Artist",
            cover = "https://cover.example/x.jpg",
            duration = 500.seconds,
        )
        val parts = repository.fetchParts(video)
        assertEquals(2, parts.size)
        assertEquals("111", parts[0].cid)
        assertEquals("First Song", parts[0].title)
        assertEquals(200_000L, parts[0].duration.inWholeMilliseconds)
        // An unnamed part with no duration falls back to the video's title and duration.
        assertEquals("222", parts[1].cid)
        assertEquals("Video Title", parts[1].title)
        assertEquals(500_000L, parts[1].duration.inWholeMilliseconds)
        assertTrue(parts.all { it.artist == "Artist" })
        assertTrue(parts.all { it.cover == "https://cover.example/x.jpg" })
        assertTrue(parts.all { it.id == "BV1PROBE" })
        // The probe stays on the unsigned pagelist: no signed details call.
        assertTrue(gateway.requests.any { it.url.contains("pagelist") })
        assertTrue(gateway.requests.none { it.url.contains("wbi/view") })
    }

    @Test
    fun `fetchParts falls back to details when the pagelist fails`() = runTest {
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
                request.url.contains("pagelist") ->
                    envelopeResponse(-352, "{}")
                request.url.contains("wbi/view") ->
                    envelopeResponse(0, ApiServiceTest.VIEW_DATA)
                request.url.contains("/nav") ->
                    envelopeResponse(-101, ApiServiceTest.NAV_DATA)
                else -> error("unexpected ${request.url}")
            }
        }
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope)
        advanceUntilIdle()
        gateway.requests.clear()
        val video = Track(
            id = "BV1FALLBACK",
            cid = "",
            title = "Video Title",
            artist = "Artist",
            cover = "https://cover.example/x.jpg",
            duration = 500.seconds,
        )
        val parts = repository.fetchParts(video)
        assertEquals(1, parts.size)
        assertEquals("28139389900", parts[0].cid)
        assertEquals("001. 夜曲-周杰伦", parts[0].title)
        assertTrue(gateway.requests.any { it.url.contains("pagelist") })
        assertTrue(gateway.requests.any { it.url.contains("wbi/view") })
    }
}
