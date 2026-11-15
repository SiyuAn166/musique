package com.siyu.musique.data

import android.content.Context
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.WbiKeyStore
import com.siyu.musique.player.ExoPlayerEngine
import com.siyu.musique.player.PlayerEngine

/**
 * Manual wiring for the Bilibili stack and the player. No DI
 * framework in v0.1.
 * The WBI key fetcher is assigned after [ApiService] exists, which
 * breaks the client <-> service construction cycle: the fetcher uses
 * an unsigned nav request, so it never re-enters the key store.
 * Statically reviewed only: it needs an Android Context for DataStore
 * and for the ExoPlayer cache.
 */
object BiliDependencies {

    /** The ExoPlayer engine. Build once, on the main thread, and keep it for the Activity's life. */
    fun createPlayerEngine(context: Context): PlayerEngine =
        ExoPlayerEngine(context.applicationContext)

    /** The persisted audio quality preference, read by the ViewModel at startup. */
    fun createQualityPreferenceStore(context: Context): QualityPreferenceStore =
        DataStoreQualityPreferenceStore(context.applicationContext)

    /** The persisted playlist list, read by the ViewModel at startup. */
    fun createPlaylistStore(context: Context): PlaylistStore =
        DataStorePlaylistStore(context.applicationContext)

    /** The persisted repeat/shuffle mode, read by the ViewModel at startup. */
    fun createPlaybackModeStore(context: Context): PlaybackModeStore =
        DataStorePlaybackModeStore(context.applicationContext)

    fun createSearchRepository(context: Context): SearchRepository {
        val appContext = context.applicationContext
        val cookieJar = CookieJar()
        val keyStore = WbiKeyStore()
        val client = BiliClient(
            gateway = OkHttpGateway(),
            cookieJar = cookieJar,
            wbiKeys = keyStore,
        )
        val apiService = ApiService(client)
        keyStore.fetcher = { apiService.fetchNavKeys() }
        val bootstrap = BiliBootstrap(
            client = client,
            cookieJar = cookieJar,
            store = DataStoreBootstrapStore(appContext),
            wbiKeys = keyStore,
        )
        return BiliSearchRepository(apiService, bootstrap)
    }
}
