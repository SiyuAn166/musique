package com.siyu.musique.player

/**
 * Test double for [PlayerEngine]: records every call and lets tests
 * drive the listener callbacks (buffering, playing, paused, ended,
 * error) by hand. No audio, no Media3.
 */
class FakePlayerEngine : PlayerEngine {
    var listener: PlayerEngine.Listener? = null
        private set

    /** (url, cacheKey) pairs passed to [play], in order. */
    val playCalls = mutableListOf<Pair<String, String>>()
    var pauseCalls: Int = 0
        private set
    var resumeCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var releaseCalls: Int = 0
        private set

    override fun setListener(listener: PlayerEngine.Listener?) {
        this.listener = listener
    }

    override fun play(url: String, cacheKey: String) {
        playCalls.add(url to cacheKey)
    }

    override fun pause() {
        pauseCalls++
    }

    override fun resume() {
        resumeCalls++
    }

    override fun stop() {
        stopCalls++
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
