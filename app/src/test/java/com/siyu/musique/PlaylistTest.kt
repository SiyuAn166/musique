package com.siyu.musique

import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.PlaylistJson
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playlist model, its JSON codec, and the in-memory store. */
class PlaylistTest {

    private fun track(
        id: String = "BV1q4FTeBEMj",
        cid: String = "28139389900",
        playUrl: String = "",
    ) = Track(
        id = id,
        cid = cid,
        title = "Night Song",
        artist = "Singer",
        cover = "https://cover.example/1.jpg",
        duration = 228.seconds,
        playUrl = playUrl,
    )

    @Test
    fun `default playlist has the fixed id and name`() {
        val default = Playlist.default()
        assertEquals(Playlist.DEFAULT_ID, default.id)
        assertEquals("Default Playlist", default.name)
        assertTrue(default.isDefault)
        assertTrue(default.tracks.isEmpty())
        assertEquals(0, default.trackCount)
    }

    @Test
    fun `created playlists get unique ids and are not the default`() {
        val first = Playlist.create("Favorites")
        val second = Playlist.create("Favorites")
        assertEquals("Favorites", first.name)
        assertNotEquals(first.id, second.id)
        assertFalse(first.isDefault)
        assertFalse(second.isDefault)
    }

    @Test
    fun `encode then decode round-trips playlists and tracks`() {
        val playlists = listOf(
            Playlist.default(),
            Playlist(
                id = "abc-123",
                name = "Late Night",
                tracks = listOf(
                    track(),
                    track(id = "BV1EMPTY0001", cid = ""),
                ),
            ),
        )
        val decoded = PlaylistJson.decode(PlaylistJson.encode(playlists))
        assertEquals(playlists, decoded)
    }

    @Test
    fun `durations survive the round trip in milliseconds`() {
        val playlist = Playlist(
            id = "x",
            name = "Durations",
            tracks = listOf(
                track().copy(duration = 0.milliseconds),
                track(id = "BV1LONG00001").copy(duration = 25_140_000.milliseconds),
            ),
        )
        val decoded = PlaylistJson.decode(PlaylistJson.encode(listOf(playlist)))
        assertEquals(listOf(playlist), decoded)
    }

    @Test
    fun `play URLs are never stored`() {
        val playlist = Playlist(
            id = "x",
            name = "Urls",
            tracks = listOf(track(playUrl = "https://cdn.example/secret.m4s?token=abc")),
        )
        val encoded = PlaylistJson.encode(listOf(playlist))
        assertFalse(encoded.contains("cdn.example"))
        val decoded = PlaylistJson.decode(encoded)
        assertEquals("", decoded?.single()?.tracks?.single()?.playUrl)
    }

    @Test
    fun `decode returns null for values that are not a JSON array`() {
        assertNull(PlaylistJson.decode(""))
        assertNull(PlaylistJson.decode("{not json"))
        assertNull(PlaylistJson.decode("null"))
        assertNull(PlaylistJson.decode("{}"))
        assertNull(PlaylistJson.decode("123"))
        assertNull(PlaylistJson.decode("\"hello\""))
    }

    @Test
    fun `decode of an empty array is an empty list`() {
        assertEquals(emptyList<Playlist>(), PlaylistJson.decode("[]"))
        assertEquals(emptyList<Playlist>(), PlaylistJson.decode(PlaylistJson.encode(emptyList())))
    }

    @Test
    fun `decode skips entries missing an id name or bvid and defaults missing fields`() {
        val raw = """[
            {"name": "No id", "tracks": []},
            {"id": "no-name", "tracks": []},
            {"id": "ok", "name": "Kept", "tracks": [
                {"title": "No bvid"},
                {"bvid": "BV1MINIMAL01"},
                {"bvid": "BV1FULL00001", "cid": "7", "title": "T",
                 "artist": "A", "cover": "C", "durationMs": 61000}
            ]},
            {"id": "no-tracks", "name": "No tracks field"}
        ]"""
        val decoded = PlaylistJson.decode(raw)
        assertEquals(listOf("ok", "no-tracks"), decoded?.map { it.id })
        val kept = decoded?.first()
        assertEquals(
            listOf(
                Track("BV1MINIMAL01", "", "", "", "", 0.milliseconds),
                Track("BV1FULL00001", "7", "T", "A", "C", 61.seconds),
            ),
            kept?.tracks,
        )
        assertEquals(emptyList<Track>(), decoded?.last()?.tracks)
    }

    @Test
    fun `decode ignores unknown fields`() {
        val raw = """[{"id": "x", "name": "N", "future": true,
            "tracks": [{"bvid": "BV1X", "extra": 1}]}]"""
        val decoded = PlaylistJson.decode(raw)
        assertEquals(
            listOf(Playlist("x", "N", listOf(Track("BV1X", "", "", "", "", 0.milliseconds)))),
            decoded,
        )
    }

    @Test
    fun `in-memory store loads null before anything is saved`() = runTest {
        val store = InMemoryPlaylistStore()
        assertNull(store.load())
    }

    @Test
    fun `in-memory store round-trips a saved list`() = runTest {
        val store = InMemoryPlaylistStore()
        val playlists = listOf(Playlist.default(), Playlist.create("Mix"))
        store.save(playlists)
        assertEquals(playlists, store.load())
    }

    @Test
    fun `in-memory store save replaces the previous value`() = runTest {
        val store = InMemoryPlaylistStore(listOf(Playlist.default()))
        store.save(emptyList())
        assertEquals(emptyList<Playlist>(), store.load())
    }
}
