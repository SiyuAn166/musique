package com.siyu.musique.data

import com.siyu.musique.model.Track

/**
 * The playback to resume after a restart: the queue, the current
 * track's key (bvid:cid), and the position in milliseconds. Play
 * URLs are never stored: they expire, so the resumed track
 * resolves a fresh URL when the user presses play. The resumed
 * state always starts paused, never autoplaying.
 */
data class SavedPlayback(
    val queue: List<Track>,
    val currentKey: String,
    val positionMs: Long,
)

/** Persists [SavedPlayback]. One value, overwritten on every save. */
interface PlaybackStateStore {
    /** The saved playback, or null when nothing was saved yet or the stored value is unusable. */
    suspend fun load(): SavedPlayback?

    /** Overwrites the saved playback. */
    suspend fun save(state: SavedPlayback)
}

/** An in-memory [PlaybackStateStore]. The ViewModel's default, so tests that do not care about restore keep working. */
class InMemoryPlaybackStateStore(
    initial: SavedPlayback? = null,
) : PlaybackStateStore {
    private var stored: SavedPlayback? = initial

    override suspend fun load(): SavedPlayback? = stored

    override suspend fun save(state: SavedPlayback) {
        stored = state
    }
}
