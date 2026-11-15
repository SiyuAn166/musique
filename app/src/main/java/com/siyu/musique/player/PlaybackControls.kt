package com.siyu.musique.player

/**
 * Playback actions the background service's media session can
 * request: the notification, the lock screen, and headset buttons
 * speak through this, and the ViewModel answers through its
 * existing queue and state logic, so there is exactly one owner
 * of playback state. Seeking is not here on purpose: the session
 * applies scrubbing straight to the player, and the ViewModel's
 * progress polling reads the new position back.
 * Pure Kotlin, no Android imports, so the ViewModel and its tests
 * compile under kotlinc.
 */
interface PlaybackControls {
    /** Toggle play/pause, following the same rules as the play/pause button. */
    fun onToggleRequested()

    /** Skip to the next track in the queue. */
    fun onNextRequested()

    /** Go back to the previous track in the queue. */
    fun onPreviousRequested()
}

/**
 * The process-wide slot for the live [PlaybackControls] handler.
 * The ViewModel registers itself on creation and unregisters on
 * clear; the playback service reads the slot when a controller
 * command arrives. Null when no ViewModel is alive: the service
 * then lets the player handle the command directly.
 */
object PlaybackControlsRegistry {
    var handler: PlaybackControls? = null
}
