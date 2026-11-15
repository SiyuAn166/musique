package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.data.AppCache
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The settings cache state: measuring publishes the size, clearing
 * drops the caches and re-measures, and failures leave the last
 * known size untouched instead of crashing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheSettingsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeAppCache(var size: Long = 12_345L) : AppCache {
        var clearCalls = 0
        var failSize = false
        var failClear = false

        override suspend fun sizeBytes(): Long {
            if (failSize) throw RuntimeException("size boom")
            return size
        }

        override suspend fun clear() {
            clearCalls++
            if (failClear) throw RuntimeException("clear boom")
            size = 0L
        }
    }

    private fun viewModel(cache: AppCache) = SearchViewModel(
        FakeSearchRepository(),
        FakePlayerEngine(),
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        appCache = cache,
    )

    @Test
    fun `loadCacheSize publishes the measured size`() = runTest(testDispatcher) {
        val vm = viewModel(FakeAppCache(size = 12_345L))
        assertNull(vm.cacheSizeBytes.value)
        vm.loadCacheSize()
        advanceUntilIdle()
        assertEquals(12_345L, vm.cacheSizeBytes.value)
    }

    @Test
    fun `clearCache clears and re-measures`() = runTest(testDispatcher) {
        val cache = FakeAppCache(size = 12_345L)
        val vm = viewModel(cache)
        vm.loadCacheSize()
        advanceUntilIdle()
        vm.clearCache()
        advanceUntilIdle()
        assertEquals(1, cache.clearCalls)
        assertEquals(0L, vm.cacheSizeBytes.value)
        assertFalse(vm.clearingCache.value)
    }

    @Test
    fun `a failed measurement leaves the last size untouched`() = runTest(testDispatcher) {
        val cache = FakeAppCache(size = 12_345L)
        val vm = viewModel(cache)
        vm.loadCacheSize()
        advanceUntilIdle()
        cache.failSize = true
        vm.loadCacheSize()
        advanceUntilIdle()
        assertEquals(12_345L, vm.cacheSizeBytes.value)
    }

    @Test
    fun `a failed clear still re-measures`() = runTest(testDispatcher) {
        val cache = FakeAppCache(size = 12_345L)
        cache.failClear = true
        val vm = viewModel(cache)
        vm.clearCache()
        advanceUntilIdle()
        assertEquals(1, cache.clearCalls)
        assertEquals(12_345L, vm.cacheSizeBytes.value)
        assertFalse(vm.clearingCache.value)
    }
}
