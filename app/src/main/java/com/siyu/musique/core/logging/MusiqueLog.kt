package com.siyu.musique.core.logging

/**
 * A sink that receives every log line, e.g. android.util.Log on a
 * device. Sinks must never throw: [MusiqueLogger] swallows sink
 * failures so logging can never break playback or search.
 */
fun interface LogSink {
    fun write(line: LogLine)
}

/**
 * Defense-in-depth redaction for log messages. Call sites must already
 * follow the rule (log hosts, ids, counts, and error kinds only; never
 * full URLs, cookies, tickets, or keys), but exception messages and
 * response fragments can smuggle secrets in, so every message passes
 * through [redact] before it reaches the buffer or any sink.
 *
 * - Any http(s) URL carrying a query string is cut to scheme + host:
 *   play URLs carry signed CDN query params, and WBI-signed API URLs
 *   carry the signature. The host stays, everything after it goes.
 * - Known secret key=value pairs (SESSDATA, bili_jct, bili_ticket,
 *   buvid3/buvid4, w_rid) have their values replaced.
 *
 * This is a backstop, not a license to log secrets: keep call sites
 * clean, and never log [com.siyu.musique.core.network.BiliParseException]
 * previews (response bodies can contain the ticket JWT).
 */
object LogRedactor {
    private val URL_WITH_QUERY = Regex("https?://[^\\s?#]+\\?[^\\s]*")
    private val SECRET_PAIR =
        Regex("(?i)(sessdata|bili_jct|bili_ticket|buvid3|buvid4|w_rid|wts)=[^&\\s]*")

    fun redact(message: String): String {
        var out = URL_WITH_QUERY.replace(message) { match ->
            val url = match.value
            val afterScheme = url.indexOf("://") + 3
            val slash = url.indexOf('/', afterScheme)
            val hostEnd = if (slash >= 0) slash else url.indexOf('?', afterScheme)
            "${url.substring(0, hostEnd)}/<redacted>"
        }
        out = SECRET_PAIR.replace(out, "$1=<redacted>")
        return out
    }

    /**
     * The host of [url], or "unknown-host" when the URL does not parse.
     * Use this at call sites that need to name a CDN: the host is safe
     * to log, the full URL never is.
     */
    fun hostOf(url: String): String {
        val afterScheme = url.indexOf("://")
        if (afterScheme < 0) return "unknown-host"
        val rest = url.substring(afterScheme + 3)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.ifEmpty { "unknown-host" }
    }
}

/**
 * The app logger: every line goes to the in-memory [buffer] (shown on
 * the log screen) and to every installed [LogSink] (logcat on a
 * device, via the Android sink installed at startup). Pure Kotlin, no
 * Android imports, so the network core can log too.
 */
class MusiqueLogger(val buffer: LogBuffer = LogBuffer()) {
    private val sinks = mutableListOf<LogSink>()

    fun addSink(sink: LogSink) {
        synchronized(sinks) {
            sinks.add(sink)
        }
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val line = buffer.append(level, tag, LogRedactor.redact(message))
        val copy = synchronized(sinks) { sinks.toList() }
        for (sink in copy) {
            runCatching { sink.write(line) }
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
}

/**
 * The single app-wide logger. Production code takes a [MusiqueLogger]
 * parameter defaulting to [shared], so the log screen, logcat, and
 * every layer see the same lines; tests pass their own instances.
 */
object MusiqueLog {
    val shared = MusiqueLogger()

    fun d(tag: String, message: String) = shared.d(tag, message)
    fun i(tag: String, message: String) = shared.i(tag, message)
    fun w(tag: String, message: String) = shared.w(tag, message)
    fun e(tag: String, message: String) = shared.e(tag, message)
}
