package com.siyu.musique.data

import android.content.Context
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.WbiKeyStore

/**
 * Manual wiring for the Bilibili stack. No DI framework in v0.1.
 * The WBI key fetcher is assigned after [ApiService] exists, which
 * breaks the client <-> service construction cycle: the fetcher uses
 * an unsigned nav request, so it never re-enters the key store.
 * Statically reviewed only: it needs an Android Context for DataStore.
 */
object BiliDependencies {

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
