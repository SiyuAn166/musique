package com.siyu.musique

import com.siyu.musique.core.network.normalizeCover
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cover URL normalization: every cover must end up https, or it never loads on device. */
class NormalizeCoverTest {

    @Test
    fun `protocol-relative cover gets an https prefix`() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/x.jpg",
            normalizeCover("//i0.hdslb.com/bfs/archive/x.jpg"),
        )
    }

    @Test
    fun `plain http cover is upgraded to https`() {
        // The details response returns an http:// pic; Android blocks
        // cleartext HTTP by default, so it must be upgraded.
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/x.jpg",
            normalizeCover("http://i2.hdslb.com/bfs/archive/x.jpg"),
        )
    }

    @Test
    fun `https cover passes through unchanged`() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/x.jpg",
            normalizeCover("https://i0.hdslb.com/bfs/archive/x.jpg"),
        )
    }

    @Test
    fun `empty cover stays empty`() {
        assertEquals("", normalizeCover(""))
    }
}
