package com.siyu.musique.data

import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.model.Track
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Test double for [SearchRepository]. This is the Step 0 fake, kept
 * only as a test double: the production implementation is
 * [BiliSearchRepository]. Its search contract (blank query returns
 * the whole list) is the fake's own behavior for ViewModel tests, not
 * the real repository's contract (blank sends no request).
 */
class FakeSearchRepository : SearchRepository {

    /** How many times [search] was called, for no-request assertions. */
    var searchCalls: Int = 0
        private set

    override suspend fun search(query: String): List<Track> {
        searchCalls++
        // Pretend a network round trip so the loading state is visible.
        delay(300)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return FAKE_TRACKS
        return FAKE_TRACKS.filter {
            it.title.contains(trimmed, ignoreCase = true) ||
                it.artist.contains(trimmed, ignoreCase = true)
        }
    }

    override suspend fun fetchDetail(track: Track): Track = track

    /**
     * A canned 192K stream. The URL is fake and never fetched: the
     * fake engine records it, the real engine never runs in tests.
     * The preference is ignored: the fake has exactly one stream.
     */
    override suspend fun fetchPlayUrl(
        track: Track,
        preference: AudioQuality,
    ): AudioStream =
        AudioStream(
            qualityId = 30280,
            baseUrl = "https://fake-cdn.example/audio-${track.id}-${track.cid}.m4s",
            bandwidth = 216670L,
        )

    companion object {
        val FAKE_TRACKS: List<Track> = listOf(
            Track(
                id = "BV1q4FTeBEMj",
                cid = "28139389900",
                title = "001. 夜曲-周杰伦",
                artist = "周杰伦",
                cover = "",
                duration = 228.seconds,
            ),
            Track(
                id = "BV1FAKE00001",
                cid = "10000000001",
                title = "Demo Track One (fake data)",
                artist = "Demo Artist",
                cover = "",
                duration = 183.seconds,
            ),
            Track(
                id = "BV1FAKE00002",
                cid = "10000000002",
                title = "Demo Track Two (fake data)",
                artist = "Demo Artist",
                cover = "",
                duration = 241.seconds,
            ),
            Track(
                id = "BV1FAKE00003",
                cid = "10000000003",
                title = "Demo Long Track (fake data)",
                artist = "Demo Artist",
                cover = "",
                duration = 3722.seconds,
            ),
            Track(
                id = "BV1FAKE00004",
                cid = "10000000004",
                title = "Demo Short Track (fake data)",
                artist = "Demo Artist",
                cover = "",
                duration = 45.seconds,
            ),
        )
    }
}
