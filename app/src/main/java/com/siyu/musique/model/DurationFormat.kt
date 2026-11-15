package com.siyu.musique.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Formats a [Duration] for display in the track list and player.
 *
 * Always "M:SS" with total minutes, never rolling into hours, so the
 * display matches how Bilibili reports durations in search results:
 * 228 seconds -> "3:48", a 419-minute compilation -> "419:02"
 * (verified in the pre-dev smoke test: "419:2", "829:59").
 * Negative durations are treated as zero; a track never shows a
 * negative time.
 */
fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * Remaining time as a negative "M:SS" ("-2:31"), the way the player
 * page shows it next to the slider. A position past the duration
 * reads as "-0:00", never a negative time.
 */
fun formatRemaining(duration: Duration, position: Duration): String =
    "-${formatDuration(duration - position)}"

/**
 * Parses the duration text Bilibili search results report: total
 * minutes as "M:SS", where minutes can exceed 60 ("419:2", "829:59")
 * and seconds may be a single digit. A three-part "H:MM:SS" form is
 * accepted defensively. Returns null for blank or malformed text so
 * the caller can fall back to zero instead of dropping the track.
 */
fun parseDurationText(text: String): Duration? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(":")
    if (parts.size !in 2..3) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null
    val totalSeconds = when (numbers.size) {
        2 -> numbers[0] * 60L + numbers[1]
        else -> numbers[0] * 3600L + numbers[1] * 60L + numbers[2]
    }
    return totalSeconds.seconds
}
