package com.siyu.musique.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.downloadDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "downloads",
)

/**
 * The [DownloadStore] backed by DataStore preferences, storing the
 * whole record list as one JSON string ([DownloadJson]) under one
 * key. An unreadable stored string decodes to null, and the caller
 * starts with an empty list, so a corrupt value can never break
 * the downloads screen. Statically reviewed only: DataStore
 * cannot compile under kotlinc here, the first real compile is the
 * Gradle build.
 */
class DataStoreDownloadStore(
    private val context: Context,
) : DownloadStore {

    override suspend fun load(): List<DownloadRecord>? {
        val raw = context.downloadDataStore.data.first()[KEY_DOWNLOADS] ?: return null
        return DownloadJson.decode(raw)
    }

    override suspend fun save(records: List<DownloadRecord>) {
        context.downloadDataStore.edit { prefs ->
            prefs[KEY_DOWNLOADS] = DownloadJson.encode(records)
        }
    }

    companion object {
        private val KEY_DOWNLOADS = stringPreferencesKey("downloads_json")
    }
}
