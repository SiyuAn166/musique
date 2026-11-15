package com.siyu.musique

import com.siyu.musique.model.formatDuration
import com.siyu.musique.model.parseDurationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationFormatTest {

    @Test
    fun `zero formats as 0 00`() {
        assertEquals("0:00", formatDuration(0.seconds))
    }

    @Test
    fun `seconds under a minute pad to two digits`() {
        assertEquals("0:05", formatDuration(5.seconds))
        assertEquals("0:45", formatDuration(45.seconds))
    }

    @Test
    fun `228 seconds formats as 3 48`() {
        assertEquals("3:48", formatDuration(228.seconds))
    }

    @Test
    fun `exactly one minute formats as 1 00`() {
        assertEquals("1:00", formatDuration(60.seconds))
    }

    @Test
    fun `minutes are not capped at 59`() {
        // Smoke-test durations: search reported "419:2" and "829:59".
        assertEquals("419:02", formatDuration(419.minutes + 2.seconds))
        assertEquals("829:59", formatDuration(829.minutes + 59.seconds))
    }

    @Test
    fun `one hour or more still shows total minutes, like Bilibili`() {
        // No hour rollover: Bilibili reports long durations as total
        // minutes ("419:2"), so 60 minutes shows as 60:00, not 1:00:00.
        assertEquals("60:00", formatDuration(3600.seconds))
        assertEquals("65:09", formatDuration(3909.seconds))
    }

    @Test
    fun `milliseconds are truncated, not rounded`() {
        // 90.9 seconds shows as 1:30, not 1:31.
        assertEquals("1:30", formatDuration(90.seconds + 900.milliseconds))
    }

    @Test
    fun `negative durations clamp to zero`() {
        assertEquals("0:00", formatDuration((-5).seconds))
    }

    @Test
    fun `parse handles total-minutes text with minutes over 60`() {
        // Smoke-test durations: search reported "419:2" and "829:59".
        assertEquals(419.minutes + 2.seconds, parseDurationText("419:2"))
        assertEquals(829.minutes + 59.seconds, parseDurationText("829:59"))
    }

    @Test
    fun `parse handles ordinary durations and single-digit seconds`() {
        assertEquals(3.minutes + 48.seconds, parseDurationText("3:48"))
        assertEquals(5.seconds, parseDurationText("0:05"))
        assertEquals(222.minutes + 28.seconds, parseDurationText("222:28"))
    }

    @Test
    fun `parse and format round-trip`() {
        assertEquals("419:02", formatDuration(parseDurationText("419:2")!!))
        assertEquals("3:48", formatDuration(parseDurationText("3:48")!!))
    }

    @Test
    fun `parse returns null for blank or malformed text`() {
        assertNull(parseDurationText(""))
        assertNull(parseDurationText("   "))
        assertNull(parseDurationText("abc"))
        assertNull(parseDurationText("12"))
        assertNull(parseDurationText("1:2:3:4"))
        assertNull(parseDurationText("-1:30"))
    }
}
