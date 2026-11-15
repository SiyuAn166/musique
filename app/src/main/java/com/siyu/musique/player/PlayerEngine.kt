package com.siyu.musique.player

/**
 * A playback failure reported by the engine. [isHttpFailure] is true
 * for network/HTTP failures (bad status, connection failure, timeout),
 * which are worth one fresh-URL retry; decode and other failures are
 * not retried.
 */
data class PlaybackError(val message: String, val isHttpFailure: Boolean)

/**
 * The player seam: the ViewModel drives playback through this
 * interface only, so unit tests use a fake and the production
 * implementation ([ExoPlayerEngine]) stays in the Android layer.
 *
 * All methods must be called from one thread (the main thread, via
 * the ViewModel's scope): ExoPlayer requires single-thread access.
 * Callbacks arrive on the same thread.
 */
interface PlayerEngine {

    /** Playback events. Every callback names no URL, only state. */
    interface Listener {
        /** The engine is loading/buffering the current item. */
        fun onBuffering()

        /** Audio is playing. */
        fun onPlaying()

        /** Playback paused (by request or by the system). */
        fun onPaused()

        /** The current item finished. */
        fun onEnded()

        /** Playback failed. See [PlaybackError] for retryability. */
        fun onError(error: PlaybackError)
    }

    fun setListener(listener: Listener?)

    /**
     * Stream [url] (progressive playback, never download-then-play).
     * [cacheKey] identifies the cached audio (bvid + cid + quality
     * id); the URL itself is never the cache key because URLs expire.
     * Replaces whatever was playing.
     */
    fun play(url: String, cacheKey: String)

    /** Pause, keeping the current item loaded. */
    fun pause()

    /** Resume the current item after [pause]. */
    fun resume()

    /** Stop playback and drop the current item. */
    fun stop()

    /**
     * Seek within the current item to [positionMs]. Callers clamp to
     * [0, duration]; engines clamp defensively and ignore seeks with
     * no item loaded.
     */
    fun seekTo(positionMs: Long)

    /**
     * The current playback position in milliseconds, or 0 when
     * nothing is loaded.
     */
    fun currentPositionMs(): Long

    /**
     * The current item's duration in milliseconds, or
     * [UNKNOWN_DURATION] when the engine does not know it yet (the
     * ViewModel falls back to the track's duration then).
     */
    fun durationMs(): Long

    /** Release all player resources. The engine must not be used after this. */
    fun release()

    companion object {
        /** [durationMs] sentinel: the engine does not know the duration yet. */
        const val UNKNOWN_DURATION: Long = -1L
    }
}

/**
 * A player that does nothing. The ViewModel's default, so screens and
 * tests that do not care about audio keep working without an engine;
 * production always wires [ExoPlayerEngine].
 */
object NoOpPlayerEngine : PlayerEngine {
    override fun setListener(listener: PlayerEngine.Listener?) = Unit
    override fun play(url: String, cacheKey: String) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun currentPositionMs(): Long = 0L
    override fun durationMs(): Long = PlayerEngine.UNKNOWN_DURATION
    override fun release() = Unit
}
