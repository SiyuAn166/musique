package com.siyu.musique.core.logging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Log severity, mirroring android.util.Log levels without depending on Android. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** One timestamped, level-tagged log line. */
data class LogLine(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    /** One line for display and copy: "20:52:01.123 I/Player: message". */
    fun format(): String =
        "${formatTimestamp(timestampMillis)} ${level.name.first()}/$tag: $message"
}

/** Wall-clock time as HH:mm:ss.SSS in the device timezone. */
fun formatTimestamp(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIME_FORMAT)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * A ring buffer holding the last [capacity] log lines, oldest evicted
 * first. Thread-safe: logging happens from network, bootstrap, and UI
 * threads. [snapshot] is a StateFlow so the log screen recomposes as
 * lines arrive.
 */
class LogBuffer(
    val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val lines = ArrayDeque<LogLine>()

    private val _snapshot = MutableStateFlow<List<LogLine>>(emptyList())
    val snapshot: StateFlow<List<LogLine>> = _snapshot.asStateFlow()

    /** Append a line, evicting the oldest lines past [capacity]. Returns the stored line. */
    fun append(level: LogLevel, tag: String, message: String): LogLine {
        val line = LogLine(clock(), level, tag, message)
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > capacity) {
                lines.removeFirst()
            }
            _snapshot.value = lines.toList()
        }
        return line
    }

    /** A copy of the buffered lines, oldest first. */
    fun lines(): List<LogLine> = synchronized(lock) { lines.toList() }

    /** Drop every buffered line. */
    fun clear() {
        synchronized(lock) {
            lines.clear()
            _snapshot.value = emptyList()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 300
    }
}
