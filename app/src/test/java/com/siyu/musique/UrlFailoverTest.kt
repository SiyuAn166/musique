package com.siyu.musique

import com.siyu.musique.player.UrlFailover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlFailoverTest {

    @Test
    fun `empty list has no current URL and never advances`() {
        val failover = UrlFailover()
        failover.reset(emptyList())
        assertNull(failover.current())
        assertFalse(failover.hasUntried())
        assertNull(failover.advance())
        assertEquals(0, failover.attemptCount)
    }

    @Test
    fun `single URL is current with nothing untried`() {
        val failover = UrlFailover()
        failover.reset(listOf("https://cdn.example/only.m4s"))
        assertEquals("https://cdn.example/only.m4s", failover.current())
        assertFalse(failover.hasUntried())
        assertNull(failover.advance())
        assertEquals(1, failover.attemptCount)
    }

    @Test
    fun `advance walks the list in order then exhausts`() {
        val failover = UrlFailover()
        failover.reset(
            listOf(
                "https://cdn.example/primary.m4s",
                "https://cdn.example/backup1.m4s",
                "https://cdn.example/backup2.m4s",
            ),
        )
        assertEquals("https://cdn.example/primary.m4s", failover.current())
        assertTrue(failover.hasUntried())
        assertEquals("https://cdn.example/backup1.m4s", failover.advance())
        assertEquals("https://cdn.example/backup1.m4s", failover.current())
        assertEquals(2, failover.attemptCount)
        assertTrue(failover.hasUntried())
        assertEquals("https://cdn.example/backup2.m4s", failover.advance())
        assertFalse(failover.hasUntried())
        assertEquals(3, failover.attemptCount)
        assertNull(failover.advance())
        assertEquals("https://cdn.example/backup2.m4s", failover.current())
    }

    @Test
    fun `reset starts a fresh load from the first URL`() {
        val failover = UrlFailover()
        failover.reset(listOf("https://cdn.example/a.m4s", "https://cdn.example/b.m4s"))
        failover.advance()
        failover.reset(listOf("https://cdn.example/c.m4s"))
        assertEquals("https://cdn.example/c.m4s", failover.current())
        assertFalse(failover.hasUntried())
        assertEquals(1, failover.attemptCount)
    }

    @Test
    fun `reset drops blanks and duplicates keeping first-occurrence order`() {
        val failover = UrlFailover()
        failover.reset(
            listOf(
                "",
                "https://cdn.example/a.m4s",
                "https://cdn.example/a.m4s",
                "  ",
                "https://cdn.example/b.m4s",
            ),
        )
        assertEquals("https://cdn.example/a.m4s", failover.current())
        assertEquals("https://cdn.example/b.m4s", failover.advance())
        assertNull(failover.advance())
    }
}
