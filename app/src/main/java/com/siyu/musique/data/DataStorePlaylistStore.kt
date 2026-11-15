package com.siyu.musique.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.siyu.musique.model.Playlist
import kotlinx.coroutines.flow.first

private val Context.playlistDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playlists",
)

/**
 * The [PlaylistStore] backed by DataStore preferences, storing the
 * whole playlist list as one JSON string ([PlaylistJson]) under one
 * key. An unreadable stored string decodes to null, and the caller
 * bootstraps the default playlist, so a corrupt value can never
 * break the playlists screen. Statically reviewed only: DataStore
 * cannot compile under kotlinc here, the first real compile is the
 * Gradle build.
 */
class DataStorePlaylistStore(
    private val context: Context,
) : PlaylistStore {

    override suspend fun load(): List<Playlist>? {
        val raw = context.playlistDataStore.data.first()[KEY_PLAYLISTS] ?: return null
        return PlaylistJson.decode(raw)
    }

    override suspend fun save(playlists: List<Playlist>) {
        context.playlistDataStore.edit { prefs ->
            prefs[KEY_PLAYLISTS] = PlaylistJson.encode(playlists)
        }
    }

    companion object {
        private val KEY_PLAYLISTS = stringPreferencesKey("playlists_json")
    }
}
