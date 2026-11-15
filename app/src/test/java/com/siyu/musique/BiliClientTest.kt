package com.siyu.musique

import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.WbiKeyStore
import com.siyu.musique.core.network.parseNavKeys
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BiliClientTest {

    private fun client(
        gateway: FakeGateway,
        jar: CookieJar = CookieJar(),
        keys: WbiKeyStore? = null,
        sleeps: MutableList<Long>? = null,
        nowSeconds: () -> Long = { 1794710448L },
    ): BiliClient = BiliClient(
        gateway = gateway,
        cookieJar = jar,
        wbiKeys = keys,
        sleeper = { sleeps?.add(it) },
        jitter = { 0.5 },
        nowSeconds = nowSeconds,
    )

    private fun keyStore(
        keys: WbiKeyStore.WbiKeys = WbiKeyStore.WbiKeys(
            WbiSignerTest.IMG_KEY,
            WbiSignerTest.SUB_KEY,
        ),
    ): WbiKeyStore = WbiKeyStore(fetcher = { keys })

    @Test
    fun `code zero returns the data element`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, """{"hello":"world"}"""))
        val data = client(gateway).getData("https://api.bilibili.com/x/test")
        assertEquals("world", data.jsonObject["hello"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non-zero code throws BiliApiException carrying the code`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(-101, "null", message = "not logged in"))
        try {
            client(gateway).getData("https://api.bilibili.com/x/test")
            fail("expected BiliApiException")
        } catch (e: BiliApiException) {
            assertEquals(-101, e.code)
        }
    }

    @Test
    fun `allowed codes return the envelope instead of throwing`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(-101, """{"wbi_img":{}}"""))
        val envelope = client(gateway).getEnvelope(
            "https://api.bilibili.com/x/web-interface/nav",
            allowCodes = setOf(-101),
        )
        assertEquals(-101, envelope.code)
    }

    @Test
    fun `v_voucher pseudo-success is treated as minus 352, not empty results`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, """{"v_voucher":"abc"}"""))
        try {
            client(gateway).getData("https://api.bilibili.com/x/test")
            fail("expected BiliApiException")
        } catch (e: BiliApiException) {
            assertEquals(-352, e.code)
        }
    }

    @Test
    fun `set-cookie headers are ingested and sent on the next request`() = runTest {
        val jar = CookieJar()
        val gateway = FakeGateway()
        gateway.enqueue(
            envelopeResponse(
                0,
                "null",
                headers = mapOf(
                    "Set-Cookie" to listOf("buvid3=TESTBUVID; Domain=.bilibili.com; Path=/"),
                ),
            ),
        )
        gateway.enqueue(envelopeResponse(0, "null"))
        val c = client(gateway, jar)
        c.getData("https://api.bilibili.com/x/first")
        c.getData("https://api.bilibili.com/x/second")
        assertEquals("buvid3=TESTBUVID", gateway.requests[1].headers["Cookie"])
    }

    @Test
    fun `desktop user agent and referer are sent`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, "null"))
        client(gateway).getData("https://api.bilibili.com/x/test")
        val headers = gateway.requests.single().headers
        assertEquals(BiliClient.USER_AGENT, headers["User-Agent"])
        assertEquals("https://www.bilibili.com/", headers["Referer"])
    }

    @Test
    fun `origin is sent only on POST`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, "null"))
        gateway.enqueue(envelopeResponse(0, "null"))
        val c = client(gateway)
        c.getData("https://api.bilibili.com/x/test")
        c.postData("https://api.bilibili.com/x/test")
        assertEquals(null, gateway.requests[0].headers["Origin"])
        assertEquals("https://www.bilibili.com", gateway.requests[1].headers["Origin"])
    }

    @Test
    fun `HTTP 500 retries once and succeeds on the second attempt`() = runTest {
        val sleeps = mutableListOf<Long>()
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(500, "boom"))
        gateway.enqueue(envelopeResponse(0, """{"ok":true}"""))
        val data = client(gateway, sleeps = sleeps).getData("https://api.bilibili.com/x/test")
        assertEquals(2, gateway.requests.size)
        assertEquals(1, sleeps.size)
        assertEquals("true", data.jsonObject["ok"]?.jsonPrimitive?.content)
    }

    @Test
    fun `HTTP 500 twice exhausts the two attempts and throws`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(500, "boom"))
        gateway.enqueue(plainResponse(500, "boom"))
        gateway.enqueue(envelopeResponse(0, "null"))
        try {
            client(gateway).getData("https://api.bilibili.com/x/test")
            fail("expected BiliNetworkException")
        } catch (e: BiliNetworkException) {
            assertEquals(2, gateway.requests.size)
        }
    }

    @Test
    fun `transport IOException retries`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueueError(IOException("connection reset"))
        gateway.enqueue(envelopeResponse(0, "null"))
        client(gateway).getData("https://api.bilibili.com/x/test")
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `HTTP 404 does not retry`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(404, "not found"))
        gateway.enqueue(envelopeResponse(0, "null"))
        try {
            client(gateway).getData("https://api.bilibili.com/x/test")
            fail("expected BiliNetworkException")
        } catch (e: BiliNetworkException) {
            assertEquals(1, gateway.requests.size)
        }
    }

    @Test
    fun `backoff starts at 300ms with deterministic jitter`() = runTest {
        val sleeps = mutableListOf<Long>()
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(500, "boom"))
        gateway.enqueue(envelopeResponse(0, "null"))
        client(gateway, sleeps = sleeps).getData("https://api.bilibili.com/x/test")
        // jitter() returns 0.5, so the factor is 0.7 + 0.6 * 0.5 = 1.0.
        assertEquals(listOf(300L), sleeps)
    }

    @Test
    fun `non-JSON body throws BiliParseException with a preview`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(200, "<html>error page</html>"))
        try {
            client(gateway).getData("https://api.bilibili.com/x/test")
            fail("expected BiliParseException")
        } catch (e: BiliParseException) {
            assertEquals("<html>error page</html>", e.preview)
        }
    }

    @Test
    fun `envelope without a numeric code throws BiliParseException`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(200, """{"data":{}}"""))
        try {
            client(gateway).getData("https://api.bilibili.com/x/test")
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected
        }
    }

    @Test
    fun `signed requests carry wts and the exact verified w_rid`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, "null"))
        client(gateway, keys = keyStore()).getData(
            "https://api.bilibili.com/x/web-interface/wbi/view?bvid=BV1q4FTeBEMj",
            signed = true,
        )
        val url = gateway.requests.single().url
        assertTrue(
            url.contains("wts=1794710448&w_rid=d087368b7925114252e736f5e0691f97"),
        )
    }

    @Test
    fun `unsigned requests never carry w_rid`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, "null"))
        client(gateway, keys = keyStore()).getData(
            "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=test",
        )
        assertTrue(!gateway.requests.single().url.contains("w_rid"))
    }

    @Test
    fun `minus 352 on a signed request invalidates keys and retries once with fresh keys`() =
        runTest {
            var fetches = 0
            val keys = WbiKeyStore(fetcher = {
                fetches++
                if (fetches == 1) {
                    WbiKeyStore.WbiKeys(WbiSignerTest.IMG_KEY, WbiSignerTest.SUB_KEY)
                } else {
                    WbiKeyStore.WbiKeys(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    )
                }
            })
            val gateway = FakeGateway()
            gateway.enqueue(envelopeResponse(-352, "null", message = "risk"))
            gateway.enqueue(envelopeResponse(0, """{"ok":true}"""))
            client(gateway, keys = keys).getData(
                "https://api.bilibili.com/x/web-interface/wbi/view?bvid=BV1q4FTeBEMj",
                signed = true,
            )
            assertEquals(2, fetches)
            assertEquals(2, gateway.requests.size)
            val firstRid = gateway.requests[0].url.substringAfter("w_rid=")
            val secondRid = gateway.requests[1].url.substringAfter("w_rid=")
            assertTrue(firstRid != secondRid)
        }

    @Test
    fun `minus 352 twice on a signed request throws after one re-sign retry`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(-352, "null"))
        gateway.enqueue(envelopeResponse(-352, "null"))
        gateway.enqueue(envelopeResponse(0, "null"))
        try {
            client(gateway, keys = keyStore()).getData(
                "https://api.bilibili.com/x/web-interface/wbi/view?bvid=BV1q4FTeBEMj",
                signed = true,
            )
            fail("expected BiliApiException")
        } catch (e: BiliApiException) {
            assertEquals(-352, e.code)
            assertEquals(2, gateway.requests.size)
        }
    }

    @Test
    fun `HTTP 412 on an unsigned request throws minus 412 without retry`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(412, "<html>banned</html>"))
        gateway.enqueue(envelopeResponse(0, "null"))
        try {
            client(gateway).getData("https://api.bilibili.com/x/web-interface/view?bvid=x")
            fail("expected BiliApiException")
        } catch (e: BiliApiException) {
            assertEquals(-412, e.code)
            assertEquals(1, gateway.requests.size)
        }
    }

    @Test
    fun `HTTP 412 on a signed request re-signs and retries once`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(412, "<html>banned</html>"))
        gateway.enqueue(envelopeResponse(0, "null"))
        client(gateway, keys = keyStore()).getData(
            "https://api.bilibili.com/x/web-interface/wbi/view?bvid=BV1q4FTeBEMj",
            signed = true,
        )
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `key fetcher calling back into the client does not deadlock on risk retry`() = runTest {
        // Regression test: the key fetcher fetches nav through this
        // same client. Resolving keys while holding the request mutex
        // deadlocked the risk retry; keys must resolve outside it.
        val gateway = FakeGateway()
        lateinit var c: BiliClient
        val keys = WbiKeyStore(fetcher = {
            val envelope = c.getEnvelope(
                "https://api.bilibili.com/x/web-interface/nav",
                allowCodes = setOf(-101),
            )
            parseNavKeys(envelope.data)
        })
        c = client(gateway, keys = keys)
        gateway.handler = { request ->
            when {
                request.url.contains("/nav") ->
                    envelopeResponse(-101, ApiServiceTest.NAV_DATA, message = "not logged in")
                request.url.contains("wbi/view") -> {
                    val viewCalls = gateway.requests.count { it.url.contains("wbi/view") }
                    if (viewCalls == 1) {
                        envelopeResponse(-352, "null", message = "risk")
                    } else {
                        envelopeResponse(0, """{"ok":true}""")
                    }
                }
                else -> error("unexpected ${request.url}")
            }
        }
        val data = c.getData(
            "https://api.bilibili.com/x/web-interface/wbi/view?bvid=BV1q4FTeBEMj",
            signed = true,
        )
        assertEquals("true", data.jsonObject["ok"]?.jsonPrimitive?.content)
        assertEquals(2, gateway.requests.count { it.url.contains("/nav") })
        assertEquals(2, gateway.requests.count { it.url.contains("wbi/view") })
    }

    @Test
    fun `fetchRaw returns the response without parsing JSON`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(plainResponse(200, "<html>home page</html>"))
        val response = client(gateway).fetchRaw("https://www.bilibili.com/")
        assertEquals(200, response.status)
        assertEquals("<html>home page</html>", response.body)
    }
}
