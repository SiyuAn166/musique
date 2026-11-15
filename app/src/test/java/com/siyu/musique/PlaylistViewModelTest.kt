package com.siyu.musique

import com.siyu.musique.core.logging.LogBuffer
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.data.DetailedTrack
import com.siyu.musique.data.FakeSearchRepository
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.FakePlayerEngine
import com.siyu.musique.ui.SearchUiState
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel playlist behavior: the default bootstrap, CRUD, the
 * idempotent add, persistence through the fake store, and the queue
 * generalization (play from search vs play from a playlist, snapshot
 * semantics, empty-cid detail resolution).
 *
 * Every test advances until idle right after constructing the
 * ViewModel, so the initial playlist load has landed before any
 * mutation: mutations assume the load has completed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A repository that fills in a missing cid, for the empty-cid playback test. */
    private class DetailFillingRepository : SearchRepository {
        var detailCalls: Int = 0
            private set

        override suspend fun search(query: String): List<Track> = emptyList()

        override suspend fun fetchDetail(track: Track): DetailedTrack {
            detailCalls++
            val filled = track.copy(cid = "999")
            return DetailedTrack(filled, listOf(filled))
        }

        override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream =
            AudioStream(30280, "https://cdn.example/${track.id}.m4s", 216670L)
    }

    private fun track(id: String, cid: String = "1", title: String = id) = Track(
        id = id,
        cid = cid,
        title = title,
        artist = "artist",
        cover = "",
        duration = 200.seconds,
    )

    private fun viewModel(
        repository: SearchRepository = FakeSearchRepository(),
        engine: FakePlayerEngine = FakePlayerEngine(),
        store: InMemoryPlaylistStore = InMemoryPlaylistStore(),
    ) = SearchViewModel(
        repository,
        engine,
        MusiqueLogger(LogBuffer(capacity = 50, clock = { 1000L })),
        InMemoryQualityPreferenceStore(),
        store,
    )

    @Test
    fun `default playlist is bootstrapped and persisted when the store is empty`() =
        runTest(testDispatcher) {
            val store = InMemoryPlaylistStore()
            val vm = viewModel(store = store)
            advanceUntilIdle()
            assertEquals(listOf(Playlist.default()), vm.playlists.value)
            assertEquals(listOf(Playlist.default()), store.load())
        }

    @Test
    fun `stored playlists are loaded in creation order`() = runTest(testDispatcher) {
        val stored = listOf(Playlist.default(), Playlist("u1", "Mix", emptyList()))
        val vm = viewModel(store = InMemoryPlaylistStore(stored))
        advanceUntilIdle()
        assertEquals(stored, vm.playlists.value)
    }

    @Test
    fun `stored lists without the default get it prepended and persisted`() =
        runTest(testDispatcher) {
            val custom = Playlist("u1", "Mix", emptyList())
            val store = InMemoryPlaylistStore(listOf(custom))
            val vm = viewModel(store = store)
            advanceUntilIdle()
            assertEquals(listOf(Playlist.default(), custom), vm.playlists.value)
            assertEquals(listOf(Playlist.default(), custom), store.load())
        }

    @Test
    fun `createPlaylist appends a trimmed playlist and persists it`() =
        runTest(testDispatcher) {
            val store = InMemoryPlaylistStore()
            val vm = viewModel(store = store)
            advanceUntilIdle()
            vm.createPlaylist("  Late Night  ")
            advanceUntilIdle()
            val playlists = vm.playlists.value
            assertEquals(2, playlists.size)
            assertEquals("Late Night", playlists.last().name)
            assertTrue(playlists.last().tracks.isEmpty())
            assertNotEquals(Playlist.DEFAULT_ID, playlists.last().id)
            assertEquals(playlists, store.load())
        }

    @Test
    fun `createPlaylist rejects blank names`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.createPlaylist("   ")
        advanceUntilIdle()
        assertEquals(listOf(Playlist.default()), vm.playlists.value)
    }

    @Test
    fun `createPlaylist allows duplicate names with distinct ids`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.createPlaylist("Favorites")
        vm.createPlaylist("Favorites")
        advanceUntilIdle()
        val playlists = vm.playlists.value
        assertEquals(3, playlists.size)
        assertEquals(listOf("Favorites", "Favorites"), playlists.drop(1).map { it.name })
        assertNotEquals(playlists[1].id, playlists[2].id)
    }

    @Test
    fun `deletePlaylist removes a user playlist and persists`() = runTest(testDispatcher) {
        val store = InMemoryPlaylistStore()
        val vm = viewModel(store = store)
        advanceUntilIdle()
        vm.createPlaylist("Temp")
        advanceUntilIdle()
        val createdId = vm.playlists.value.last().id
        vm.deletePlaylist(createdId)
        advanceUntilIdle()
        assertEquals(listOf(Playlist.default()), vm.playlists.value)
        assertEquals(listOf(Playlist.default()), store.load())
    }

    @Test
    fun `deletePlaylist cannot delete the default playlist`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.deletePlaylist(Playlist.DEFAULT_ID)
        advanceUntilIdle()
        assertEquals(listOf(Playlist.default()), vm.playlists.value)
    }

    @Test
    fun `deletePlaylist with an unknown id is a no-op`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.deletePlaylist("no-such-id")
        advanceUntilIdle()
        assertEquals(listOf(Playlist.default()), vm.playlists.value)
    }

    @Test
    fun `addTrackToPlaylist appends the track and strips its play URL`() =
        runTest(testDispatcher) {
            val store = InMemoryPlaylistStore()
            val vm = viewModel(store = store)
            advanceUntilIdle()
            val added = track("BV1ADD00001").copy(playUrl = "https://cdn.example/x.m4s")
            vm.addTrackToPlaylist(Playlist.DEFAULT_ID, added)
            advanceUntilIdle()
            val stored = vm.playlists.value.single().tracks.single()
            assertEquals("BV1ADD00001", stored.id)
            assertEquals("", stored.playUrl)
            assertEquals(vm.playlists.value, store.load())
        }

    @Test
    fun `adding the same track twice is a no-op`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1DUP00001", cid = "5"))
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1DUP00001", cid = "5"))
        advanceUntilIdle()
        assertEquals(1, vm.playlists.value.single().tracks.size)
    }

    @Test
    fun `same bvid with a different cid is a separate entry`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PART0001", cid = "5"))
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PART0001", cid = "6"))
        advanceUntilIdle()
        assertEquals(2, vm.playlists.value.single().tracks.size)
    }

    @Test
    fun `addTrackToPlaylist with an unknown playlist is a no-op`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addTrackToPlaylist("no-such-id", track("BV1X"))
        advanceUntilIdle()
        assertEquals(listOf(Playlist.default()), vm.playlists.value)
    }

    @Test
    fun `the same track can be added to several playlists`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.createPlaylist("Second")
        advanceUntilIdle()
        val secondId = vm.playlists.value.last().id
        val added = track("BV1BOTH0001")
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, added)
        vm.addTrackToPlaylist(secondId, added)
        advanceUntilIdle()
        assertEquals(
            listOf(added),
            vm.playlists.value.first { it.isDefault }.tracks,
        )
        assertEquals(
            listOf(added),
            vm.playlists.value.first { it.id == secondId }.tracks,
        )
    }

    @Test
    fun `removeTrackFromPlaylist removes by key and persists`() = runTest(testDispatcher) {
        val store = InMemoryPlaylistStore()
        val vm = viewModel(store = store)
        advanceUntilIdle()
        val first = track("BV1REM00001")
        val second = track("BV1REM00002")
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, first)
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, second)
        vm.removeTrackFromPlaylist(Playlist.DEFAULT_ID, first.key)
        advanceUntilIdle()
        assertEquals(listOf(second), vm.playlists.value.single().tracks)
        assertEquals(vm.playlists.value, store.load())
    }

    @Test
    fun `removeTrackFromPlaylist with unknown key or playlist is a no-op`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val kept = track("BV1KEEP0001")
            vm.addTrackToPlaylist(Playlist.DEFAULT_ID, kept)
            vm.removeTrackFromPlaylist(Playlist.DEFAULT_ID, "BV1NOPE:1")
            vm.removeTrackFromPlaylist("no-such-id", kept.key)
            advanceUntilIdle()
            assertEquals(listOf(kept), vm.playlists.value.single().tracks)
        }

    @Test
    fun `playing from a playlist advances within the playlist`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine = engine)
        advanceUntilIdle()
        vm.search("Demo")
        advanceUntilIdle()
        val first = track("BV1PL000001")
        val second = track("BV1PL000002")
        val third = track("BV1PL000003")
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, first)
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, second)
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, third)
        val playlist = vm.playlists.value.single()
        vm.playFromPlaylist(first, playlist)
        advanceUntilIdle()
        engine.emitPlaying()
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals(second.id, vm.currentTrack.value?.id)
        assertEquals(2, engine.playCalls.size)
    }

    @Test
    fun `playing from search after playlist playback switches the queue back`() =
        runTest(testDispatcher) {
            val engine = FakePlayerEngine()
            val vm = viewModel(engine = engine)
            advanceUntilIdle()
            vm.search("Demo")
            advanceUntilIdle()
            val searchTracks = (vm.searchState.value as SearchUiState.Success).tracks
            vm.addTrackToPlaylist(Playlist.DEFAULT_ID, track("BV1PL000001"))
            vm.playFromPlaylist(
                track("BV1PL000001"),
                vm.playlists.value.single(),
            )
            advanceUntilIdle()
            vm.play(searchTracks[0])
            advanceUntilIdle()
            engine.emitPlaying()
            engine.emitEnded()
            advanceUntilIdle()
            assertEquals(searchTracks[1].id, vm.currentTrack.value?.id)
        }

    @Test
    fun `playlist edits do not rewrite the in-flight queue`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine = engine)
        advanceUntilIdle()
        val first = track("BV1Q0000001")
        val second = track("BV1Q0000002")
        val third = track("BV1Q0000003")
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, first)
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, second)
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, third)
        vm.playFromPlaylist(first, vm.playlists.value.single())
        advanceUntilIdle()
        // Remove the next track from the playlist while playing: the
        // snapshot queue still holds it, so it plays next.
        vm.removeTrackFromPlaylist(Playlist.DEFAULT_ID, second.key)
        assertEquals(listOf(first, third), vm.playlists.value.single().tracks)
        engine.emitPlaying()
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals(second.id, vm.currentTrack.value?.id)
    }

    @Test
    fun `playPrevious moves within the playlist queue`() = runTest(testDispatcher) {
        val engine = FakePlayerEngine()
        val vm = viewModel(engine = engine)
        advanceUntilIdle()
        val first = track("BV1PREV0001")
        val second = track("BV1PREV0002")
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, first)
        vm.addTrackToPlaylist(Playlist.DEFAULT_ID, second)
        vm.playFromPlaylist(second, vm.playlists.value.single())
        advanceUntilIdle()
        vm.playPrevious()
        advanceUntilIdle()
        assertEquals(first.id, vm.currentTrack.value?.id)
        assertEquals(2, engine.playCalls.size)
    }

    @Test
    fun `playing a playlist track with an empty cid resolves its details`() =
        runTest(testDispatcher) {
            val repository = DetailFillingRepository()
            val engine = FakePlayerEngine()
            val vm = viewModel(repository = repository, engine = engine)
            advanceUntilIdle()
            val added = track("BV1NOCID001", cid = "")
            vm.addTrackToPlaylist(Playlist.DEFAULT_ID, added)
            vm.playFromPlaylist(added, vm.playlists.value.single())
            advanceUntilIdle()
            assertEquals(1, repository.detailCalls)
            assertEquals(1, engine.playCalls.size)
            assertEquals("999", vm.currentTrack.value?.cid)
            // The stored playlist keeps the track as added; the cid
            // fill is playback-time only.
            assertEquals("", vm.playlists.value.single().tracks.single().cid)
        }
}
