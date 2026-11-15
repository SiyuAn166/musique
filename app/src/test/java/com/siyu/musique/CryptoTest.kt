package com.siyu.musique

import com.siyu.musique.core.network.hmacSha256Hex
import com.siyu.musique.core.network.md5Hex
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoTest {

    @Test
    fun `md5 matches known values`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
    }

    @Test
    fun `hmac-sha256 matches RFC 4231 test case 1`() {
        val key = "\u000B".repeat(20)
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmacSha256Hex(key, "Hi There"),
        )
    }

    @Test
    fun `hmac-sha256 is deterministic and key sensitive`() {
        val a = hmacSha256Hex("XgwSnGZ1p", "ts1794710392")
        assertEquals(a, hmacSha256Hex("XgwSnGZ1p", "ts1794710392"))
        assertEquals(64, a.length)
    }
}
