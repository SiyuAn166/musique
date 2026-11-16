package com.siyu.musique.data

import java.util.Locale

/**
 * The app language: follow the phone's language, or one fixed
 * language. Persisted by enum name, like the audio quality
 * preference; an unknown stored name maps back to [SYSTEM] so a
 * renamed or corrupt value can never break startup.
 */
enum class LanguagePreference {
    /** Follow the phone's language. */
    SYSTEM,

    /** English, whatever the phone's language. */
    ENGLISH,

    /** Simplified Chinese, whatever the phone's language. */
    CHINESE,

    /** French, whatever the phone's language. */
    FRENCH;

    companion object {
        /** The stored enum name back to a preference; null or unknown names mean [SYSTEM]. */
        fun fromStoredName(name: String?): LanguagePreference =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * The locale a language preference applies, or null to follow
 * the system locale. Pure function, no Android APIs: the
 * Activity wraps its base context with the returned locale,
 * and a null return leaves the base context unchanged.
 */
fun localeForPreference(preference: LanguagePreference): Locale? = when (preference) {
    LanguagePreference.SYSTEM -> null
    LanguagePreference.ENGLISH -> Locale.ENGLISH
    LanguagePreference.CHINESE -> Locale.SIMPLIFIED_CHINESE
    LanguagePreference.FRENCH -> Locale.FRENCH
}

/**
 * Persistence for the language preference: one stored value,
 * the enum name. The production implementation is DataStore
 * ([DataStoreLanguagePreferenceStore]); tests and runs without
 * persistence use [InMemoryLanguagePreferenceStore]. A missing
 * value means "never chosen", and callers fall back to
 * [LanguagePreference.SYSTEM].
 */
interface LanguagePreferenceStore {
    /** The stored preference, or null when nothing was ever saved. */
    suspend fun load(): LanguagePreference?

    /** Persists [preference], replacing any previous value. */
    suspend fun save(preference: LanguagePreference)
}

/** In-memory store for tests and for runs without persistence. */
class InMemoryLanguagePreferenceStore(
    initial: LanguagePreference? = null,
) : LanguagePreferenceStore {
    private var stored: LanguagePreference? = initial

    override suspend fun load(): LanguagePreference? = stored

    override suspend fun save(preference: LanguagePreference) {
        stored = preference
    }
}
