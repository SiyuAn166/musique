package com.siyu.musique.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.siyu.musique.core.network.AudioQuality
import kotlinx.coroutines.flow.first

private val Context.audioQualityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_quality",
)

/**
 * The [QualityPreferenceStore] backed by DataStore preferences,
 * storing the preference's enum name under one key. An unknown
 * stored name maps back to [AudioQuality.BEST] via
 * [AudioQuality.fromStoredName], so a renamed or corrupt value can
 * never break playback. Statically reviewed only: DataStore cannot
 * compile under kotlinc here, the first real compile is the Gradle
 * build.
 */
class DataStoreQualityPreferenceStore(
    private val context: Context,
) : QualityPreferenceStore {

    override suspend fun load(): AudioQuality? {
        val name = context.audioQualityDataStore.data.first()[KEY_QUALITY] ?: return null
        return AudioQuality.fromStoredName(name)
    }

    override suspend fun save(preference: AudioQuality) {
        context.audioQualityDataStore.edit { prefs ->
            prefs[KEY_QUALITY] = preference.name
        }
    }

    companion object {
        private val KEY_QUALITY = stringPreferencesKey("audio_quality")
    }
}
