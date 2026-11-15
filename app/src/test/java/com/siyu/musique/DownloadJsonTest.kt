package com.siyu.musique

import com.siyu.musique.data.DownloadJson
import com.siyu.musique.data.DownloadRecord
import com.siyu.musique.model.Track
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download records codec: round-trips, lenient decoding of
 * corrupt entries, and the rule that play URLs are never
 * written (they expire, and a downloaded file needs no URL).
 */
class DownloadJsonTest {

    private fun track(id: String, cid: String) = Track(
        id = id,
        cid = cid,
        title = "Song $id",
        artist = "Singer",
        cover = "https://cover.example/$id.jpg",
        duration = 200.seconds,
    )

    private fun record(track: Track, qualityId: Int) = DownloadRecord(
        track = track,
        qualityId = qualityId,
        fileName = "${track.id}_${track.cid}_$qualityId.m4a",
        sizeBytes = 12345L,
        downloadedAtMs = 1700000000000L,
    )

    @Test
    fun `round trip preserves records in order`() {
        val records = listOf(
            record(track("BV1a", "111"), 30280),
            record(track("BV1b", "222"), 30216),
        )
        val decoded = DownloadJson.decode(DownloadJson.encode(records))
        assertEquals(records, decoded)
    }

    @Test
    fun `empty list round trips`() {
        assertEquals(emptyList<DownloadRecord>(), DownloadJson.decode(DownloadJson.encode(emptyList())))
    }

    @Test
    fun `play urls are never written`() {
        val withUrl = record(
            track("BV1a", "111").copy(playUrl = "https://cdn.example/secret.m4s?token=abc"),
            30280,
        )
        val encoded = DownloadJson.encode(listOf(withUrl))
        assertFalse(encoded.contains("cdn.example"))
        assertFalse(encoded.contains("token=abc"))
        val decoded = DownloadJson.decode(encoded)
        assertEquals("", decoded?.single()?.track?.playUrl)
    }

    @Test
    fun `lenient decode skips entries missing a track quality or file name`() {
        val raw = """
            [
              {"track": {"bvid": "BV1a", "cid": "111", "title": "A", "artist": "", "cover": "", "durationMs": 1000}, "qualityId": 30280, "fileName": "BV1a_111_30280.m4a", "sizeBytes": 10, "downloadedAtMs": 5},
              {"track": {"bvid": "BV1b", "cid": "222", "title": "B", "artist": "", "cover": "", "durationMs": 1000}, "fileName": "BV1b_222_30280.m4a"},
              {"track": {"bvid": "BV1c", "cid": "333", "title": "C", "artist": "", "cover": "", "durationMs": 1000}, "qualityId": 30280},
              {"qualityId": 30280, "fileName": "nobody.m4a"},
              {"track": {"cid": "444", "title": "D"}, "qualityId": 30280, "fileName": "BV1d_444_30280.m4a"},
              "not an object",
              {"track": {"bvid": "BV1e", "cid": "555", "title": "E", "artist": "", "cover": "", "durationMs": 1000}, "qualityId": 30216, "fileName": "BV1e_555_30216.m4a"}
            ]
        """.trimIndent()
        val decoded = DownloadJson.decode(raw)
        assertEquals(
            listOf("BV1a", "BV1e"),
            decoded?.map { it.track.id },
        )
        // Missing size and time default to zero.
        assertEquals(0L, decoded?.last()?.sizeBytes)
        assertEquals(0L, decoded?.last()?.downloadedAtMs)
    }

    @Test
    fun `non array and garbage decode to null`() {
        assertNull(DownloadJson.decode("{}"))
        assertNull(DownloadJson.decode("not json at all"))
        assertNull(DownloadJson.decode(""))
    }

    @Test
    fun `negative size and time clamp to zero`() {
        val raw = """
            [{"track": {"bvid": "BV1a", "cid": "111", "title": "A", "artist": "", "cover": "", "durationMs": 1000}, "qualityId": 30280, "fileName": "BV1a_111_30280.m4a", "sizeBytes": -5, "downloadedAtMs": -9}]
        """.trimIndent()
        val decoded = DownloadJson.decode(raw)
        assertEquals(0L, decoded?.single()?.sizeBytes)
        assertEquals(0L, decoded?.single()?.downloadedAtMs)
    }

    @Test
    fun `decoded tracks keep their metadata`() {
        val decoded = DownloadJson.decode(
            DownloadJson.encode(listOf(record(track("BV1a", "111"), 30280))),
        )
        val decodedTrack = decoded?.single()?.track
        assertEquals("Song BV1a", decodedTrack?.title)
        assertEquals("Singer", decodedTrack?.artist)
        assertEquals("https://cover.example/BV1a.jpg", decodedTrack?.cover)
        assertEquals(200_000L, decodedTrack?.duration?.inWholeMilliseconds)
        assertTrue(decoded?.single()?.downloadedAtMs == 1700000000000L)
    }
}
