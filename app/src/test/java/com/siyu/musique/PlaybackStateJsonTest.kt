package com.siyu.musique

import com.siyu.musique.data.PlaybackStateJson
import com.siyu.musique.data.SavedPlayback
import com.siyu.musique.model.Track
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The saved-playback codec: round-trips, and rejects anything that cannot restore. */
class PlaybackStateJsonTest {

    private fun track(id: String, cid: String) = Track(
        id = id,
        cid = cid,
        title = "T$id",
        artist = "A",
        cover = "https://cover.example/$id.jpg",
        duration = 200.seconds,
    )

    private val trackA = track("BV1a", "cid-a")
    private val trackB = track("BV1b", "cid-b")

    @Test
    fun `encode and decode round-trip`() {
        val state = SavedPlayback(
            queue = listOf(trackA, trackB),
            currentKey = trackB.key,
            positionMs = 45_000L,
        )
        assertEquals(state, PlaybackStateJson.decode(PlaybackStateJson.encode(state)))
    }

    @Test
    fun `play urls are never written`() {
        val withUrl = trackA.copy(playUrl = "https://cdn.example/secret.m4s")
        val encoded = PlaybackStateJson.encode(
            SavedPlayback(listOf(withUrl), withUrl.key, 0L),
        )
        assertEquals(false, encoded.contains("cdn.example"))
        assertEquals("", PlaybackStateJson.decode(encoded)?.queue?.first()?.playUrl)
    }

    @Test
    fun `garbage decodes to null`() {
        assertNull(PlaybackStateJson.decode("not json"))
        assertNull(PlaybackStateJson.decode("[1, 2, 3]"))
        assertNull(PlaybackStateJson.decode("{}"))
    }

    @Test
    fun `a current key outside the queue decodes to null`() {
        val encoded = PlaybackStateJson.encode(
            SavedPlayback(listOf(trackA), currentKey = trackB.key, positionMs = 0L),
        )
        assertNull(PlaybackStateJson.decode(encoded))
    }

    @Test
    fun `a current key naming a part of a queue video decodes`() {
        val videoRow = track("BV1multi", "")
        val encoded = PlaybackStateJson.encode(
            SavedPlayback(
                listOf(videoRow, trackA),
                currentKey = "BV1multi:cid-part-2",
                positionMs = 30_000L,
            ),
        )
        assertEquals(
            SavedPlayback(
                listOf(videoRow, trackA),
                "BV1multi:cid-part-2",
                30_000L,
            ),
            PlaybackStateJson.decode(encoded),
        )
    }

    @Test
    fun `a negative position clamps to zero`() {
        val encoded = PlaybackStateJson.encode(
            SavedPlayback(listOf(trackA), currentKey = trackA.key, positionMs = -500L),
        )
        assertEquals(0L, PlaybackStateJson.decode(encoded)?.positionMs)
    }
}
