package com.siyu.musique.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the WBI keys with a 6-hour cache. Keys arrive from two
 * sources: seeded by the bootstrap from the GenWebTicket nav, or
 * fetched from /x/web-interface/nav through [fetcher] when the cache
 * is empty or expired.
 *
 * [fetcher] is set after construction by the wiring code, because the
 * fetcher calls [ApiService] which needs the [BiliClient] which needs
 * this store: the cycle is broken by assigning the fetcher once the
 * service exists. The fetcher must use an unsigned request, so it
 * never re-enters this store.
 *
 * Concurrent callers share one in-flight fetch: the mutex makes the
 * second caller wait, then it sees the fresh cache.
 */
class WbiKeyStore(
    var fetcher: (suspend () -> WbiKeys)? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    data class WbiKeys(val imgKey: String, val subKey: String)

    private val mutex = Mutex()
    private var cached: WbiKeys? = null
    private var fetchedAtMillis: Long = 0L

    /** Seed keys from the bootstrap (GenWebTicket nav) or stored state. */
    fun seed(imgKey: String, subKey: String) {
        if (imgKey.isEmpty() || subKey.isEmpty()) return
        cached = WbiKeys(imgKey, subKey)
        fetchedAtMillis = nowMillis()
    }

    /** Drop the cached keys so the next call fetches fresh ones. */
    fun invalidate() {
        cached = null
        fetchedAtMillis = 0L
    }

    /**
     * The mixin key for signing, fetching fresh keys when the cache is
     * empty or older than [TTL_MILLIS], or when [forceRefresh] is set.
     */
    suspend fun getMixinKey(forceRefresh: Boolean = false): String = mutex.withLock {
        val now = nowMillis()
        val current = cached
        if (!forceRefresh && current != null && now - fetchedAtMillis < TTL_MILLIS) {
            WbiSigner.mixinKeyOf(current.imgKey, current.subKey)
        } else {
            val fetch = fetcher
                ?: throw BiliParseException("WBI key fetcher not configured")
            val fresh = fetch()
            cached = fresh
            fetchedAtMillis = nowMillis()
            WbiSigner.mixinKeyOf(fresh.imgKey, fresh.subKey)
        }
    }

    companion object {
        const val TTL_MILLIS: Long = 6L * 60L * 60L * 1000L
    }
}
