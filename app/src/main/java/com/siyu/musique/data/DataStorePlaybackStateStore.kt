package com.siyu.musique.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.playbackStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_state",
)

/**
 * The [PlaybackStateStore] backed by DataStore preferences,
 * storing the saved playback as one JSON string under one key.
 * A missing or undecodable value loads as null (fresh start);
 * a failed load never overwrites the store. Statically
 * reviewed only: DataStore cannot compile under kotlinc here,
 * the first real compile is the Gradle build.
 */
class DataStorePlaybackStateStore(
    private val context: Context,
) : PlaybackStateStore {

    override suspend fun load(): SavedPlayback? {
        val raw = context.playbackStateDataStore.data.first()[KEY_STATE] ?: return null
        return PlaybackStateJson.decode(raw)
    }

    override suspend fun save(state: SavedPlayback) {
        context.playbackStateDataStore.edit { prefs ->
            prefs[KEY_STATE] = PlaybackStateJson.encode(state)
        }
    }

    companion object {
        private val KEY_STATE = stringPreferencesKey("playback_state_json")
    }
}
