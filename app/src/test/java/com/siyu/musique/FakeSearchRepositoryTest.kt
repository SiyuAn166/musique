package com.siyu.musique

import com.siyu.musique.data.FakeSearchRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSearchRepositoryTest {

    private val repository = FakeSearchRepository()

    @Test
    fun `blank query returns the whole fake list`() = runTest {
        val tracks = repository.search("")
        assertEquals(FakeSearchRepository.FAKE_TRACKS.size, tracks.size)
        assertEquals(5, tracks.size)
    }

    @Test
    fun `whitespace-only query returns the whole fake list`() = runTest {
        assertEquals(5, repository.search("   ").size)
    }

    @Test
    fun `keyword filters by title`() = runTest {
        val tracks = repository.search("夜曲")
        assertEquals(1, tracks.size)
        assertEquals("BV1q4FTeBEMj", tracks[0].id)
        assertEquals("28139389900", tracks[0].cid)
    }

    @Test
    fun `keyword filters by artist`() = runTest {
        val tracks = repository.search("周杰伦")
        assertEquals(1, tracks.size)
        assertEquals("001. 夜曲-周杰伦", tracks[0].title)
    }

    @Test
    fun `unknown keyword returns an empty list, not an error`() = runTest {
        assertTrue(repository.search("no such song at all").isEmpty())
    }

    @Test
    fun `every fake track has an id, a title, a positive duration, and a unique key`() = runTest {
        val tracks = repository.search("")
        assertTrue(tracks.all { it.id.isNotEmpty() && it.title.isNotEmpty() })
        assertTrue(tracks.all { it.duration.inWholeMilliseconds > 0 })
        assertEquals(tracks.size, tracks.map { it.key }.toSet().size)
    }
}
