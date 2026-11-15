package com.siyu.musique

import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.core.network.pickAudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioQualityTest {

    private fun streams(vararg ids: Int): List<AudioStream> =
        ids.map { AudioStream(it, "https://cdn.example/$it.m4s", 1L) }

    @Test
    fun `best tries hi-res dolby 192K 132K 64K in that order`() {
        assertEquals(
            listOf(30251, 30250, 30280, 30232, 30216),
            AudioQuality.BEST.tryOrder,
        )
    }

    @Test
    fun `explicit choices try their id then fall downward only`() {
        assertEquals(listOf(30280, 30232, 30216), AudioQuality.Q192.tryOrder)
        assertEquals(listOf(30232, 30216), AudioQuality.Q132.tryOrder)
        assertEquals(listOf(30216), AudioQuality.Q64.tryOrder)
    }

    @Test
    fun `labels are Best 192K 132K 64K`() {
        assertEquals("Best", AudioQuality.BEST.label)
        assertEquals("192K", AudioQuality.Q192.label)
        assertEquals("132K", AudioQuality.Q132.label)
        assertEquals("64K", AudioQuality.Q64.label)
    }

    @Test
    fun `best walks the full order regardless of server order`() {
        val all = streams(30216, 30232, 30280, 30250, 30251)
        assertEquals(30251, pickAudioStream(all, AudioQuality.BEST)?.qualityId)
        assertEquals(30251, pickAudioStream(all.reversed(), AudioQuality.BEST)?.qualityId)
        assertEquals(30250, pickAudioStream(all.take(4), AudioQuality.BEST)?.qualityId)
        assertEquals(30280, pickAudioStream(all.take(3), AudioQuality.BEST)?.qualityId)
        assertEquals(30232, pickAudioStream(all.take(2), AudioQuality.BEST)?.qualityId)
        assertEquals(30216, pickAudioStream(all.take(1), AudioQuality.BEST)?.qualityId)
    }

    @Test
    fun `192K falls to 132K then 64K and never upward`() {
        assertEquals(
            30280,
            pickAudioStream(streams(30251, 30250, 30280), AudioQuality.Q192)?.qualityId,
        )
        assertEquals(
            30232,
            pickAudioStream(streams(30251, 30250, 30232), AudioQuality.Q192)?.qualityId,
        )
        assertEquals(
            30216,
            pickAudioStream(streams(30251, 30216), AudioQuality.Q192)?.qualityId,
        )
        assertNull(pickAudioStream(streams(30251, 30250), AudioQuality.Q192))
    }

    @Test
    fun `132K falls to 64K and never upward`() {
        assertEquals(
            30232,
            pickAudioStream(streams(30280, 30232), AudioQuality.Q132)?.qualityId,
        )
        assertEquals(
            30216,
            pickAudioStream(streams(30280, 30216), AudioQuality.Q132)?.qualityId,
        )
        assertNull(pickAudioStream(streams(30280, 30250, 30251), AudioQuality.Q132))
    }

    @Test
    fun `64K picks only 64K`() {
        assertEquals(30216, pickAudioStream(streams(30280, 30216), AudioQuality.Q64)?.qualityId)
        assertNull(pickAudioStream(streams(30280, 30232), AudioQuality.Q64))
    }

    @Test
    fun `pick returns null when no stream matches`() {
        assertNull(pickAudioStream(emptyList(), AudioQuality.BEST))
        assertNull(pickAudioStream(streams(99999), AudioQuality.BEST))
    }

    @Test
    fun `stream ids map to badge labels and unknown ids map to null`() {
        assertEquals("64K", AudioQuality.labelForStreamId(30216))
        assertEquals("132K", AudioQuality.labelForStreamId(30232))
        assertEquals("192K", AudioQuality.labelForStreamId(30280))
        assertEquals("Dolby", AudioQuality.labelForStreamId(30250))
        assertEquals("Hi-Res", AudioQuality.labelForStreamId(30251))
        assertNull(AudioQuality.labelForStreamId(99999))
    }

    @Test
    fun `stored names round-trip and unknown names mean best`() {
        for (quality in AudioQuality.entries) {
            assertEquals(quality, AudioQuality.fromStoredName(quality.name))
        }
        assertEquals(AudioQuality.BEST, AudioQuality.fromStoredName(null))
        assertEquals(AudioQuality.BEST, AudioQuality.fromStoredName(""))
        assertEquals(AudioQuality.BEST, AudioQuality.fromStoredName("DOLBY_PLUS"))
    }
}
