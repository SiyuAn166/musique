package com.siyu.musique

import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.InMemoryLanguagePreferenceStore
import com.siyu.musique.data.LanguagePreference
import com.siyu.musique.data.LanguagePreferenceStore
import com.siyu.musique.data.localeForPreference
import com.siyu.musique.ui.SearchViewModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The language preference: the pure preference-to-locale
 * mapping, the stored-name parsing, the in-memory store, and
 * the ViewModel's load/save round trip. Pure Kotlin, no
 * Android APIs: the DataStore implementation is statically
 * reviewed only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguagePreferenceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `follow-system maps to no locale`() {
        assertNull(localeForPreference(LanguagePreference.SYSTEM))
    }

    @Test
    fun `fixed languages map to their locales`() {
        assertEquals(Locale.ENGLISH, localeForPreference(LanguagePreference.ENGLISH))
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            localeForPreference(LanguagePreference.CHINESE),
        )
        assertEquals(Locale.FRENCH, localeForPreference(LanguagePreference.FRENCH))
    }

    @Test
    fun `stored names round trip`() {
        LanguagePreference.entries.forEach { preference ->
            assertEquals(
                preference,
                LanguagePreference.fromStoredName(preference.name),
            )
        }
    }

    @Test
    fun `missing or unknown stored names mean follow-system`() {
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStoredName(null))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStoredName("SPANISH"))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStoredName("english"))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStoredName(""))
    }

    @Test
    fun `in-memory store loads null before anything is saved`() = runTest(testDispatcher) {
        assertNull(InMemoryLanguagePreferenceStore().load())
    }

    @Test
    fun `in-memory store saves and loads, and later saves replace earlier ones`() =
        runTest(testDispatcher) {
            val store = InMemoryLanguagePreferenceStore()
            store.save(LanguagePreference.CHINESE)
            assertEquals(LanguagePreference.CHINESE, store.load())
            store.save(LanguagePreference.FRENCH)
            assertEquals(LanguagePreference.FRENCH, store.load())
        }

    @Test
    fun `in-memory store starts from its initial value`() = runTest(testDispatcher) {
        val store = InMemoryLanguagePreferenceStore(LanguagePreference.ENGLISH)
        assertEquals(LanguagePreference.ENGLISH, store.load())
    }

    @Test
    fun `view model defaults to follow-system with an empty store`() =
        runTest(testDispatcher) {
            val viewModel = SearchViewModel(
                FakeSearchRepository(),
                languagePreferenceStore = InMemoryLanguagePreferenceStore(),
            )
            advanceUntilIdle()
            assertEquals(
                LanguagePreference.SYSTEM,
                viewModel.languagePreference.value,
            )
        }

    @Test
    fun `setting a language updates the state and persists it`() = runTest(testDispatcher) {
        val store = InMemoryLanguagePreferenceStore()
        val viewModel = SearchViewModel(
            FakeSearchRepository(),
            languagePreferenceStore = store,
        )
        advanceUntilIdle()
        viewModel.setLanguagePreference(LanguagePreference.FRENCH)
        assertEquals(LanguagePreference.FRENCH, viewModel.languagePreference.value)
        assertEquals(LanguagePreference.FRENCH, store.load())
    }

    @Test
    fun `a stored language loads on a fresh view model`() = runTest(testDispatcher) {
        val store = InMemoryLanguagePreferenceStore(LanguagePreference.CHINESE)
        val viewModel = SearchViewModel(
            FakeSearchRepository(),
            languagePreferenceStore = store,
        )
        advanceUntilIdle()
        assertEquals(
            LanguagePreference.CHINESE,
            viewModel.languagePreference.value,
        )
    }

    @Test
    fun `setting the current language is a no-op`() = runTest(testDispatcher) {
        var saves = 0
        val store = object : LanguagePreferenceStore {
            private var stored: LanguagePreference? = null
            override suspend fun load(): LanguagePreference? = stored
            override suspend fun save(preference: LanguagePreference) {
                saves++
                stored = preference
            }
        }
        val viewModel = SearchViewModel(
            FakeSearchRepository(),
            languagePreferenceStore = store,
        )
        advanceUntilIdle()
        viewModel.setLanguagePreference(LanguagePreference.SYSTEM)
        assertEquals(0, saves)
        assertEquals(LanguagePreference.SYSTEM, viewModel.languagePreference.value)
    }
}
