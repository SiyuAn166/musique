package com.siyu.musique.data

import com.siyu.musique.core.network.AudioQuality

/**
 * Persistence for the audio quality preference: one stored value,
 * the enum name. The production implementation is DataStore
 * ([DataStoreQualityPreferenceStore]); tests and runs without
 * persistence use [InMemoryQualityPreferenceStore]. A missing value
 * means "never chosen", and callers fall back to [AudioQuality.BEST].
 */
interface QualityPreferenceStore {
    /** The stored preference, or null when nothing was ever saved. */
    suspend fun load(): AudioQuality?

    /** Persists [preference], replacing any previous value. */
    suspend fun save(preference: AudioQuality)
}

/** In-memory store for tests and for runs without persistence. */
class InMemoryQualityPreferenceStore(
    initial: AudioQuality? = null,
) : QualityPreferenceStore {
    private var stored: AudioQuality? = initial

    override suspend fun load(): AudioQuality? = stored

    override suspend fun save(preference: AudioQuality) {
        stored = preference
    }
}
