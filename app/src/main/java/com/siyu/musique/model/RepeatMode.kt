package com.siyu.musique.model

/**
 * What happens when a track ends. [OFF] is plain sequential play:
 * the queue plays in order and stops at the end. [ALL] loops the
 * whole queue: the track after the last is the first again. [ONE]
 * replays the same track from the start. The repeat button cycles
 * OFF -> ALL -> ONE -> OFF, like every other music app; shuffle
 * is a separate toggle and combines with any of these.
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE;

    /** The next mode in the button's cycle. */
    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        /** Maps a stored name back, defaulting to [OFF] for anything unknown. */
        fun fromStoredName(name: String): RepeatMode =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}
