package com.siyu.musique.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.siyu.musique.core.network.BiliClient
import java.io.File

/**
 * The process-wide player and audio cache, shared by the
 * Activity's engine and the background playback service: both
 * must drive the same ExoPlayer, or the notification would
 * control a different player than the UI. Created lazily on
 * first use, from either side; released only when the service
 * is destroyed, never by the Activity, so playback survives
 * leaving the app.
 *
 * Statically reviewed only: it needs an Android Context and
 * Media3, so it cannot compile under kotlinc here.
 */
object PlayerHolder {

    private var cache: SimpleCache? = null
    private var player: ExoPlayer? = null

    /** The shared audio cache (200MB LRU). The settings screen measures this same instance. */
    @Synchronized
    fun audioCache(context: Context): SimpleCache {
        cache?.let { return it }
        val appContext = context.applicationContext
        return SimpleCache(
            File(appContext.cacheDir, CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
            StandaloneDatabaseProvider(appContext),
        ).also { cache = it }
    }

    /**
     * The shared player, streaming through the shared cache.
     * Built once; every later call returns the same instance.
     * The media source factory lives on the builder, so the
     * engine hands the player plain media items.
     */
    @Synchronized
    fun player(context: Context): ExoPlayer {
        player?.let { return it }
        val appContext = context.applicationContext
        val mediaSourceFactory = ProgressiveMediaSource.Factory(
            CacheDataSource.Factory()
                .setCache(audioCache(appContext))
                .setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(REQUEST_HEADERS),
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { player = it }
    }

    /**
     * Release the player and the cache. Called only from the
     * playback service's onDestroy: the Activity must never
     * release the shared player, or background playback would
     * die with it.
     */
    @Synchronized
    fun release() {
        player?.release()
        player = null
        cache?.release()
        cache = null
    }

    private const val CACHE_DIR = "exo_audio_cache"

    /** 200MB, least recently used eviction, per the v0.1 design. */
    private const val CACHE_SIZE_BYTES: Long = 200L * 1024L * 1024L

    /**
     * The CDN answers 403 without these. Same desktop Firefox
     * User-Agent as the API client, same bilibili.com Referer.
     */
    private val REQUEST_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to BiliClient.USER_AGENT,
        "Referer" to "https://www.bilibili.com/",
    )
}
