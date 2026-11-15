package com.siyu.musique

import com.siyu.musique.core.network.sizedCoverUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SizedCoverUrlTest {

    @Test
    fun `bilibili covers get the resize suffix`() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/abc.jpg@320w_200h_1c",
            sizedCoverUrl("https://i0.hdslb.com/bfs/archive/abc.jpg", 320, 200),
        )
    }

    @Test
    fun `empty urls pass through`() {
        assertEquals("", sizedCoverUrl("", 320, 200))
    }

    @Test
    fun `non bilibili hosts pass through`() {
        val url = "https://cdn.example/cover.jpg"
        assertEquals(url, sizedCoverUrl(url, 320, 200))
    }

    @Test
    fun `urls that already carry a suffix pass through`() {
        val url = "https://i0.hdslb.com/bfs/archive/abc.jpg@320w_200h_1c"
        assertEquals(url, sizedCoverUrl(url, 640, 400))
    }
}
