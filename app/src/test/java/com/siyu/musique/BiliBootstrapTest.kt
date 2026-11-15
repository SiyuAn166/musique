package com.siyu.musique

import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.BootstrapState
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.InMemoryBootstrapStore
import com.siyu.musique.core.network.WbiKeyStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliBootstrapTest {

    companion object {
        // Fixtures from the Nov 14, 2026 live probe. The ticket value
        // itself is a placeholder: only its presence, ttl, and nav
        // keys matter to the bootstrap.
        const val FINGER_DATA = """{"b_3":"BUVID3-TEST","b_4":"BUVID4-TEST"}"""
        const val TICKET_DATA = """{"ticket":"test-ticket-value","created_at":1794710392,"ttl":259200,"nav":{"img":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}"""
        val FIXED_IDENTITY = BiliBootstrap.DeviceIdentity(
            uuid = "FIXEDUUID12345infoc",
            bLsid = "AABBCCDD11223344",
            bNut = "1794710392",
        )
    }

    private class Fixture(
        val gateway: FakeGateway = FakeGateway(),
        val jar: CookieJar = CookieJar(),
        val store: InMemoryBootstrapStore = InMemoryBootstrapStore(),
        val keys: WbiKeyStore = WbiKeyStore(),
    ) {
        val bootstrap = BiliBootstrap(
            client = BiliClient(
                gateway = gateway,
                cookieJar = jar,
                wbiKeys = keys,
                sleeper = {},
                jitter = { 0.5 },
                nowSeconds = { 1794710392L },
            ),
            cookieJar = jar,
            store = store,
            wbiKeys = keys,
            generateIdentity = { FIXED_IDENTITY },
            nowSeconds = { 1794710392L },
            nowMillis = { 1794710392000L },
        )
    }

    private fun happyGateway(): FakeGateway {
        val gateway = FakeGateway()
        gateway.handler = { request ->
            when {
                request.url.contains("finger/spi") -> envelopeResponse(0, FINGER_DATA)
                request.url.contains("GenWebTicket") -> envelopeResponse(0, TICKET_DATA)
                request.url == "https://www.bilibili.com/" -> plainResponse(200, "<html/>")
                else -> error("unexpected ${request.url}")
            }
        }
        return gateway
    }

    @Test
    fun `happy path stores buvid and ticket, seeds cookies and WBI keys`() = runTest {
        val fixture = Fixture(gateway = happyGateway())
        assertTrue(fixture.bootstrap.ensureBootstrap())
        assertNull(fixture.bootstrap.lastError)

        val state = fixture.store.load()!!
        assertEquals("BUVID3-TEST", state.buvid3)
        assertEquals("BUVID4-TEST", state.buvid4)
        assertEquals("test-ticket-value", state.biliTicket)
        assertEquals(1794710392L + 259200L, state.ticketExpiresAtSeconds)
        assertEquals(WbiSignerTest.IMG_KEY, state.wbiImgKey)
        assertEquals(WbiSignerTest.SUB_KEY, state.wbiSubKey)

        assertEquals("FIXEDUUID12345infoc", fixture.jar.valueOf("_uuid"))
        assertEquals("AABBCCDD11223344", fixture.jar.valueOf("b_lsid"))
        assertEquals("BUVID3-TEST", fixture.jar.valueOf("buvid3"))
        assertEquals("BUVID4-TEST", fixture.jar.valueOf("buvid4"))
        assertEquals("test-ticket-value", fixture.jar.valueOf("bili_ticket"))

        // WBI keys were seeded from the ticket nav: no fetcher needed.
        assertEquals(WbiSignerTest.MIXIN, fixture.keys.getMixinKey())

        val urls = fixture.gateway.requests.map { it.url }
        assertTrue(urls.any { it.contains("finger/spi") })
        assertTrue(urls.any { it.contains("GenWebTicket") })
        assertTrue(urls.any { it == "https://www.bilibili.com/" })
    }

    @Test
    fun `GenWebTicket POST carries key_id, hexsign, ts, and csrf`() = runTest {
        val fixture = Fixture(gateway = happyGateway())
        fixture.bootstrap.ensureBootstrap()
        val ticket = fixture.gateway.requests.first { it.url.contains("GenWebTicket") }
        assertEquals("POST", ticket.method)
        assertTrue(ticket.url.contains("key_id=ec02"))
        assertTrue(ticket.url.contains("hexsign="))
        assertTrue(ticket.url.contains("context%5Bts%5D=1794710392"))
        assertTrue(ticket.url.contains("csrf="))
        assertEquals("https://www.bilibili.com", ticket.headers["Origin"])
    }

    @Test
    fun `finger failure records lastError, returns false, never throws`() = runTest {
        val gateway = FakeGateway()
        gateway.handler = { throw IOException("no network") }
        val fixture = Fixture(gateway = gateway)
        assertFalse(fixture.bootstrap.ensureBootstrap())
        assertNotNull(fixture.bootstrap.lastError)
        // The generated identity was still persisted for the retry.
        assertEquals("FIXEDUUID12345infoc", fixture.store.load()?.uuid)
    }

    @Test
    fun `ticket failure keeps the buvid that finger already stored`() = runTest {
        val gateway = FakeGateway()
        gateway.handler = { request ->
            if (request.url.contains("finger/spi")) {
                envelopeResponse(0, FINGER_DATA)
            } else {
                throw IOException("no network")
            }
        }
        val fixture = Fixture(gateway = gateway)
        assertFalse(fixture.bootstrap.ensureBootstrap())
        assertEquals("BUVID3-TEST", fixture.store.load()?.buvid3)
        assertEquals("", fixture.store.load()?.biliTicket)
    }

    @Test
    fun `www activation failure is ignored and bootstrap still succeeds`() = runTest {
        val gateway = FakeGateway()
        gateway.handler = { request ->
            when {
                request.url.contains("finger/spi") -> envelopeResponse(0, FINGER_DATA)
                request.url.contains("GenWebTicket") -> envelopeResponse(0, TICKET_DATA)
                else -> throw IOException("www unreachable")
            }
        }
        val fixture = Fixture(gateway = gateway)
        assertTrue(fixture.bootstrap.ensureBootstrap())
        assertNull(fixture.bootstrap.lastError)
    }

    @Test
    fun `fresh stored state skips finger and ticket, only the activation hit runs`() =
        runTest {
            val stored = BootstrapState(
                uuid = "STOREDUUID",
                bLsid = "STOREDLSID",
                bNut = "1794710392",
                buvid3 = "STORED-B3",
                buvid4 = "STORED-B4",
                biliTicket = "stored-ticket",
                ticketExpiresAtSeconds = 1794710392L + 259200L,
                wbiImgKey = WbiSignerTest.IMG_KEY,
                wbiSubKey = WbiSignerTest.SUB_KEY,
                wbiFetchedAtMillis = 1794710392000L,
            )
            val gateway = FakeGateway()
            gateway.handler = { plainResponse(200, "<html/>") }
            val fixture = Fixture(
                gateway = gateway,
                store = InMemoryBootstrapStore(stored),
            )
            assertTrue(fixture.bootstrap.ensureBootstrap())
            assertEquals(1, gateway.requests.size)
            assertEquals("https://www.bilibili.com/", gateway.requests.single().url)
            assertEquals("STORED-B3", fixture.jar.valueOf("buvid3"))
            assertEquals(WbiSignerTest.MIXIN, fixture.keys.getMixinKey())
        }

    @Test
    fun `expired ticket is renewed`() = runTest {
        val stored = BootstrapState(
            uuid = "STOREDUUID",
            bLsid = "STOREDLSID",
            bNut = "1794710392",
            buvid3 = "STORED-B3",
            buvid4 = "STORED-B4",
            biliTicket = "old-ticket",
            ticketExpiresAtSeconds = 1794710392L - 10L,
        )
        val fixture = Fixture(
            gateway = happyGateway(),
            store = InMemoryBootstrapStore(stored),
        )
        assertTrue(fixture.bootstrap.ensureBootstrap())
        assertEquals("test-ticket-value", fixture.store.load()?.biliTicket)
        assertTrue(fixture.gateway.requests.none { it.url.contains("finger/spi") })
        assertTrue(fixture.gateway.requests.any { it.url.contains("GenWebTicket") })
    }

    @Test
    fun `second call after success does no work`() = runTest {
        val fixture = Fixture(gateway = happyGateway())
        assertTrue(fixture.bootstrap.ensureBootstrap())
        val count = fixture.gateway.requests.size
        assertTrue(fixture.bootstrap.ensureBootstrap())
        assertEquals(count, fixture.gateway.requests.size)
    }
}
