package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.LogLevel
import com.siyu.musique.core.logging.LogRedactor
import com.siyu.musique.core.logging.MusiqueLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusiqueLogTest {

    @Test
    fun `buffer keeps lines oldest first in insertion order`() {
        val buffer = LogBuffer(capacity = 10, clock = { 1000L })
        buffer.append(LogLevel.INFO, "A", "first")
        buffer.append(LogLevel.WARN, "B", "second")
        buffer.append(LogLevel.ERROR, "C", "third")
        val lines = buffer.lines()
        assertEquals(3, lines.size)
        assertEquals("first", lines[0].message)
        assertEquals("second", lines[1].message)
        assertEquals("third", lines[2].message)
        assertEquals(LogLevel.WARN, lines[1].level)
        assertEquals("B", lines[1].tag)
    }

    @Test
    fun `buffer evicts the oldest lines past capacity`() {
        val buffer = LogBuffer(capacity = 3, clock = { 1000L })
        repeat(5) { buffer.append(LogLevel.INFO, "T", "line $it") }
        val lines = buffer.lines()
        assertEquals(3, lines.size)
        assertEquals("line 2", lines[0].message)
        assertEquals("line 3", lines[1].message)
        assertEquals("line 4", lines[2].message)
    }

    @Test
    fun `default capacity is 300`() {
        assertEquals(300, LogBuffer.DEFAULT_CAPACITY)
    }

    @Test
    fun `clear drops every line`() {
        val buffer = LogBuffer(capacity = 10, clock = { 1000L })
        buffer.append(LogLevel.INFO, "T", "one")
        buffer.append(LogLevel.INFO, "T", "two")
        buffer.clear()
        assertTrue(buffer.lines().isEmpty())
    }

    @Test
    fun `snapshot flow emits the buffered lines`() = runTest {
        val buffer = LogBuffer(capacity = 10, clock = { 1000L })
        buffer.append(LogLevel.INFO, "T", "hello")
        assertEquals(listOf("hello"), buffer.snapshot.first().map { it.message })
        buffer.clear()
        assertTrue(buffer.snapshot.first().isEmpty())
    }

    @Test
    fun `format renders timestamp level tag and message`() {
        val buffer = LogBuffer(capacity = 10, clock = { 1794710448123L })
        val line = buffer.append(LogLevel.ERROR, "Player", "boom")
        val formatted = line.format()
        assertTrue(formatted.endsWith("E/Player: boom"))
        assertTrue(formatted.contains(":"))
    }

    @Test
    fun `redactor cuts a URL with a query to scheme plus host`() {
        val message = "playurl https://upos-sz-mirrorcosov.bilivideo.com/upos-bula/x.m4s?e=abc&token=secret done"
        val redacted = LogRedactor.redact(message)
        assertEquals(
            "playurl https://upos-sz-mirrorcosov.bilivideo.com/<redacted> done",
            redacted,
        )
        assertFalse(redacted.contains("token=secret"))
    }

    @Test
    fun `redactor cuts a signed API URL to its host`() {
        val message = "GET https://api.bilibili.com/x/player/playurl?bvid=BV1x&w_rid=deadbeef&wts=123"
        assertEquals(
            "GET https://api.bilibili.com/<redacted>",
            LogRedactor.redact(message),
        )
    }

    @Test
    fun `redactor replaces secret key value pairs`() {
        val message = "Cookie: SESSDATA=abc123; bili_jct=def456; buvid3=ghi789"
        val redacted = LogRedactor.redact(message)
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("def456"))
        assertFalse(redacted.contains("ghi789"))
        assertTrue(redacted.contains("SESSDATA=<redacted>"))
        assertTrue(redacted.contains("bili_jct=<redacted>"))
    }

    @Test
    fun `redactor leaves clean messages untouched`() {
        val message = "search ok count=20 bvid=BV1q4FTeBEMj cid=28139389900 host=cdn.example"
        assertEquals(message, LogRedactor.redact(message))
    }

    @Test
    fun `hostOf extracts the host and only the host`() {
        assertEquals(
            "upos-sz-mirrorcosov.bilivideo.com",
            LogRedactor.hostOf("https://upos-sz-mirrorcosov.bilivideo.com/upos-bula/x.m4s?e=1"),
        )
        assertEquals("cdn.example", LogRedactor.hostOf("https://cdn.example/a.m4s"))
        assertEquals("unknown-host", LogRedactor.hostOf("not a url"))
        assertEquals("unknown-host", LogRedactor.hostOf(""))
    }

    @Test
    fun `logger redacts before buffering and fans out to sinks`() {
        val logger = MusiqueLogger(LogBuffer(capacity = 10, clock = { 1000L }))
        val seen = mutableListOf<String>()
        logger.addSink { line -> seen.add(line.message) }
        logger.i("PlayUrl", "playurl https://cdn.example/x.m4s?token=secret")
        val buffered = logger.buffer.lines().single()
        assertEquals("playurl https://cdn.example/<redacted>", buffered.message)
        assertEquals(listOf("playurl https://cdn.example/<redacted>"), seen)
    }

    @Test
    fun `a throwing sink never breaks logging`() {
        val logger = MusiqueLogger(LogBuffer(capacity = 10, clock = { 1000L }))
        logger.addSink { throw RuntimeException("sink boom") }
        logger.i("T", "still logged")
        assertEquals(1, logger.buffer.lines().size)
    }
}
