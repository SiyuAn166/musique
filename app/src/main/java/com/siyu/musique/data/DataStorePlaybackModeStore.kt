package com.siyu.musique.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.siyu.musique.model.RepeatMode
import kotlinx.coroutines.flow.first

private val Context.playbackModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_mode",
)

/**
 * The [PlaybackModeStore] backed by DataStore preferences: the
 * repeat mode's enum name under one key, the shuffle flag under
 * another. An unknown stored mode name maps back to
 * [RepeatMode.OFF] via [RepeatMode.fromStoredName], so a renamed or
 * corrupt value can never break playback. Statically reviewed
 * only: DataStore cannot compile under kotlinc here, the first
 * real compile is the Gradle build.
 */
class DataStorePlaybackModeStore(
    private val context: Context,
) : PlaybackModeStore {

    override suspend fun load(): PlaybackMode? {
        val prefs = context.playbackModeDataStore.data.first()
        val modeName = prefs[KEY_REPEAT_MODE] ?: return null
        return PlaybackMode(
            repeatMode = RepeatMode.fromStoredName(modeName),
            shuffleEnabled = prefs[KEY_SHUFFLE_ENABLED] ?: false,
        )
    }

    override suspend fun save(mode: PlaybackMode) {
        context.playbackModeDataStore.edit { prefs ->
            prefs[KEY_REPEAT_MODE] = mode.repeatMode.name
            prefs[KEY_SHUFFLE_ENABLED] = mode.shuffleEnabled
        }
    }

    companion object {
        private val KEY_REPEAT_MODE = stringPreferencesKey("repeat_mode")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
    }
}
