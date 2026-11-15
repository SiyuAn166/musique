package com.siyu.musique

import com.siyu.musique.core.network.WbiSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WbiSignerTest {

    companion object {
        // Smoke-test fixture keys (Nov 9 and Nov 14 probes returned
        // the same keys). Fixtures only: app code never hardcodes
        // keys, it refreshes them from nav / GenWebTicket.
        const val IMG_KEY = "7cd084941338484aae1ad9425b84077c"
        const val SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45"
        const val MIXIN = "ea1db124af3c7062474693fa704f4ff8"
    }

    @Test
    fun `mixin key matches the verified smoke-test value`() {
        assertEquals(MIXIN, WbiSigner.mixinKeyOf(IMG_KEY, SUB_KEY))
    }

    @Test
    fun `signing a fixed request produces the exact verified query string`() {
        // w_rid computed independently in Python:
        // md5("bvid=BV1q4FTeBEMj&wts=1794710448" + mixin).
        val signed = WbiSigner.sign(
            params = mapOf("bvid" to "BV1q4FTeBEMj"),
            mixinKey = MIXIN,
            wtsSeconds = 1794710448L,
        )
        assertEquals(
            "bvid=BV1q4FTeBEMj&wts=1794710448&w_rid=d087368b7925114252e736f5e0691f97",
            signed,
        )
    }

    @Test
    fun `keys are sorted before signing`() {
        val signed = WbiSigner.sign(
            params = mapOf("zebra" to "1", "apple" to "2"),
            mixinKey = MIXIN,
            wtsSeconds = 100L,
        )
        assertTrue(signed.startsWith("apple=2&wts=100&zebra=1&w_rid="))
    }

    @Test
    fun `filtered characters are removed from values before signing`() {
        val withNoise = WbiSigner.sign(
            params = mapOf("k" to "a!b'c(d)e*f"),
            mixinKey = MIXIN,
            wtsSeconds = 100L,
        )
        val clean = WbiSigner.sign(
            params = mapOf("k" to "abcdef"),
            mixinKey = MIXIN,
            wtsSeconds = 100L,
        )
        assertEquals(clean, withNoise)
    }

    @Test
    fun `spaces encode as percent-20, never plus`() {
        assertEquals("a%20b", WbiSigner.percentEncode("a b"))
        val signed = WbiSigner.sign(
            params = mapOf("k" to "a b"),
            mixinKey = MIXIN,
            wtsSeconds = 100L,
        )
        assertTrue(signed.contains("k=a%20b"))
    }

    @Test
    fun `short keys are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WbiSigner.mixinKeyOf("short", "keys")
        }
    }

    @Test
    fun `key extraction takes the filename without extension`() {
        assertEquals(
            IMG_KEY,
            WbiSigner.keyFromImageUrl(
                "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
            ),
        )
        assertEquals(
            SUB_KEY,
            WbiSigner.keyFromImageUrl(
                "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png",
            ),
        )
    }
}
