package com.siyu.musique.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.core.network.SMALL_COVER_HEIGHT
import com.siyu.musique.core.network.SMALL_COVER_WIDTH
import com.siyu.musique.core.network.sizedCoverUrl

/**
 * [PlayerEngine] backed by the shared Media3 ExoPlayer from
 * [PlayerHolder], streaming: the audio plays from the CDN
 * progressively, never downloaded whole first (the reference
 * Flutter app downloads the full DASH file before playing; this
 * is the deliberate design change).
 *
 * The player is shared with the background playback service, so
 * the notification controls the same playback as the UI. [play]
 * makes sure the service is running, so playback survives
 * leaving the app. [release] only detaches this engine's
 * listener; the player itself lives until the service is
 * destroyed. Song info travels on the media item as Media3
 * metadata, so the notification and lock screen show the title,
 * artist, and cover. Downloaded files play from file:// URLs
 * through a FileDataSource media source: the player's
 * CacheDataSource factory hands cache misses to an HTTP
 * upstream that cannot open file URLs.
 *
 * Statically reviewed only: it needs an Android Context and
 * Media3, so it cannot compile under kotlinc here. Construct
 * and call it on the main thread.
 */
class ExoPlayerEngine(
    context: Context,
    private val player: ExoPlayer,
    private val cache: SimpleCache,
) : PlayerEngine {

    private val appContext: Context = context.applicationContext

    private var listener: PlayerEngine.Listener? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> listener?.onBuffering()
                Player.STATE_READY -> if (player.playWhenReady) {
                    listener?.onPlaying()
                } else {
                    listener?.onPaused()
                }
                Player.STATE_ENDED -> listener?.onEnded()
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Flipping playWhenReady while the player is already
            // READY (pause and resume mid-track) fires no
            // playback-state change, so onPlaybackStateChanged
            // above never runs for it. Without this override a
            // resume left the UI stuck on Buffering with the
            // progress polling never restarted: the ViewModel
            // only learns the player is playing again from
            // onPlaying. Only report while READY; during a
            // fresh play, a stop, or the ENDED state the
            // playback-state callback owns the transition.
            if (player.playbackState == Player.STATE_READY) {
                if (playWhenReady) {
                    listener?.onPlaying()
                } else {
                    listener?.onPaused()
                }
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
    }

    init {
        player.addListener(playerListener)
    }

    override fun setListener(listener: PlayerEngine.Listener?) {
        this.listener = listener
    }

    override fun play(url: String, cacheKey: String, metadata: PlayerMediaMetadata?) {
        val isLocalFile = Uri.parse(url).scheme == "file"
        if (!isLocalFile) {
            // Diagnostic for the audio cache: how many bytes of this
            // key are already stored before the stream starts. The key
            // holds bvid + cid + quality only, never the URL. Local
            // files skip the cache entirely, so there is nothing to
            // measure for them. getCachedBytes (total bytes) is the
            // right call here: getCachedLength returns the length of
            // the continuous cached run, or a NEGATIVE hole length
            // when position 0 is not cached at all, which printed
            // -9223372036854775807 for every uncached track.
            val cachedBytes = cache.getCachedBytes(cacheKey, 0L, Long.MAX_VALUE)
            MusiqueLog.i(TAG, "audio cache $cachedBytes bytes stored key=$cacheKey")
        }
        val metadataBuilder = MediaMetadata.Builder()
        metadata?.let {
            metadataBuilder.setTitle(it.title)
            metadataBuilder.setArtist(it.artist)
            if (it.artworkUrl.isNotBlank()) {
                // The notification needs no more than the small
                // cover: the full image would download ~290KB.
                metadataBuilder.setArtworkUri(
                    Uri.parse(
                        sizedCoverUrl(it.artworkUrl, SMALL_COVER_WIDTH, SMALL_COVER_HEIGHT),
                    ),
                )
            }
        }
        val item = MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(metadataBuilder.build())
            .build()
        if (isLocalFile) {
            // Setting a media source built with the file factory
            // overrides the builder's CacheDataSource factory for
            // this item only; network items below keep using it.
            val source = ProgressiveMediaSource.Factory(FileDataSource.Factory())
                .createMediaSource(item)
            player.setMediaSource(source)
        } else {
            player.setMediaItem(item)
        }
        player.prepare()
        player.playWhenReady = true
        ensureServiceRunning()
    }

    /**
     * Starts the background playback service (a no-op when it is
     * already running). A failure only costs the notification and
     * background survival; playback itself is unaffected.
     */
    private fun ensureServiceRunning() {
        try {
            appContext.startForegroundService(
                Intent(appContext, PlaybackService::class.java),
            )
        } catch (e: Exception) {
            MusiqueLog.w(TAG, "playback service start failed (${e::class.simpleName})")
        }
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

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    override fun durationMs(): Long {
        val duration = player.duration
        return if (duration == C.TIME_UNSET) PlayerEngine.UNKNOWN_DURATION
        else duration.coerceAtLeast(0L)
    }

    override fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun release() {
        // Detach only: the player is shared with the playback
        // service, which releases it when the service is destroyed.
        player.removeListener(playerListener)
        listener = null
    }

    companion object {
        private const val TAG = "Player"

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
