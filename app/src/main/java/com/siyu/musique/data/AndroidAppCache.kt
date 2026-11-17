package com.siyu.musique.data

import android.content.Context
import androidx.media3.datasource.cache.Cache
import coil.imageLoader
import com.siyu.musique.core.logging.MusiqueLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [AppCache] backed by the real caches: the [Cache] the player
 * writes audio into (resolved fresh from [audioCacheProvider] on
 * every call) and the Coil disk cache behind the cover images.
 * The audio cache is cleared key by key through the cache itself,
 * never by deleting files under it, so the player's index stays
 * consistent.
 *
 * The provider must resolve the holder's CURRENT instance on
 * every call, never a captured one: the playback service
 * releases the shared cache in onDestroy, and any use of a
 * released instance throws IllegalStateException, which left
 * the settings row stuck on the loading dots with the clear
 * button disabled. Resolving fresh also recreates the cache
 * when the holder has none, so the size works even before the
 * first playback of the process.
 *
 * Each cache is read defensively and failures are logged with
 * their cause: one unreadable cache must not blank the whole
 * number or block clearing the other.
 *
 * Statically reviewed only: it needs an Android Context, Media3,
 * and Coil, so it cannot compile under kotlinc here.
 */
class AndroidAppCache(
    context: Context,
    private val audioCacheProvider: () -> Cache,
) : AppCache {

    private val appContext: Context = context.applicationContext

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        val audio = runCatching { audioCacheProvider().cacheSpace }
            .onFailure { MusiqueLog.w(TAG, "audio cache size failed (${describe(it)})") }
            .getOrDefault(0L)
        val covers = runCatching { appContext.imageLoader.diskCache?.size ?: 0L }
            .onFailure { MusiqueLog.w(TAG, "cover cache size failed (${describe(it)})") }
            .getOrDefault(0L)
        audio + covers
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching {
                val audioCache = audioCacheProvider()
                audioCache.keys.toList().forEach { key -> audioCache.removeResource(key) }
            }.onFailure { MusiqueLog.w(TAG, "audio cache clear failed (${describe(it)})") }
            runCatching { appContext.imageLoader.diskCache?.clear() }
                .onFailure { MusiqueLog.w(TAG, "cover cache clear failed (${describe(it)})") }
        }
    }

    private fun describe(error: Throwable): String =
        "${error::class.simpleName}: ${error.message}"

    private companion object {
        const val TAG = "Cache"
    }
}
