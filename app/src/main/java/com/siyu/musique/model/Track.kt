package com.siyu.musique.model

import kotlin.time.Duration

/**
 * One Track model for everything, per the v0.1 design.
 *
 * - [id] is the bvid, e.g. "BV1q4FTeBEMj".
 * - [cid] is the part number, kept as a String like the Bilibili API
 *   returns it in JSON (it can exceed Int range). Empty until the
 *   details call fills it in; fill it in before playing.
 * - [duration] is always milliseconds, as a [Duration]. Never mix
 *   seconds in one place and milliseconds in another.
 * - [playUrl] is empty until the stream URL call fills it in. Play
 *   URLs expire, so they are fetched fresh on every play and never
 *   stored long term.
 */
data class Track(
    val id: String,
    val cid: String,
    val title: String,
    val artist: String,
    val cover: String,
    val duration: Duration,
    val playUrl: String = "",
) {
    /**
     * Unique key for a track part. Two parts of the same video share
     * the bvid but never the cid, so bvid alone is not unique.
     */
    val key: String
        get() = "$id:$cid"

    /**
     * Whether [other] is the same song, not just the same part:
     * same video, and the parts match when both are known. A track
     * whose cid is still empty (straight from search results)
     * stands for the whole video, so it matches any part of the
     * same video. This is the dedupe rule for playlists and the
     * already-current check for taps: the same video added before
     * and after its cid was filled in must count as one song.
     */
    fun isSameSongAs(other: Track): Boolean =
        id == other.id && (cid.isEmpty() || other.cid.isEmpty() || cid == other.cid)
}
