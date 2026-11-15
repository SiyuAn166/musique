package com.siyu.musique.data

import android.util.Log
import com.siyu.musique.core.logging.LogLine
import com.siyu.musique.core.logging.LogLevel
import com.siyu.musique.core.logging.LogSink
import com.siyu.musique.core.logging.MusiqueLog

/**
 * The logcat sink: every line the app logs also goes to logcat under
 * the tag "Musique" (view with `adb logcat -s Musique`). Installed
 * once at startup from MainActivity. Statically reviewed only: it
 * needs android.util.Log.
 */
object AndroidLogSink : LogSink {
    const val TAG = "Musique"

    /** Install this sink on the shared logger. Call once at startup. */
    fun install() {
        MusiqueLog.shared.addSink(this)
    }

    override fun write(line: LogLine) {
        val message = "[${line.tag}] ${line.message}"
        when (line.level) {
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
    }
}
