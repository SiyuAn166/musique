package com.siyu.musique.data

import com.siyu.musique.model.RepeatMode

/** The repeat mode plus the shuffle toggle, persisted as one value. */
data class PlaybackMode(
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
) {
    companion object {
        /** Fresh-install default: sequential play, no shuffle. */
        val DEFAULT = PlaybackMode(RepeatMode.OFF, shuffleEnabled = false)
    }
}

/**
 * Persistence for the playback mode. The production implementation
 * is DataStore ([DataStorePlaybackModeStore]); tests and runs
 * without persistence use [InMemoryPlaybackModeStore]. A missing
 * value means "never chosen", and callers fall back to
 * [PlaybackMode.DEFAULT].
 */
interface PlaybackModeStore {
    /** The stored mode, or null when nothing was ever saved. */
    suspend fun load(): PlaybackMode?

    /** Persists [mode], replacing any previous value. */
    suspend fun save(mode: PlaybackMode)
}

/** In-memory store for tests and for runs without persistence. */
class InMemoryPlaybackModeStore(
    initial: PlaybackMode? = null,
) : PlaybackModeStore {
    private var stored: PlaybackMode? = initial

    override suspend fun load(): PlaybackMode? = stored

    override suspend fun save(mode: PlaybackMode) {
        stored = mode
    }
}
