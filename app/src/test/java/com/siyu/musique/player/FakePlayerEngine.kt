package com.siyu.musique.player

/**
 * Test double for [PlayerEngine]: records every call and lets tests
 * drive the listener callbacks (buffering, playing, paused, ended,
 * error) by hand. No audio, no Media3.
 */
class FakePlayerEngine : PlayerEngine {
    var listener: PlayerEngine.Listener? = null
        private set

    /** (first url, cacheKey) pairs passed to [play], in order. */
    val playCalls = mutableListOf<Pair<String, String>>()

    /** The full URL lists passed to [play], in order (primary first, then backups). */
    val playUrlLists = mutableListOf<List<String>>()

    /** The song metadata from the latest [play] call, or null when none was passed. */
    var lastMetadata: PlayerMediaMetadata? = null
        private set
    var pauseCalls: Int = 0
        private set
    var resumeCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var releaseCalls: Int = 0
        private set

    /** When set, [stop] throws this instead of returning normally. */
    var failStopWith: Throwable? = null

    /**
     * When true, [resume] reports onPlaying synchronously inside
     * the call, the way the real ExoPlayer delivers
     * onPlayWhenReadyChanged on the calling thread. Off by
     * default: most tests drive the callbacks by hand.
     */
    var firePlayingOnResume: Boolean = false

    /** The position [currentPositionMs] reports; tests move it to simulate playback. */
    var positionMs: Long = 0L

    /**
     * The duration [durationMs] reports. -1 (the engine's unknown
     * sentinel) by default, so tests exercise the ViewModel's
     * track-duration fallback unless they set a real duration.
     */
    var durationMsValue: Long = PlayerEngine.UNKNOWN_DURATION

    /** Positions passed to [seekTo], in order. */
    val seekCalls = mutableListOf<Long>()

    /** The volume [setVolume] last set; 1f (full) by default. */
    var volume: Float = 1f
        private set

    /** Volumes passed to [setVolume], in order. */
    val volumeCalls = mutableListOf<Float>()

    override fun setListener(listener: PlayerEngine.Listener?) {
        this.listener = listener
    }

    override fun play(urls: List<String>, cacheKey: String, metadata: PlayerMediaMetadata?) {
        playCalls.add((urls.firstOrNull() ?: "") to cacheKey)
        playUrlLists.add(urls.toList())
        lastMetadata = metadata
        positionMs = 0L
    }

    override fun pause() {
        pauseCalls++
    }

    override fun resume() {
        resumeCalls++
        if (firePlayingOnResume) {
            listener?.onPlaying()
        }
    }

    override fun stop() {
        stopCalls++
        failStopWith?.let { throw it }
    }

    override fun seekTo(positionMs: Long) {
        seekCalls.add(positionMs)
        this.positionMs = positionMs
    }

    override fun currentPositionMs(): Long = positionMs

    override fun durationMs(): Long = durationMsValue

    override fun setVolume(volume: Float) {
        this.volume = volume
        volumeCalls.add(volume)
    }

    override fun release() {
        releaseCalls++
    }

    fun emitBuffering() = listener?.onBuffering()
    fun emitPlaying() = listener?.onPlaying()
    fun emitPaused() = listener?.onPaused()
    fun emitEnded() = listener?.onEnded()
    fun emitError(error: PlaybackError) = listener?.onError(error)
}
