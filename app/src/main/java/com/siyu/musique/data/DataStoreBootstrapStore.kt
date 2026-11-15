package com.siyu.musique.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.siyu.musique.core.network.BootstrapState
import com.siyu.musique.core.network.BootstrapStore
import kotlinx.coroutines.flow.first

private val Context.biliBootstrapDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bili_bootstrap",
)

/**
 * The [BootstrapStore] backed by DataStore preferences. This is the
 * only file that touches DataStore; the bootstrap logic itself is
 * pure and tested against [com.siyu.musique.core.network.InMemoryBootstrapStore].
 * Statically reviewed only: DataStore cannot compile under kotlinc
 * here, the first real compile is the Windows Gradle build.
 */
class DataStoreBootstrapStore(
    private val context: Context,
) : BootstrapStore {

    override suspend fun load(): BootstrapState? {
        val prefs = context.biliBootstrapDataStore.data.first()
        val uuid = prefs[KEY_UUID] ?: return null
        return BootstrapState(
            uuid = uuid,
            bLsid = prefs[KEY_B_LSID] ?: "",
            bNut = prefs[KEY_B_NUT] ?: "",
            buvid3 = prefs[KEY_BUVID3] ?: "",
            buvid4 = prefs[KEY_BUVID4] ?: "",
            biliTicket = prefs[KEY_BILI_TICKET] ?: "",
            ticketExpiresAtSeconds = prefs[KEY_TICKET_EXPIRES] ?: 0L,
            wbiImgKey = prefs[KEY_WBI_IMG] ?: "",
            wbiSubKey = prefs[KEY_WBI_SUB] ?: "",
            wbiFetchedAtMillis = prefs[KEY_WBI_FETCHED_AT] ?: 0L,
        )
    }

    override suspend fun save(state: BootstrapState) {
        context.biliBootstrapDataStore.edit { prefs ->
            prefs[KEY_UUID] = state.uuid
            prefs[KEY_B_LSID] = state.bLsid
            prefs[KEY_B_NUT] = state.bNut
            prefs[KEY_BUVID3] = state.buvid3
            prefs[KEY_BUVID4] = state.buvid4
            prefs[KEY_BILI_TICKET] = state.biliTicket
            prefs[KEY_TICKET_EXPIRES] = state.ticketExpiresAtSeconds
            prefs[KEY_WBI_IMG] = state.wbiImgKey
            prefs[KEY_WBI_SUB] = state.wbiSubKey
            prefs[KEY_WBI_FETCHED_AT] = state.wbiFetchedAtMillis
        }
    }

    companion object {
        private val KEY_UUID = stringPreferencesKey("uuid")
        private val KEY_B_LSID = stringPreferencesKey("b_lsid")
        private val KEY_B_NUT = stringPreferencesKey("b_nut")
        private val KEY_BUVID3 = stringPreferencesKey("buvid3")
        private val KEY_BUVID4 = stringPreferencesKey("buvid4")
        private val KEY_BILI_TICKET = stringPreferencesKey("bili_ticket")
        private val KEY_TICKET_EXPIRES = longPreferencesKey("ticket_expires_at_seconds")
        private val KEY_WBI_IMG = stringPreferencesKey("wbi_img_key")
        private val KEY_WBI_SUB = stringPreferencesKey("wbi_sub_key")
        private val KEY_WBI_FETCHED_AT = longPreferencesKey("wbi_fetched_at_millis")
    }
}
