package com.siyu.musique

import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.core.network.WbiKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WbiKeyStoreTest {

    private var now = 1_794_710_400_000L

    private fun store(fetches: MutableList<Int>): WbiKeyStore = WbiKeyStore(
        fetcher = {
            fetches.add(1)
            WbiKeyStore.WbiKeys(WbiSignerTest.IMG_KEY, WbiSignerTest.SUB_KEY)
        },
        nowMillis = { now },
    )

    @Test
    fun `first call fetches and returns the verified mixin`() = runTest {
        val fetches = mutableListOf<Int>()
        val mixin = store(fetches).getMixinKey()
        assertEquals(WbiSignerTest.MIXIN, mixin)
        assertEquals(1, fetches.size)
    }

    @Test
    fun `second call within 6 hours uses the cache`() = runTest {
        val fetches = mutableListOf<Int>()
        val keyStore = store(fetches)
        keyStore.getMixinKey()
        now += WbiKeyStore.TTL_MILLIS - 1L
        keyStore.getMixinKey()
        assertEquals(1, fetches.size)
    }

    @Test
    fun `call after 6 hours refetches`() = runTest {
        val fetches = mutableListOf<Int>()
        val keyStore = store(fetches)
        keyStore.getMixinKey()
        now += WbiKeyStore.TTL_MILLIS
        keyStore.getMixinKey()
        assertEquals(2, fetches.size)
    }

    @Test
    fun `force refresh refetches even within the TTL`() = runTest {
        val fetches = mutableListOf<Int>()
        val keyStore = store(fetches)
        keyStore.getMixinKey()
        keyStore.getMixinKey(forceRefresh = true)
        assertEquals(2, fetches.size)
    }

    @Test
    fun `invalidate drops the cache`() = runTest {
        val fetches = mutableListOf<Int>()
        val keyStore = store(fetches)
        keyStore.getMixinKey()
        keyStore.invalidate()
        keyStore.getMixinKey()
        assertEquals(2, fetches.size)
    }

    @Test
    fun `seeded keys are used without fetching`() = runTest {
        val keyStore = WbiKeyStore(fetcher = null, nowMillis = { now })
        keyStore.seed(WbiSignerTest.IMG_KEY, WbiSignerTest.SUB_KEY)
        assertEquals(WbiSignerTest.MIXIN, keyStore.getMixinKey())
    }

    @Test
    fun `no fetcher and no seed throws`() = runTest {
        val keyStore = WbiKeyStore(fetcher = null, nowMillis = { now })
        try {
            keyStore.getMixinKey()
            fail("expected BiliParseException")
        } catch (_: BiliParseException) {
            // expected
        }
    }
}
