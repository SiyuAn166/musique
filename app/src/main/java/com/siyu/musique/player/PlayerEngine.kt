package com.siyu.musique.player

/**
 * A playback failure reported by the engine. [isHttpFailure] is true
 * for network/HTTP failures (bad status, connection failure, timeout),
 * which are worth one fresh-URL retry; decode and other failures are
 * not retried.
 */
data class PlaybackError(val message: String, val isHttpFailure: Boolean)

/**
 * The song info the engine attaches to the media item, so the
 * notification and lock screen show the title, artist, and cover.
 * Pure data: the engine translates it into Media3 metadata.
 * [artworkUrl] may be blank, in which case no artwork is attached.
 */
data class PlayerMediaMetadata(
    val title: String,
    val artist: String,
    val artworkUrl: String,
)

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
     * [metadata] is attached to the media item for the notification
     * and lock screen; null leaves the item without song info.
     * Replaces whatever was playing.
     */
    fun play(url: String, cacheKey: String, metadata: PlayerMediaMetadata? = null)

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

    /**
     * Player-level volume, 0f (silent) to 1f (full). Callers clamp
     * to [0f, 1f]; engines clamp defensively. Applies to the
     * player, not the item, so it survives track changes and works
     * before any item is loaded.
     */
    fun setVolume(volume: Float)

    /**
     * Release the engine's hold on the player. Engines backed by a
     * shared player only detach their listener here; the player
     * itself is released by its owner. The engine must not be used
     * after this.
     */
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
    override fun play(url: String, cacheKey: String, metadata: PlayerMediaMetadata?) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun currentPositionMs(): Long = 0L
    override fun durationMs(): Long = PlayerEngine.UNKNOWN_DURATION
    override fun setVolume(volume: Float) = Unit
    override fun release() = Unit
}
