package com.siyu.musique.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.siyu.musique.core.network.BiliClient
import java.io.File

/**
 * [PlayerEngine] backed by Media3 ExoPlayer, streaming: the audio
 * plays from the CDN progressively, never downloaded whole first
 * (the reference Flutter app downloads the full DASH file before
 * playing; this is the deliberate design change).
 *
 * The CDN needs the desktop Firefox User-Agent and a bilibili.com
 * Referer or it answers 403, so both are set as default request
 * headers. Audio bytes are cached through [CacheDataSource] (200MB
 * LRU) under the caller-supplied cache key (bvid + cid + quality);
 * play URLs are never cache keys and never stored.
 *
 * Statically reviewed only: it needs an Android Context and Media3,
 * so it cannot compile under kotlinc here. Construct and call it on
 * the main thread.
 */
class ExoPlayerEngine(context: Context) : PlayerEngine {

    private var listener: PlayerEngine.Listener? = null

    private val cache: SimpleCache = SimpleCache(
        File(context.cacheDir, CACHE_DIR),
        LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
        StandaloneDatabaseProvider(context),
    )

    private val mediaSourceFactory: ProgressiveMediaSource.Factory =
        ProgressiveMediaSource.Factory(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(REQUEST_HEADERS),
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_READY -> if (playWhenReady) {
                        listener?.onPlaying()
                    } else {
                        listener?.onPaused()
                    }
                    Player.STATE_ENDED -> listener?.onEnded()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(
                    PlaybackError(
                        message = error.message ?: "Playback error ${error.errorCode}",
                        isHttpFailure = error.errorCode in HTTP_ERROR_CODES,
                    ),
                )
            }
        })
    }

    override fun setListener(listener: PlayerEngine.Listener?) {
        this.listener = listener
    }

    override fun play(url: String, cacheKey: String) {
        val item = MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(cacheKey)
            .build()
        player.setMediaSource(mediaSourceFactory.createMediaSource(item))
        player.prepare()
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun resume() {
        player.playWhenReady = true
    }

    override fun stop() {
        player.stop()
    }

    override fun release() {
        player.release()
        cache.release()
    }

    companion object {
        const val CACHE_DIR = "exo_audio_cache"

        /** 200MB, least recently used eviction, per the v0.1 design. */
        const val CACHE_SIZE_BYTES: Long = 200L * 1024L * 1024L

        /**
         * The CDN answers 403 without these. Same desktop Firefox
         * User-Agent as the API client, same bilibili.com Referer.
         */
        val REQUEST_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to BiliClient.USER_AGENT,
            "Referer" to "https://www.bilibili.com/",
        )

        /** Media3 IO error codes: network/HTTP failures worth one fresh-URL retry. */
        val HTTP_ERROR_CODES: Set<Int> = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }
}
