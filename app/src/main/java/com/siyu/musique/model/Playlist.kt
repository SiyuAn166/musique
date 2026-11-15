package com.siyu.musique.model

import java.util.UUID

/**
 * A user playlist: an ordered list of tracks, stored with full track
 * metadata so playback works without re-searching. Playlists are
 * kept in creation order (the default playlist first); the stored
 * list order is the created order, there is no separate order field.
 *
 * One default playlist always exists (see [default]); it cannot be
 * deleted. User-created playlists get a random UUID id. Duplicate
 * names are allowed; identity is the [id], never the name.
 *
 * Tracks are stored without their play URL: play URLs expire, so
 * they are resolved fresh on every play and never persisted. A
 * track's cid may be empty when it was added from search results;
 * playing it resolves the details first, through the existing flow.
 *
 * One more playlist is synthetic, never stored: the Downloads
 * playlist ([DOWNLOADS_ID]), derived from the download records by
 * the ViewModel and shown right after the default playlist. It
 * auto-fills when a song downloads, cannot be deleted, and
 * removing a song from it deletes the downloaded file.
 */
data class Playlist(
    /** Stable identity. [DEFAULT_ID] for the default playlist, a UUID otherwise. */
    val id: String,

    /** Display name, as the user typed it. Blank names are rejected at creation. */
    val name: String,

    /** The tracks, in the order they were added. */
    val tracks: List<Track>,
) {
    /** True only for the default playlist, which cannot be deleted. */
    val isDefault: Boolean
        get() = id == DEFAULT_ID

    /** True only for the synthetic Downloads playlist, which cannot be deleted or edited by hand. */
    val isDownloads: Boolean
        get() = id == DOWNLOADS_ID

    /** The song count the playlist rows show. */
    val trackCount: Int
        get() = tracks.size

    companion object {
        /** The fixed id of the default playlist. */
        const val DEFAULT_ID: String = "default"

        /** The name of the default playlist, created on first launch. */
        const val DEFAULT_NAME: String = "Default Playlist"

        /** The fixed id of the synthetic Downloads playlist. */
        const val DOWNLOADS_ID: String = "downloads"

        /** The name of the synthetic Downloads playlist. */
        const val DOWNLOADS_NAME: String = "Downloads"

        /** The default playlist, empty. Created on first launch. */
        fun default(): Playlist = Playlist(id = DEFAULT_ID, name = DEFAULT_NAME, tracks = emptyList())

        /** The synthetic Downloads playlist holding [tracks], one row per downloaded song. */
        fun downloads(tracks: List<Track>): Playlist =
            Playlist(id = DOWNLOADS_ID, name = DOWNLOADS_NAME, tracks = tracks)

        /** A new user playlist with a random id and no tracks. */
        fun create(name: String): Playlist =
            Playlist(id = UUID.randomUUID().toString(), name = name, tracks = emptyList())
    }
}
