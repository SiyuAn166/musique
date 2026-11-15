package com.siyu.musique

import com.siyu.musique.core.network.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieJarTest {

    // Fixed clock: Nov 14, 2026.
    private var now = 1794710400000L
    private val jar = CookieJar(nowMillis = { now })

    @Test
    fun `set-cookie is stored and sent back on matching requests`() {
        jar.storeFromSetCookie(
            "SESSDATA=abc123; Domain=.bilibili.com; Path=/; Expires=Wed, 01 Jan 2031 00:00:00 GMT",
            "https://api.bilibili.com/x/web-interface/nav",
        )
        assertEquals(
            "SESSDATA=abc123",
            jar.headerFor("https://api.bilibili.com/x/web-interface/search/all/v2"),
        )
        assertEquals("abc123", jar.valueOf("SESSDATA"))
    }

    @Test
    fun `domain matching covers subdomains but not other domains`() {
        jar.set("a", "1", "bilibili.com")
        assertEquals("a=1", jar.headerFor("https://api.bilibili.com/x/"))
        assertEquals("a=1", jar.headerFor("https://www.bilibili.com/"))
        assertNull(jar.headerFor("https://evilbilibili.com/"))
        assertNull(jar.headerFor("https://example.com/"))
    }

    @Test
    fun `path matching requires a path prefix`() {
        jar.set("a", "1", "bilibili.com", path = "/x/web-interface")
        assertEquals("a=1", jar.headerFor("https://api.bilibili.com/x/web-interface/nav"))
        assertNull(jar.headerFor("https://api.bilibili.com/x/player/pagelist"))
    }

    @Test
    fun `expired cookies are not sent`() {
        jar.storeFromSetCookie(
            "old=1; Domain=bilibili.com; Path=/; Expires=Wed, 01 Jan 2020 00:00:00 GMT",
            "https://api.bilibili.com/",
        )
        assertNull(jar.headerFor("https://api.bilibili.com/"))
        assertNull(jar.valueOf("old"))
    }

    @Test
    fun `max-age zero deletes the cookie`() {
        jar.set("gone", "1", "bilibili.com")
        jar.storeFromSetCookie(
            "gone=; Domain=bilibili.com; Path=/; Max-Age=0",
            "https://api.bilibili.com/",
        )
        assertNull(jar.headerFor("https://api.bilibili.com/"))
    }

    @Test
    fun `max-age expiry is honored as time passes`() {
        jar.storeFromSetCookie(
            "short=1; Domain=bilibili.com; Path=/; Max-Age=60",
            "https://api.bilibili.com/",
        )
        assertEquals("short=1", jar.headerFor("https://api.bilibili.com/"))
        now += 61_000L
        assertNull(jar.headerFor("https://api.bilibili.com/"))
    }

    @Test
    fun `secure cookies are only sent over https`() {
        jar.storeFromSetCookie(
            "s=1; Domain=bilibili.com; Path=/; Secure",
            "https://api.bilibili.com/",
        )
        assertEquals("s=1", jar.headerFor("https://api.bilibili.com/"))
        assertNull(jar.headerFor("http://api.bilibili.com/"))
    }

    @Test
    fun `re-setting the same name domain and path overwrites`() {
        jar.set("a", "1", "bilibili.com")
        jar.set("a", "2", "bilibili.com")
        assertEquals("a=2", jar.headerFor("https://api.bilibili.com/"))
        assertEquals(1, jar.all().size)
    }

    @Test
    fun `multiple cookies join with semicolons`() {
        jar.set("a", "1", "bilibili.com")
        jar.set("b", "2", "bilibili.com")
        val header = jar.headerFor("https://api.bilibili.com/") ?: ""
        assertTrue(header.contains("a=1"))
        assertTrue(header.contains("b=2"))
    }

    @Test
    fun `malformed set-cookie headers are ignored, not fatal`() {
        jar.storeFromSetCookie("not-a-cookie", "https://api.bilibili.com/")
        jar.storeFromSetCookie("", "https://api.bilibili.com/")
        assertTrue(jar.all().isEmpty())
    }
}
