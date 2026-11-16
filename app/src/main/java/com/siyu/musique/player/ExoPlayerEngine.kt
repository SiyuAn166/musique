package com.siyu.musique.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.siyu.musique.core.logging.LogRedactor
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
 * Each load carries an ordered URL list (primary plus backup
 * mirrors). A network/HTTP error, or buffering that outlasts
 * the stall timeout, moves to the next URL, resuming at the
 * stalled position; the listener hears onError only when every
 * URL is exhausted. The cache key is the same for every mirror
 * of one stream, so cached ranges survive a failover.
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

    /** The URLs for the current load, tried in order. */
    private val failover = UrlFailover()

    /** Bumped on every load attempt, so a stale stall check never fires for a newer load. */
    private var loadGeneration: Int = 0

    /** The cache key and metadata of the current load, reused by every failover attempt. */
    private var loadCacheKey: String = ""
    private var loadMetadata: PlayerMediaMetadata? = null

    /** The stall watchdog: one pending check at most, on the main thread. */
    private val stallHandler = Handler(Looper.getMainLooper())
    private var stallCheck: Runnable? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    listener?.onBuffering()
                    scheduleStallCheck()
                }
                Player.STATE_READY -> {
                    cancelStallCheck()
                    if (player.playWhenReady) {
                        listener?.onPlaying()
                    } else {
                        listener?.onPaused()
                    }
                }
                Player.STATE_ENDED -> {
                    cancelStallCheck()
                    listener?.onEnded()
                }
                Player.STATE_IDLE -> cancelStallCheck()
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
            // A network/HTTP failure on one mirror is worth the
            // next mirror before the ViewModel hears about it;
            // decode and other failures are reported at once,
            // since another copy of the same file will not fix
            // them. The ViewModel's own one fresh-URL retry still
            // runs when every mirror is exhausted here.
            if (error.errorCode in HTTP_ERROR_CODES && failover.hasUntried()) {
                failover(reason = "error")
                return
            }
            cancelStallCheck()
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

    override fun play(urls: List<String>, cacheKey: String, metadata: PlayerMediaMetadata?) {
        failover.reset(urls)
        loadCacheKey = cacheKey
        loadMetadata = metadata
        val first = failover.current()
        if (first == null) {
            MusiqueLog.w(TAG, "play called with no URLs; nothing to load")
            return
        }
        if (Uri.parse(first).scheme != "file") {
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
        startAttempt(first, playWhenReady = true, seekToMs = 0L)
        ensureServiceRunning()
    }

    /**
     * Loads one URL of the current failover list: a fresh media
     * item with the load's cache key and metadata, prepared at
     * [seekToMs] (0 for a fresh load, the stalled position for a
     * mid-track failover). [playWhenReady] null leaves the
     * player's play/pause state untouched, so a failover never
     * resumes playback the user paused.
     */
    private fun startAttempt(url: String, playWhenReady: Boolean?, seekToMs: Long) {
        loadGeneration++
        cancelStallCheck()
        val metadata = loadMetadata
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
            .setCustomCacheKey(loadCacheKey)
            .setMediaMetadata(metadataBuilder.build())
            .build()
        if (Uri.parse(url).scheme == "file") {
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
        if (seekToMs > 0L) {
            player.seekTo(seekToMs)
        }
        if (playWhenReady != null) {
            player.playWhenReady = playWhenReady
        }
    }

    /**
     * Moves to the next untried URL after a stall ([reason]
     * "stall") or an HTTP error ([reason] "error"), resuming at
     * the position where the old attempt stopped. Logs the new
     * mirror's host only, never the URL. No-op when the list is
     * exhausted; the caller reports the error then.
     */
    private fun failover(reason: String) {
        val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
        val next = failover.advance() ?: return
        MusiqueLog.i(
            TAG,
            "player failover reason=$reason attempt=${failover.attemptCount} " +
                "host=${LogRedactor.hostOf(next)}",
        )
        startAttempt(next, playWhenReady = null, seekToMs = resumePositionMs)
    }

    /**
     * Arms the stall watchdog: when buffering lasts longer than
     * [STALL_FAILOVER_TIMEOUT_MS] without reaching READY, the
     * current mirror is presumed stalled and the next URL is
     * tried. A 24-second stall on a slow mirror was observed on
     * device (Nov 16, 2026); the timeout cuts that to one
     * failover delay plus the next mirror's buffer time. The
     * generation and attempt guards keep a stale check from
     * firing for a newer load.
     */
    private fun scheduleStallCheck() {
        cancelStallCheck()
        val generation = loadGeneration
        val attempt = failover.attemptCount
        val check = Runnable {
            stallCheck = null
            if (generation != loadGeneration || attempt != failover.attemptCount) return@Runnable
            if (player.playbackState != Player.STATE_BUFFERING) return@Runnable
            if (failover.hasUntried()) {
                failover(reason = "stall")
            }
            // All mirrors exhausted: leave the last attempt
            // loading; ExoPlayer's own retries continue, and the
            // ViewModel stays in Buffering, as before.
        }
        stallCheck = check
        stallHandler.postDelayed(check, STALL_FAILOVER_TIMEOUT_MS)
    }

    private fun cancelStallCheck() {
        stallCheck?.let { stallHandler.removeCallbacks(it) }
        stallCheck = null
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
        cancelStallCheck()
        loadGeneration++
        failover.reset(emptyList())
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
        cancelStallCheck()
        loadGeneration++
        player.removeListener(playerListener)
        listener = null
    }

    companion object {
        private const val TAG = "Player"

        /** Buffering longer than this is treated as a stalled mirror and fails over. */
        private const val STALL_FAILOVER_TIMEOUT_MS: Long = 10_000L

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
