package com.siyu.musique.data

import com.siyu.musique.model.Playlist

/**
 * Persistence for the playlist list: one stored value, the whole
 * list in creation order. The production implementation is DataStore
 * ([DataStorePlaylistStore], one JSON string via [PlaylistJson]);
 * tests and runs without persistence use [InMemoryPlaylistStore]. A
 * missing value means "never saved", and callers bootstrap the
 * default playlist.
 */
interface PlaylistStore {
    /** The stored playlists in creation order, or null when nothing was ever saved. */
    suspend fun load(): List<Playlist>?

    /** Persists [playlists], replacing any previous value. */
    suspend fun save(playlists: List<Playlist>)
}

/** In-memory store for tests and for runs without persistence. */
class InMemoryPlaylistStore(
    initial: List<Playlist>? = null,
) : PlaylistStore {
    private var stored: List<Playlist>? = initial

    override suspend fun load(): List<Playlist>? = stored

    override suspend fun save(playlists: List<Playlist>) {
        stored = playlists
    }
}
