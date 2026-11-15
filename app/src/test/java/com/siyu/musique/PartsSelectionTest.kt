package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.SearchViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Parts selection for multi-part videos: the details call
 * learns the parts, they survive part switches, clear on a new
 * video, learn in the background for cid-filled tracks, and
 * playing a part leaves the queue untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartsSelectionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun part(cid: String, title: String) = Track(
        id = "BV1multi",
        cid = cid,
        title = title,
        artist = "A",
        cover = "",
        duration = 200.seconds,
    )

    private val part1 = part("cid-1", "Part 1")
    private val part2 = part("cid-2", "Part 2")
    private val part3 = part("cid-3", "Part 3")
    private val parts = listOf(part1, part2, part3)

    /** The search row for the multi-part video: no cid yet. */
    private val videoRow = Track(
        id = "BV1multi",
        cid = "",
        title = "Multi",
        artist = "A",
        cover = "",
        duration = 600.seconds,
    )

    private val otherVideo = Track(
        id = "BV1other",
        cid = "cid-other",
        title = "Other",
        artist = "A",
        cover = "",
        duration = 200.seconds,
    )

    private class PartsRepository(
        private val results: List<Track>,
        private val multiParts: List<Track>,
    ) : SearchRepository {
        var detailCalls: Int = 0
            private set

        override suspend fun search(query: String): List<Track> = results

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            detailCalls++
            return if (track.id == "BV1multi") {
                DetailedTrack(multiParts.first(), multiParts)
            } else {
                DetailedTrack(track, listOf(track))
            }
        }

        override suspend fun fetchPlayUrl(
            track: Track,
            preference: AudioQuality,
        ): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.cid}.m4s", 216670L)
    }

    private fun viewModel(engine: FakePlayerEngine, repo: PartsRepository) = SearchViewModel(
        repo,
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
    )

    @Test
    fun `playing a multi-part video learns its parts`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, PartsRepository(listOf(videoRow), parts))
        vm.search("x")
        advanceUntilIdle()
        vm.play(videoRow)
        advanceUntilIdle()
        assertEquals(parts, vm.currentParts.value)
    }

    @Test
    fun `parts survive switching between parts`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, PartsRepository(listOf(videoRow), parts))
        vm.search("x")
        advanceUntilIdle()
        vm.play(videoRow)
        advanceUntilIdle()
        vm.playFromQueue(part2)
        advanceUntilIdle()
        assertEquals(parts, vm.currentParts.value)
        assertEquals("Part 2", vm.currentTrack.value?.title)
    }

    @Test
    fun `parts clear when switching to another video`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, PartsRepository(listOf(videoRow, otherVideo), parts))
        vm.search("x")
        advanceUntilIdle()
        vm.play(videoRow)
        advanceUntilIdle()
        assertEquals(3, vm.currentParts.value.size)
        vm.play(otherVideo)
        advanceUntilIdle()
        // The old video's parts are gone; the new video's single
        // part list is learned in the background after the hand-off.
        assertEquals(listOf(otherVideo), vm.currentParts.value)
    }

    @Test
    fun `parts learn in the background for a cid-filled track`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val repo = PartsRepository(listOf(part1), parts)
        val vm = viewModel(engine, repo)
        vm.search("x")
        advanceUntilIdle()
        vm.play(part1)
        advanceUntilIdle()
        assertEquals(parts, vm.currentParts.value)
        assertEquals(1, repo.detailCalls)
        // Known now: a second load does not fetch again.
        vm.loadPartsForCurrent()
        advanceUntilIdle()
        assertEquals(1, repo.detailCalls)
    }

    @Test
    fun `playing a part leaves the queue unchanged`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, PartsRepository(listOf(videoRow), parts))
        val playlist = Playlist.default().copy(tracks = listOf(videoRow, otherVideo))
        vm.playFromPlaylist(videoRow, playlist)
        advanceUntilIdle()
        // Playlist play keeps the playlist queue, but the parts are known.
        assertEquals(listOf(videoRow, otherVideo), vm.queue.value)
        assertEquals(parts, vm.currentParts.value)
        vm.playPart(part3)
        advanceUntilIdle()
        assertEquals(listOf(videoRow, otherVideo), vm.queue.value)
        assertEquals("Part 3", vm.currentTrack.value?.title)
        assertEquals(parts, vm.currentParts.value)
    }

    @Test
    fun `playing the current part does nothing`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine, PartsRepository(listOf(videoRow), parts))
        vm.search("x")
        advanceUntilIdle()
        vm.play(videoRow)
        advanceUntilIdle()
        vm.playPart(part1)
        advanceUntilIdle()
        assertEquals(1, engine.playCalls.size)
    }
}
