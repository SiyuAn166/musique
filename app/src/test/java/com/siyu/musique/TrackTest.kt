package com.siyu.musique

import com.siyu.musique.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TrackTest {

    private fun track(id: String = "BV1q4FTeBEMj", cid: String = "28139389900") =
        Track(
            id = id,
            cid = cid,
            title = "001. 夜曲-周杰伦",
            artist = "周杰伦",
            cover = "",
            duration = 228.seconds,
        )

    @Test
    fun `key combines bvid and cid`() {
        assertEquals("BV1q4FTeBEMj:28139389900", track().key)
    }

    @Test
    fun `two parts of the same video have different keys`() {
        val part1 = track(cid = "28139389900")
        val part2 = track(cid = "28139390958")
        assertNotEquals(part1.key, part2.key)
    }

    @Test
    fun `same bvid and cid give the same key`() {
        assertEquals(track().key, track().key)
    }

    @Test
    fun `cid may be empty before the details call fills it in`() {
        val track = track(cid = "")
        assertEquals("BV1q4FTeBEMj:", track.key)
        assertEquals("", track.cid)
    }

    @Test
    fun `duration is stored in milliseconds`() {
        // 228 seconds must be 228_000 milliseconds, never 228.
        assertEquals(228_000L, track().duration.inWholeMilliseconds)
    }

    @Test
    fun `playUrl defaults to empty`() {
        assertEquals("", track().playUrl)
    }
}
