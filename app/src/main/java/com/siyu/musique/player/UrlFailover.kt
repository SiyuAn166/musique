package com.siyu.musique.player

/**
 * The ordered URLs for one load, tried in order until one plays:
 * the primary URL first, then the backup mirrors. Pure: the
 * engine owns the timing (stall watchdog, error callbacks) and
 * this only owns the order, so unit tests drive it directly.
 *
 * [current] is the URL being tried now, or null when the list is
 * empty. [advance] moves to the next untried URL and returns it,
 * or null when every URL has been tried. [reset] starts a fresh
 * load: blank URLs are dropped and duplicates removed, keeping
 * first-occurrence order.
 */
class UrlFailover {
    private var urls: List<String> = emptyList()
    private var index: Int = 0

    fun reset(urls: List<String>) {
        this.urls = urls.filter { it.isNotBlank() }.distinct()
        index = 0
    }

    fun current(): String? = urls.getOrNull(index)

    fun hasUntried(): Boolean = index + 1 < urls.size

    fun advance(): String? {
        if (!hasUntried()) return null
        index++
        return urls[index]
    }

    /** How many URLs have been tried so far (1-based attempt count). */
    val attemptCount: Int
        get() = if (urls.isEmpty()) 0 else index + 1
}
