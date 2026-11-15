package com.siyu.musique.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import com.siyu.musique.MainActivity
import com.siyu.musique.R

/**
 * The background playback service: it owns the [MediaSession]
 * around the shared player, so the notification, the lock
 * screen, and headset buttons keep working after leaving the
 * app, and the process (and the player with it) survives in
 * the background. The session's commands are routed to the
 * ViewModel through [PlaybackControlsRegistry], which stays
 * the single owner of queue and playback state; the player
 * itself holds one item at a time and knows nothing about the
 * queue.
 *
 * Statically reviewed only: it needs Android and Media3, so it
 * cannot compile under kotlinc here. The first real compile is
 * the Gradle build.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        getOrCreateSession()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        getOrCreateSession()

    /**
     * Builds the session on first use, from either entry point:
     * onCreate in the normal start path, onGetSession if a
     * controller ever arrives first.
     */
    private fun getOrCreateSession(): MediaSession {
        mediaSession?.let { return it }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return MediaSession.Builder(this, PlayerHolder.player(this))
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .build()
            .also { mediaSession = it }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while playing keeps the music
        // going; while paused it stops the service instead of
        // lingering with a stale notification.
        if (mediaSession?.player?.isPlaying != true) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        PlayerHolder.release()
        super.onDestroy()
    }

    /**
     * Routes controller commands. Play/pause and next/previous
     * go to the ViewModel, which owns the queue and the state
     * machine; repeat and shuffle stay ViewModel-owned too, so
     * a controller can never set them on the player behind the
     * ViewModel's back. Everything else (including scrubbing)
     * passes through to the player, whose position the
     * ViewModel reads back through its polling. With no live
     * ViewModel, play/pause and skipping fall through to the
     * player, so the loaded item still answers the notification.
     */
    private class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // The player holds one item at a time and the queue
            // lives in the ViewModel, so the player's own
            // available commands would hide next/previous from
            // controllers. Grant them explicitly.
            val playerCommands = Player.Commands.Builder()
                .addAllReadOnlyCommands()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.accept(
                SessionCommands.EMPTY,
                playerCommands,
            )
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            when (playerCommand) {
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE -> return Player.COMMAND_INVALID
            }
            val handler = PlaybackControlsRegistry.handler ?: return playerCommand
            return when (playerCommand) {
                Player.COMMAND_PLAY_PAUSE -> {
                    handler.onToggleRequested()
                    Player.COMMAND_INVALID
                }
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    handler.onNextRequested()
                    Player.COMMAND_INVALID
                }
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    handler.onPreviousRequested()
                    Player.COMMAND_INVALID
                }
                else -> playerCommand
            }
        }
    }
}
