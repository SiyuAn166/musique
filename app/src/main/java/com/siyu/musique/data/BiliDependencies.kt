package com.siyu.musique.data

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.WbiKeyStore
import com.siyu.musique.player.ExoPlayerEngine
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.player.PlayerHolder

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

    /** The shared audio cache, from the holder that also owns the player. Hand the same instance to the engine and to the app cache, so the settings size is what playback stored. */
    fun createAudioCache(context: Context): SimpleCache =
        PlayerHolder.audioCache(context.applicationContext)

    /**
     * The ExoPlayer engine, around the holder's shared player.
     * Build once, on the main thread, and keep it for the
     * Activity's life. [audioCache] must be the holder's
     * instance (see [createAudioCache]), so the engine's cache
     * diagnostic reads the cache the player writes.
     */
    fun createPlayerEngine(context: Context, audioCache: SimpleCache): PlayerEngine =
        ExoPlayerEngine(
            context.applicationContext,
            PlayerHolder.player(context.applicationContext),
            audioCache,
        )

    /** The app caches (audio + covers), for the settings screen's size and clear button. */
    fun createAppCache(context: Context, audioCache: SimpleCache): AppCache =
        AndroidAppCache(context.applicationContext, audioCache)

    /** The persisted audio quality preference, read by the ViewModel at startup. */
    fun createQualityPreferenceStore(context: Context): QualityPreferenceStore =
        DataStoreQualityPreferenceStore(context.applicationContext)

    /** The persisted playlist list, read by the ViewModel at startup. */
    fun createPlaylistStore(context: Context): PlaylistStore =
        DataStorePlaylistStore(context.applicationContext)

    /** The persisted repeat/shuffle mode, read by the ViewModel at startup. */
    fun createPlaybackModeStore(context: Context): PlaybackModeStore =
        DataStorePlaybackModeStore(context.applicationContext)

    /** The persisted playback state (queue, track, position), restored by the ViewModel at startup. */
    fun createPlaybackStateStore(context: Context): PlaybackStateStore =
        DataStorePlaybackStateStore(context.applicationContext)

    /** The persisted download records, read by the ViewModel at startup. */
    fun createDownloadStore(context: Context): DownloadStore =
        DataStoreDownloadStore(context.applicationContext)

    /** The audio file downloader, streaming CDN bytes to files in the downloads dir. */
    fun createAudioFileDownloader(): AudioFileDownloader =
        OkHttpAudioFileDownloader(OkHttpGateway.defaultClient())

    /** The app-private downloads dir, under filesDir: uninstall deletes it with everything else. */
    fun downloadsDir(context: Context): File =
        File(context.applicationContext.filesDir, DOWNLOADS_DIR_NAME)

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
