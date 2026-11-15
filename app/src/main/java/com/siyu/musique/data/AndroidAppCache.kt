package com.siyu.musique.data

import android.content.Context
import androidx.media3.datasource.cache.Cache
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [AppCache] backed by the real caches: the [Cache] the player
 * writes audio into (the same instance [ExoPlayerEngine] streams
 * through, so the size is what playback actually stored) and the
 * Coil disk cache behind the cover images. The audio cache is
 * cleared key by key through the cache itself, never by deleting
 * files under it, so the player's index stays consistent.
 * Statically reviewed only: it needs an Android Context, Media3,
 * and Coil, so it cannot compile under kotlinc here.
 */
class AndroidAppCache(
    context: Context,
    private val audioCache: Cache,
) : AppCache {

    private val appContext: Context = context.applicationContext

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        audioCache.cacheSpace + (appContext.imageLoader.diskCache?.size ?: 0L)
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            audioCache.keys.toList().forEach { key -> audioCache.removeResource(key) }
            appContext.imageLoader.diskCache?.clear()
        }
    }
}
