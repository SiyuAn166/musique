package com.siyu.musique

import com.siyu.musique.data.ProgressReportGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressReportGateTest {

    @Test
    fun `first report always goes through`() {
        val gate = ProgressReportGate(100L) { 1000L }
        assertTrue(gate.shouldReport())
    }

    @Test
    fun `reports inside the interval are suppressed`() {
        var now = 1000L
        val gate = ProgressReportGate(100L) { now }
        assertTrue(gate.shouldReport())
        now = 1050L
        assertFalse(gate.shouldReport())
        now = 1099L
        assertFalse(gate.shouldReport())
    }

    @Test
    fun `reports at or after the interval go through`() {
        var now = 1000L
        val gate = ProgressReportGate(100L) { now }
        assertTrue(gate.shouldReport())
        now = 1100L
        assertTrue(gate.shouldReport())
        now = 1150L
        assertFalse(gate.shouldReport())
        now = 1200L
        assertTrue(gate.shouldReport())
    }
}
