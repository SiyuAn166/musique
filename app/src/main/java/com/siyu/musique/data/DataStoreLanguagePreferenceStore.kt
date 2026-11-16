package com.siyu.musique.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.languageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "language",
)

/**
 * The [LanguagePreferenceStore] backed by DataStore preferences,
 * storing the preference's enum name under one key. An unknown
 * stored name maps back to [LanguagePreference.SYSTEM] via
 * [LanguagePreference.fromStoredName], so a renamed or corrupt
 * value can never break startup. Statically reviewed only:
 * DataStore cannot compile under kotlinc here, the first real
 * compile is the Gradle build.
 */
class DataStoreLanguagePreferenceStore(
    private val context: Context,
) : LanguagePreferenceStore {

    override suspend fun load(): LanguagePreference? {
        val name = context.languageDataStore.data.first()[KEY_LANGUAGE] ?: return null
        return LanguagePreference.fromStoredName(name)
    }

    override suspend fun save(preference: LanguagePreference) {
        context.languageDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = preference.name
        }
    }

    companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("language")
    }
}
