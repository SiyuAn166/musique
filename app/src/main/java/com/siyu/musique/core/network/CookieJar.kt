package com.siyu.musique.core.network

import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One cookie, matched by domain, path, and expiry (RFC 6265). */
data class Cookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtMillis: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
)

/**
 * In-memory cookie store with RFC 6265 matching by domain, path, and
 * expiry. Pure Kotlin, no Android imports, so it compiles and tests
 * under plain kotlinc.
 *
 * Only the bootstrap cookies (buvid, ticket, device identity) are
 * persisted, through [BootstrapStore]; this jar is seeded from the
 * stored state at startup and otherwise lives for the process.
 */
class CookieJar(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val cookies = mutableListOf<Cookie>()

    /** Directly set a cookie, e.g. the locally generated device identity. */
    fun set(
        name: String,
        value: String,
        domain: String,
        path: String = "/",
        expiresAtMillis: Long? = null,
        secure: Boolean = false,
    ) {
        cookies.removeAll { it.name == name && it.domain == domain && it.path == path }
        cookies.add(Cookie(name, value, domain.lowercase(), path, expiresAtMillis, secure, false))
    }

    /** Parse one Set-Cookie header received for [requestUrl] and store it. */
    fun storeFromSetCookie(header: String, requestUrl: String) {
        val parts = header.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val firstEquals = parts[0].indexOf('=')
        if (firstEquals <= 0) return
        val name = parts[0].substring(0, firstEquals).trim()
        val value = parts[0].substring(firstEquals + 1).trim()
        if (name.isEmpty()) return

        val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return
        var domain = uri.host?.lowercase() ?: return
        var path = defaultPath(uri.path)
        var expiresAtMillis: Long? = null
        var maxAgeSeconds: Long? = null
        var secure = false
        var httpOnly = false
        for (attribute in parts.drop(1)) {
            val eq = attribute.indexOf('=')
            val attrName = (if (eq >= 0) attribute.substring(0, eq) else attribute).trim()
                .lowercase()
            val attrValue = if (eq >= 0) attribute.substring(eq + 1).trim() else ""
            when (attrName) {
                "domain" -> if (attrValue.isNotEmpty()) {
                    domain = attrValue.trimStart('.').lowercase()
                }
                "path" -> if (attrValue.isNotEmpty()) path = attrValue
                "expires" -> expiresAtMillis = parseHttpDate(attrValue)
                "max-age" -> maxAgeSeconds = attrValue.toLongOrNull()
                "secure" -> secure = true
                "httponly" -> httpOnly = true
            }
        }
        if (maxAgeSeconds != null) {
            expiresAtMillis = if (maxAgeSeconds <= 0) {
                0L // already expired: delete the cookie
            } else {
                nowMillis() + maxAgeSeconds * 1000L
            }
        }
        cookies.removeAll { it.name == name && it.domain == domain && it.path == path }
        if (expiresAtMillis != null && expiresAtMillis <= nowMillis()) return
        cookies.add(Cookie(name, value, domain, path, expiresAtMillis, secure, httpOnly))
    }

    /** The Cookie header value for [url], or null when no cookie matches. */
    fun headerFor(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val requestPath = uri.path?.takeIf { it.isNotEmpty() } ?: "/"
        val isSecure = uri.scheme.equals("https", ignoreCase = true)
        val now = nowMillis()
        cookies.removeAll { it.expiresAtMillis != null && it.expiresAtMillis <= now }
        val matching = cookies.filter { cookie ->
            domainMatches(host, cookie.domain) &&
                requestPath.startsWith(cookie.path) &&
                (!cookie.secure || isSecure)
        }
        if (matching.isEmpty()) return null
        return matching.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** The current value of the cookie named [name], or null. */
    fun valueOf(name: String): String? {
        val now = nowMillis()
        return cookies.firstOrNull {
            it.name == name && (it.expiresAtMillis == null || it.expiresAtMillis > now)
        }?.value
    }

    /** Snapshot for tests and debugging. */
    fun all(): List<Cookie> = cookies.toList()

    private fun domainMatches(host: String, cookieDomain: String): Boolean =
        host == cookieDomain || host.endsWith(".$cookieDomain")

    private fun defaultPath(requestPath: String?): String {
        if (requestPath.isNullOrEmpty() || !requestPath.startsWith("/")) return "/"
        val lastSlash = requestPath.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else requestPath.substring(0, lastSlash)
    }

    private fun parseHttpDate(value: String): Long? = runCatching {
        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).parse(value))
            .toEpochMilli()
    }.getOrNull()
}
