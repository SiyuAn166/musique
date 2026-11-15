package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.BiliParseException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRepositoryTest {

    private class Stack(val gateway: FakeGateway, val logger: MusiqueLogger) {
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
        val api = ApiService(client, logger)
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
            logger = logger,
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
                    request.url.contains("playurl") ->
                        envelopeResponse(0, PlayUrlTest.PLAYURL_DATA)
                    request.url.contains("/nav") ->
                        envelopeResponse(-101, ApiServiceTest.NAV_DATA)
                    else -> error("unexpected ${request.url}")
                }
            }
        }
    }

    private fun track(cid: String = "28139389900") = Track(
        id = "BV1q4FTeBEMj",
        cid = cid,
        title = "t",
        artist = "a",
        cover = "",
        duration = 228.seconds,
    )

    @Test
    fun `fetchPlayUrl returns the picked 30280 stream for a track with a cid`() = runTest {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val stack = Stack(FakeGateway(), logger)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope, logger)
        advanceUntilIdle()
        val stream = repository.fetchPlayUrl(track())
        assertEquals(30280, stream.qualityId)
        assertTrue(stream.baseUrl.contains("fake192.m4s"))
        val playurlRequest = stack.gateway.requests.single { it.url.contains("playurl") }
        assertTrue(playurlRequest.url.contains("bvid=BV1q4FTeBEMj"))
        assertTrue(playurlRequest.url.contains("cid=28139389900"))
        assertTrue(playurlRequest.url.contains("fnval=4048"))
    }

    @Test
    fun `fetchPlayUrl resolves fresh on every call, never cached`() = runTest {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val stack = Stack(FakeGateway(), logger)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope, logger)
        advanceUntilIdle()
        repository.fetchPlayUrl(track())
        repository.fetchPlayUrl(track())
        assertEquals(2, stack.gateway.requests.count { it.url.contains("playurl") })
    }

    @Test
    fun `fetchPlayUrl throws without a cid and sends no request`() = runTest {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val stack = Stack(FakeGateway(), logger)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope, logger)
        advanceUntilIdle()
        stack.gateway.requests.clear()
        try {
            repository.fetchPlayUrl(track(cid = ""))
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected
        }
        assertTrue(stack.gateway.requests.none { it.url.contains("playurl") })
    }

    @Test
    fun `fetchPlayUrl propagates API errors`() = runTest {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val gateway = FakeGateway()
        val stack = Stack(gateway, logger)
        gateway.handler = { request ->
            when {
                request.url.contains("finger/spi") ->
                    envelopeResponse(0, BiliBootstrapTest.FINGER_DATA)
                request.url.contains("GenWebTicket") ->
                    envelopeResponse(0, BiliBootstrapTest.TICKET_DATA)
                request.url == "https://www.bilibili.com/" ->
                    plainResponse(200, "<html/>")
                request.url.contains("playurl") ->
                    envelopeResponse(-352, "null", message = "risk")
                request.url.contains("/nav") ->
                    envelopeResponse(-101, ApiServiceTest.NAV_DATA)
                else -> error("unexpected ${request.url}")
            }
        }
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope, logger)
        advanceUntilIdle()
        try {
            repository.fetchPlayUrl(track())
            fail("expected BiliApiException")
        } catch (e: BiliApiException) {
            assertEquals(-352, e.code)
        }
    }

    @Test
    fun `playurl log lines carry host and quality, never the full URL`() = runTest {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val stack = Stack(FakeGateway(), logger)
        val repository = BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope, logger)
        advanceUntilIdle()
        logger.buffer.clear()
        repository.fetchPlayUrl(track())
        val messages = logger.buffer.lines().map { it.message }
        val playurlLine = messages.single { it.startsWith("playurl ") }
        assertTrue(playurlLine.contains("bvid=BV1q4FTeBEMj"))
        assertTrue(playurlLine.contains("cid=28139389900"))
        assertTrue(playurlLine.contains("quality=30280"))
        assertTrue(playurlLine.contains("host=upos-sz-mirrorcosov.bilivideo.com"))
        assertFalse(playurlLine.contains("fake192.m4s"))
        assertFalse(playurlLine.contains("token=fake"))
        assertTrue(messages.none { it.contains("bili_ticket") })
    }

    @Test
    fun `bootstrap logs start and ok, never secrets`() = runTest {
        val logger = MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L }))
        val stack = Stack(FakeGateway(), logger)
        // Drive the bootstrap directly: the repository's background
        // launch (backgroundScope) is not advanced by advanceUntilIdle
        // in runTest, so the direct call is the deterministic way to
        // exercise the bootstrap's logging. The background launch
        // itself runs on Dispatchers.IO in production.
        BiliSearchRepository(stack.api, stack.bootstrap, backgroundScope, logger)
        assertTrue(stack.bootstrap.ensureBootstrap())
        val messages = logger.buffer.lines().map { it.message }
        assertTrue(messages.contains("bootstrap start"))
        assertTrue(messages.contains("bootstrap ok"))
        assertTrue(messages.none { it.contains("bili_ticket=") })
        assertTrue(messages.none { it.contains("SESSDATA") })
    }
}
