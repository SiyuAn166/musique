package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.model.moved
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
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Drag-to-reorder inside a playlist: the pure list move, the
 * playlist-level reorder guards, and the ViewModel method the
 * detail screen's drag handle commits through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistReorderTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(id: String, title: String) = Track(
        id = id,
        cid = "cid-$id",
        title = title,
        artist = "A",
        cover = "",
        duration = 200.seconds,
    )

    private val trackA = track("BV1a", "A")
    private val trackB = track("BV1b", "B")
    private val trackC = track("BV1c", "C")
    private val trackD = track("BV1d", "D")

    private class EmptyRepository : SearchRepository {
        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): DetailedTrack =
            DetailedTrack(track, listOf(track))

        override suspend fun fetchPlayUrl(
            track: Track,
            preference: AudioQuality,
        ): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.id}.m4s", 216670L)
    }

    private fun viewModel(
        playlistStore: InMemoryPlaylistStore,
    ) = SearchViewModel(
        EmptyRepository(),
        FakePlayerEngine(),
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        playlistStore,
    )

    @Test
    fun `moved shifts the element down past the ones between`() {
        assertEquals(listOf("b", "c", "a", "d"), listOf("a", "b", "c", "d").moved(0, 2))
    }

    @Test
    fun `moved shifts the element up past the ones between`() {
        assertEquals(listOf("a", "d", "b", "c"), listOf("a", "b", "c", "d").moved(3, 1))
    }

    @Test
    fun `moved onto itself and out-of-range moves return the list unchanged`() {
        val list = listOf("a", "b", "c")
        assertSame(list, list.moved(1, 1))
        assertSame(list, list.moved(-1, 1))
        assertSame(list, list.moved(0, 3))
        assertSame(list, list.moved(3, 0))
    }

    @Test
    fun `withTrackMoved moves one track and keeps the rest in order`() {
        val playlist = Playlist.default().copy(tracks = listOf(trackA, trackB, trackC, trackD))
        val moved = playlist.withTrackMoved(0, 2)
        assertEquals(listOf(trackB, trackC, trackA, trackD), moved.tracks)
        assertSame(playlist, playlist.withTrackMoved(1, 1))
        assertSame(playlist, playlist.withTrackMoved(0, 9))
    }

    @Test
    fun `withTrackOrder applies a full permutation and rejects anything else`() {
        val playlist = Playlist.default().copy(tracks = listOf(trackA, trackB, trackC))
        val reordered = playlist.withTrackOrder(listOf(trackC.key, trackA.key, trackB.key))
        assertEquals(listOf(trackC, trackA, trackB), reordered.tracks)
        assertSame(playlist, playlist.withTrackOrder(listOf(trackC.key, trackA.key)))
        assertSame(playlist, playlist.withTrackOrder(listOf(trackC.key, trackA.key, "BV1x:cid")))
        assertSame(
            playlist,
            playlist.withTrackOrder(listOf(trackC.key, trackA.key, trackA.key)),
        )
    }

    @Test
    fun `reorder persists the new order and leaves other playlists alone`() = runTest(testDispatcher) {
        val target = Playlist.default().copy(tracks = listOf(trackA, trackB, trackC))
        val other = Playlist("u1", "Mix", listOf(trackD))
        val store = InMemoryPlaylistStore(listOf(target, other))
        val vm = viewModel(store)
        advanceUntilIdle()
        vm.reorderPlaylistTracks(
            Playlist.DEFAULT_ID,
            listOf(trackC.key, trackA.key, trackB.key),
        )
        advanceUntilIdle()
        val playlists = vm.playlists.value
        assertEquals(
            listOf(trackC, trackA, trackB),
            playlists.first { it.id == Playlist.DEFAULT_ID }.tracks,
        )
        assertEquals(listOf(trackD), playlists.first { it.id == "u1" }.tracks)
        assertEquals(
            listOf(trackC, trackA, trackB),
            store.load()?.first { it.id == Playlist.DEFAULT_ID }?.tracks,
        )
    }

    @Test
    fun `reorder rejects downloads unknown playlists and non-permutations`() = runTest(testDispatcher) {
        val target = Playlist.default().copy(tracks = listOf(trackA, trackB))
        val store = InMemoryPlaylistStore(listOf(target))
        val vm = viewModel(store)
        advanceUntilIdle()
        vm.reorderPlaylistTracks(Playlist.DOWNLOADS_ID, listOf(trackB.key, trackA.key))
        vm.reorderPlaylistTracks("missing", listOf(trackB.key, trackA.key))
        vm.reorderPlaylistTracks(Playlist.DEFAULT_ID, listOf(trackB.key))
        vm.reorderPlaylistTracks(Playlist.DEFAULT_ID, listOf(trackB.key, trackB.key))
        advanceUntilIdle()
        assertEquals(
            listOf(trackA, trackB),
            vm.playlists.value.first { it.id == Playlist.DEFAULT_ID }.tracks,
        )
        assertEquals(
            listOf(trackA, trackB),
            store.load()?.first { it.id == Playlist.DEFAULT_ID }?.tracks,
        )
    }
}
