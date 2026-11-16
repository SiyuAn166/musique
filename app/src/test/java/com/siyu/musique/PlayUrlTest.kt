package com.siyu.musique

import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.WbiKeyStore
import com.siyu.musique.core.network.parsePlayUrl
import com.siyu.musique.core.network.pickAudioStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlayUrlTest {

    companion object {
        // Fixture shaped like the live Nov 14, 2026 probe: dash.audio
        // with the three real quality ids, in server order (not
        // preference order), plus bandwidth/codecid as observed.
        // The URLs are fake; only the shape and ids are real.
        const val PLAYURL_DATA = """{"accept_quality":[127,126,125,120,116,112,80,64,32,16],"dash":{"duration":228,"audio":[{"id":30216,"baseUrl":"https://upos-sz-mirrorcosov.bilivideo.com/upos-bula/fake64.m4s?e=1&token=fake","bandwidth":57574,"codecid":0,"mimeType":"audio/mp4"},{"id":30232,"baseUrl":"https://upos-sz-mirrorcosov.bilivideo.com/upos-bula/fake132.m4s?e=1&token=fake","bandwidth":110733,"codecid":0,"mimeType":"audio/mp4"},{"id":30280,"baseUrl":"https://upos-sz-mirrorcosov.bilivideo.com/upos-bula/fake192.m4s?e=1&token=fake","bandwidth":216670,"codecid":0,"mimeType":"audio/mp4"}],"video":[],"flac":{"audio":null},"dolby":{"audio":null}},"durl":[]}"""
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun service(gateway: FakeGateway): ApiService = ApiService(
        BiliClient(
            gateway = gateway,
            cookieJar = CookieJar(),
            wbiKeys = WbiKeyStore(),
            sleeper = {},
            jitter = { 0.5 },
            nowSeconds = { 1794710448L },
        ),
    )

    private fun audioData(vararg streams: String): String =
        """{"dash":{"audio":[${streams.joinToString(",")} ]}}"""

    private fun stream(id: Int, url: String = "https://cdn.example/$id.m4s"): String =
        """{"id":$id,"baseUrl":"$url","bandwidth":1000,"codecid":0}"""

    @Test
    fun `parse picks 30280 when all three qualities are present`() {
        val picked = parsePlayUrl(json.parseToJsonElement(PLAYURL_DATA))
        assertEquals(30280, picked.qualityId)
        assertTrue(picked.baseUrl.contains("fake192.m4s"))
        assertEquals(216670L, picked.bandwidth)
    }

    @Test
    fun `pick order is 30280 then 30232 then 30216 regardless of server order`() {
        val streams = listOf(
            AudioStream(30216, "https://cdn.example/64", 1),
            AudioStream(30232, "https://cdn.example/132", 1),
            AudioStream(30280, "https://cdn.example/192", 1),
        )
        assertEquals(30280, pickAudioStream(streams)?.qualityId)
        assertEquals(30280, pickAudioStream(streams.reversed())?.qualityId)
        assertEquals(30232, pickAudioStream(streams.take(2))?.qualityId)
        assertEquals(30216, pickAudioStream(streams.take(1))?.qualityId)
    }

    @Test
    fun `best picks hi-res first, then dolby, when present`() {
        val streams = listOf(
            AudioStream(30250, "https://cdn.example/dolby", 1),
            AudioStream(30251, "https://cdn.example/hires", 1),
            AudioStream(30280, "https://cdn.example/192", 1),
        )
        assertEquals(30251, pickAudioStream(streams)?.qualityId)
        assertEquals(30250, pickAudioStream(streams.take(1))?.qualityId)
    }

    @Test
    fun `explicit 192K never picks dolby or hi-res, falling downward only`() {
        val streams = listOf(
            AudioStream(30250, "https://cdn.example/dolby", 1),
            AudioStream(30251, "https://cdn.example/hires", 1),
            AudioStream(30216, "https://cdn.example/64", 1),
        )
        assertEquals(30216, pickAudioStream(streams, AudioQuality.Q192)?.qualityId)
        assertNull(pickAudioStream(streams.take(2), AudioQuality.Q192))
    }

    @Test
    fun `parse falls back to 30232 when 30280 is missing`() {
        val data = audioData(stream(30216), stream(30232))
        assertEquals(30232, parsePlayUrl(json.parseToJsonElement(data)).qualityId)
    }

    @Test
    fun `parse throws when only dolby and hi-res are present and 192K is picked`() {
        val data = audioData(stream(30250), stream(30251))
        try {
            parsePlayUrl(json.parseToJsonElement(data), AudioQuality.Q192)
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected: an explicit choice never falls upward.
        }
        // Best accepts them: Hi-Res wins.
        assertEquals(30251, parsePlayUrl(json.parseToJsonElement(data)).qualityId)
    }

    @Test
    fun `parse throws when the audio array is empty`() {
        try {
            parsePlayUrl(json.parseToJsonElement("""{"dash":{"audio":[]}}"""))
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected
        }
    }

    @Test
    fun `parse throws when dash is missing`() {
        try {
            parsePlayUrl(json.parseToJsonElement("""{"durl":[]}"""))
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected
        }
    }

    @Test
    fun `parse throws when dash has no audio array`() {
        try {
            parsePlayUrl(json.parseToJsonElement("""{"dash":{"video":[]}}"""))
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected
        }
    }

    @Test
    fun `parse skips streams without an id or an empty baseUrl`() {
        val data = """{"dash":{"audio":[{"id":30280,"baseUrl":""},{"baseUrl":"https://cdn.example/no-id.m4s"},{"id":30216,"baseUrl":"https://cdn.example/64.m4s","bandwidth":57574}]}}"""
        val picked = parsePlayUrl(json.parseToJsonElement(data))
        assertEquals(30216, picked.qualityId)
    }

    @Test
    fun `fetchPlayUrl sends bvid cid fnval unsigned, no qn`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, PLAYURL_DATA))
        val stream = service(gateway).fetchPlayUrl("BV1q4FTeBEMj", "28139389900")
        assertEquals(30280, stream.qualityId)
        val url = gateway.requests.single().url
        assertTrue(
            url.startsWith("https://api.bilibili.com/x/player/playurl?bvid=BV1q4FTeBEMj&cid=28139389900&fnval=4048"),
        )
        assertTrue(!url.contains("w_rid"))
        assertTrue(!url.contains("qn="))
    }

    @Test
    fun `fetchPlayUrl surfaces an empty audio array as a parse error`() = runTest {
        val gateway = FakeGateway()
        gateway.enqueue(envelopeResponse(0, """{"dash":{"audio":[]}}"""))
        try {
            service(gateway).fetchPlayUrl("BV1q4FTeBEMj", "28139389900")
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected
        }
    }

    @Test
    fun `backup_url is kept in order for the picked stream`() {
        val data = audioData(
            """{"id":30280,"baseUrl":"https://cdn.example/primary.m4s","bandwidth":1000,"backup_url":["https://cdn.example/backup1.m4s","https://cdn.example/backup2.m4s"]}""",
        )
        val picked = parsePlayUrl(json.parseToJsonElement(data))
        assertEquals(
            listOf("https://cdn.example/backup1.m4s", "https://cdn.example/backup2.m4s"),
            picked.backupUrls,
        )
    }

    @Test
    fun `missing backup_url yields an empty backup list`() {
        val picked = parsePlayUrl(json.parseToJsonElement(audioData(stream(30280))))
        assertTrue(picked.backupUrls.isEmpty())
    }

    @Test
    fun `blank entries duplicates of baseUrl and repeats are dropped from backup_url`() {
        val data = audioData(
            """{"id":30280,"baseUrl":"https://cdn.example/primary.m4s","bandwidth":1000,"backup_url":["","https://cdn.example/primary.m4s","https://cdn.example/backup1.m4s","https://cdn.example/backup1.m4s","  "]}""",
        )
        val picked = parsePlayUrl(json.parseToJsonElement(data))
        assertEquals(listOf("https://cdn.example/backup1.m4s"), picked.backupUrls)
    }

    @Test
    fun `backup_url of a non-picked stream is ignored`() {
        val data = audioData(
            """{"id":30216,"baseUrl":"https://cdn.example/64.m4s","bandwidth":1000,"backup_url":["https://cdn.example/64backup.m4s"]}""",
            """{"id":30280,"baseUrl":"https://cdn.example/192.m4s","bandwidth":1000,"backup_url":["https://cdn.example/192backup.m4s"]}""",
        )
        val picked = parsePlayUrl(json.parseToJsonElement(data))
        assertEquals(30280, picked.qualityId)
        assertEquals(listOf("https://cdn.example/192backup.m4s"), picked.backupUrls)
    }
}
