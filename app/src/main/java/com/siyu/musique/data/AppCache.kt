package com.siyu.musique.data

/**
 * The app's caches, behind one seam: the audio cache (ExoPlayer,
 * 200MB LRU) plus the cover cache (Coil disk cache). The settings
 * screen shows the combined size and clears both. Clearing while
 * a track plays is safe: playback falls back to the network for
 * spans that are no longer cached.
 */
interface AppCache {

    /** Combined cached bytes: audio plus covers. */
    suspend fun sizeBytes(): Long

    /** Drop everything cached, audio and covers. */
    suspend fun clear()
}

/** An [AppCache] holding nothing. The ViewModel's default, so tests that do not care about the cache keep working. */
class InMemoryAppCache : AppCache {
    override suspend fun sizeBytes(): Long = 0L
    override suspend fun clear() = Unit
}
