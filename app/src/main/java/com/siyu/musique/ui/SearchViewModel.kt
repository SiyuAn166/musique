package com.siyu.musique.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.siyu.musique.core.logging.LogLine
import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.PlaylistStore
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.data.describeError
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import com.siyu.musique.player.NoOpPlayerEngine
import com.siyu.musique.player.PlaybackError
import com.siyu.musique.player.PlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why a search failed, so the UI shows a distinct message per kind. */
enum class SearchErrorKind {
    /** Timeout, connection failure, or a non-200 HTTP status. */
    NETWORK,

    /** Bilibili risk control (-352 / -412). */
    BLOCKED,

    /** Too frequent (-799). */
    RATE_LIMITED,

    /** The response was not the JSON shape we expected. */
    PARSE,

    /** Any other Bilibili business error. */
    API,
}

/** Search states, per the v0.1 design. Empty results and errors are different states. */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState

    /**
     * The accumulated tracks for [query] across every page loaded so
     * far. [hasMore] is true while another page remains;
     * [loadingMore] is true while that page is being fetched, so the
     * list can show a loading row at the bottom.
     */
    data class Success(
        val tracks: List<Track>,
        val query: String,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
    ) : SearchUiState

    data class Empty(val query: String) : SearchUiState
    data class Error(val message: String, val kind: SearchErrorKind, val query: String) :
        SearchUiState
}

/** Playback states, per the v0.1 design. Fetching and buffering both show a loading state so a tap never looks dead. */
sealed interface PlayerUiState {
    /** Nothing selected yet. */
    data object Idle : PlayerUiState

    /** Resolving the cid and/or the play URL. */
    data object Fetching : PlayerUiState

    /** The engine has the URL and is loading audio. */
    data object Buffering : PlayerUiState

    /** Audio is playing. */
    data object Playing : PlayerUiState

    /** Paused, current item kept. */
    data object Paused : PlayerUiState

    /** Playback failed; the UI shows [message] with a Retry button. */
    data class Error(val message: String) : PlayerUiState
}

/**
 * Playback progress in milliseconds. [durationMs] is the engine's
 * duration when known, otherwise the current track's duration; 0
 * when neither is known. The UI derives the slider fraction and the
 * elapsed/remaining times from this.
 */
data class PlaybackProgress(val positionMs: Long, val durationMs: Long)

/**
 * Holds search state, the current queue, and playback state.
 *
 * Step 3: tapping a track fills in its cid when missing, resolves a
 * fresh play URL, and hands it to the [PlayerEngine] for streaming
 * playback. A detail or URL failure is a player error with a visible
 * message and Retry: unlike Step 1, a failed cid fill is no longer
 * silent, because playback needs the cid.
 *
 * Every tap gets an incrementing intent number: resolve results and
 * engine callbacks for an older intent are discarded, so tapping A
 * then B never plays A by mistake. On an HTTP playback failure the
 * URL is refetched once for the same intent; a second failure stops
 * in the error state.
 *
 * Step 4: progress is polled from the engine while playing and
 * exposed as [playbackProgress]; [seekTo] scrubs within the current
 * item; when a track ends, playback auto-advances to the next track
 * in the queue (stopping paused at the last one). The audio quality
 * preference ([qualityPreference]) is owned here, persisted through
 * [QualityPreferenceStore], and passed to every play URL resolve;
 * changing it while a track is loaded re-resolves the URL and
 * resumes at the same position.
 *
 * Playlists: [playlists] is the stored list in creation order, with
 * the default playlist always present (bootstrapped on first launch
 * through [PlaylistStore]). Creating, deleting, adding to, and
 * removing from playlists update the flow synchronously and persist
 * asynchronously; a failed persist is logged and swallowed, never
 * allowed to lose the in-memory state. Playlist mutations assume the
 * initial load has completed; in practice it finishes before any
 * playlist UI can be reached.
 *
 * The queue is a snapshot of the list the current track was played
 * from: the search results for [play], the playlist's tracks for
 * [playFromPlaylist]. Previous/next/auto-advance move inside that
 * snapshot only; later edits to the playlist do not rewrite the
 * in-flight queue. The queue is empty until the first play.
 */
class SearchViewModel(
    private val repository: SearchRepository,
    private val playerEngine: PlayerEngine = NoOpPlayerEngine,
    private val logger: MusiqueLogger = MusiqueLog.shared,
    private val qualityPreferenceStore: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
    private val playlistStore: PlaylistStore = InMemoryPlaylistStore(),
) : ViewModel() {

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerUiState>(PlayerUiState.Idle)
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(PlaybackProgress(0L, 0L))
    val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()

    private val _qualityPreference = MutableStateFlow(AudioQuality.BEST)
    val qualityPreference: StateFlow<AudioQuality> = _qualityPreference.asStateFlow()

    /** The playlists in creation order, default first. Empty only until the initial load lands. */
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** The app log lines, for the log screen. */
    val logLines: StateFlow<List<LogLine>> = logger.buffer.snapshot

    private var lastQuery: String = ""

    /**
     * The queue prev/next/auto-advance move inside: a snapshot of the
     * search list or playlist the current track was played from.
     */
    private var currentQueue: List<Track> = emptyList()
    private var playJob: Job? = null
    private var loadMoreJob: Job? = null
    private var progressJob: Job? = null

    /** Incremented on every tap; stale async work checks against it. */
    private var playIntent: Int = 0

    /** The intent the engine is currently playing for; callbacks for older intents are stale. */
    private var engineIntent: Int = 0

    /** Whether the current intent already used its one fresh-URL retry. */
    private var refetchedForIntent: Boolean = false

    /** Whether the engine currently holds a loaded item (so resume works after pause). */
    private var engineHasMedia: Boolean = false

    init {
        viewModelScope.launch {
            runCatching { qualityPreferenceStore.load() }
                .onSuccess { stored -> stored?.let { _qualityPreference.value = it } }
                .onFailure {
                    logger.w(TAG, "quality preference load failed (${it::class.simpleName})")
                }
        }
        viewModelScope.launch {
            val result = runCatching { playlistStore.load() }
            result.onFailure {
                logger.w(TAG, "playlist load failed (${it::class.simpleName})")
            }
            val stored = result.getOrNull()
            val lists = ensureDefaultPlaylist(stored)
            _playlists.value = lists
            // A successful load that came back empty (or without the
            // default) means first launch or a corrupt value: persist
            // the bootstrapped list. A failed load never persists,
            // so a transient read failure cannot wipe stored lists.
            if (result.isSuccess && (stored.isNullOrEmpty() || stored.none { it.isDefault })) {
                persistPlaylists(lists)
            }
        }
        playerEngine.setListener(object : PlayerEngine.Listener {
            override fun onBuffering() {
                if (engineIntent != playIntent) return
                _playerState.value = PlayerUiState.Buffering
                _isPlaying.value = true
                logger.d(TAG, "player buffering intent=$playIntent")
            }

            override fun onPlaying() {
                if (engineIntent != playIntent) return
                _playerState.value = PlayerUiState.Playing
                _isPlaying.value = true
                startProgressPolling()
                logger.d(TAG, "player playing intent=$playIntent")
            }

            override fun onPaused() {
                if (engineIntent != playIntent) return
                stopProgressPolling()
                publishProgress()
                _playerState.value = PlayerUiState.Paused
                _isPlaying.value = false
                logger.d(TAG, "player paused intent=$playIntent")
            }

            override fun onEnded() {
                if (engineIntent != playIntent) return
                stopProgressPolling()
                logger.i(TAG, "player ended intent=$playIntent")
                val next = trackAfterCurrent()
                if (next != null) {
                    logger.i(TAG, "player auto-advance bvid=${next.id}")
                    playKeepingQueue(next)
                } else {
                    publishProgress()
                    _playerState.value = PlayerUiState.Paused
                    _isPlaying.value = false
                }
            }

            override fun onError(error: PlaybackError) {
                if (engineIntent != playIntent) return
                stopProgressPolling()
                logger.e(
                    TAG,
                    "player error intent=$playIntent http=${error.isHttpFailure} " +
                        "message=${error.message}",
                )
                if (error.isHttpFailure && !refetchedForIntent) {
                    refetchedForIntent = true
                    refetchAndPlay(playIntent)
                } else {
                    engineHasMedia = false
                    _playerState.value =
                        PlayerUiState.Error("Playback failed: ${error.message}")
                    _isPlaying.value = false
                }
            }
        })
    }

    fun search(query: String) {
        val trimmed = query.trim()
        loadMoreJob?.cancel()
        if (trimmed.isEmpty()) {
            _searchState.value = SearchUiState.Idle
            return
        }
        lastQuery = trimmed
        _searchState.value = SearchUiState.Loading
        viewModelScope.launch {
            try {
                val tracks = repository.search(trimmed)
                _searchState.value = if (tracks.isEmpty()) {
                    SearchUiState.Empty(trimmed)
                } else {
                    SearchUiState.Success(
                        tracks,
                        trimmed,
                        hasMore = repository.hasMorePages,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchState.value = mapSearchError(e, trimmed)
            }
        }
    }

    /** Re-runs the last non-blank search, from page 1. Does nothing before any search. */
    fun retry() {
        if (lastQuery.isNotEmpty()) search(lastQuery)
    }

    /**
     * Loads the next page of the current keyword search and appends
     * it to the list. No-op unless the current state is a Success
     * with more pages and no load already in flight. A failure keeps
     * the loaded list and drops the loading row; the next scroll to
     * the bottom retries.
     */
    fun loadMore() {
        val state = _searchState.value as? SearchUiState.Success ?: return
        if (!state.hasMore || state.loadingMore) return
        _searchState.value = state.copy(loadingMore = true)
        loadMoreJob = viewModelScope.launch {
            try {
                val result = repository.loadMore()
                val current = _searchState.value as? SearchUiState.Success ?: return@launch
                if (current.query != state.query) return@launch
                _searchState.value = current.copy(
                    tracks = result.tracks,
                    hasMore = result.hasMore,
                    loadingMore = false,
                )
                logger.i(
                    SEARCH_TAG,
                    "search page loaded query=\"${state.query}\" page=${result.page} " +
                        "count=${result.tracks.size} hasMore=${result.hasMore}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = _searchState.value as? SearchUiState.Success
                if (current != null && current.query == state.query) {
                    _searchState.value = current.copy(loadingMore = false)
                }
                logger.e(SEARCH_TAG, "search page failed ${describeError(e)}")
            }
        }
    }

    /**
     * Play [track]: fill in the cid when missing, resolve a fresh play
     * URL, hand it to the engine. The tap is logged as the very first
     * statement, so it always leaves a trace even if something below
     * throws; a throwing engine stop is logged and swallowed, never
     * allowed to kill the tap. The current track and the fetching
     * state are set synchronously so a tap never looks dead; failures
     * land in [PlayerUiState.Error]. When [track] is in the current
     * search results, the search list becomes the queue; otherwise
     * the queue is left alone.
     */
    fun play(track: Track) {
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid}")
        adoptSearchQueueFor(track)
        playAt(track, startPositionMs = 0L, resumePlaying = true)
    }

    /**
     * Play [track] from [playlist]: the same resolve-and-play flow
     * as [play], with the playlist's tracks (a snapshot at play time)
     * as the queue. Later edits to the playlist do not rewrite the
     * in-flight queue. A track with an empty cid resolves its
     * details first, through the existing flow.
     */
    fun playFromPlaylist(track: Track, playlist: Playlist) {
        logger.i(
            TAG,
            "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid} " +
                "playlist=${playlist.id}",
        )
        currentQueue = playlist.tracks
        playAt(track, startPositionMs = 0L, resumePlaying = true)
    }

    /**
     * Play [track] without touching the queue: previous/next,
     * auto-advance, and playback retry keep moving inside the queue
     * the current track came from.
     */
    private fun playKeepingQueue(track: Track) {
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid}")
        playAt(track, startPositionMs = 0L, resumePlaying = true)
    }

    /**
     * When [track] is in the current search results, the search list
     * becomes the queue. When it is not (a retry, or a track played
     * while other results are showing), the queue is left alone so
     * playlist playback survives a retry.
     */
    private fun adoptSearchQueueFor(track: Track) {
        val state = _searchState.value as? SearchUiState.Success ?: return
        if (state.tracks.any { it.id == track.id }) {
            currentQueue = state.tracks
        }
    }

    /**
     * The shared resolve-and-play flow: a new intent, cid fill when
     * missing, fresh play URL at the current quality preference,
     * hand-off to the engine. [startPositionMs] seeks into the new
     * item once the engine has it (quality changes resume where the
     * old item left off); [resumePlaying] false pauses immediately
     * after the hand-off, keeping a paused track paused.
     */
    private fun playAt(track: Track, startPositionMs: Long, resumePlaying: Boolean) {
        playIntent++
        val intent = playIntent
        refetchedForIntent = false
        playJob?.cancel()
        try {
            playerEngine.stop()
        } catch (e: Exception) {
            logger.e(
                TAG,
                "player stop failed intent=$intent " +
                    "${e::class.simpleName}: ${e.message}",
            )
        }
        engineHasMedia = false
        stopProgressPolling()
        _currentTrack.value = track
        _playbackProgress.value = PlaybackProgress(
            startPositionMs,
            track.duration.inWholeMilliseconds.coerceAtLeast(0L),
        )
        _playerState.value = PlayerUiState.Fetching
        _isPlaying.value = true
        playJob = viewModelScope.launch {
            try {
                var current = track
                if (current.cid.isEmpty()) {
                    current = repository.fetchDetail(current)
                    if (intent != playIntent) return@launch
                    _currentTrack.value = current
                    updateTrackInList(current)
                }
                val stream = repository.fetchPlayUrl(current, _qualityPreference.value)
                if (intent != playIntent) return@launch
                _currentTrack.value = current.copy(playUrl = stream.baseUrl)
                engineIntent = intent
                engineHasMedia = true
                playerEngine.play(
                    stream.baseUrl,
                    cacheKey(current, stream.qualityId),
                )
                logger.i(TAG, "player play intent=$intent quality=${stream.qualityId}")
                if (startPositionMs > 0) {
                    playerEngine.seekTo(startPositionMs)
                    publishProgress()
                }
                if (!resumePlaying) {
                    playerEngine.pause()
                    _playerState.value = PlayerUiState.Paused
                    _isPlaying.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (intent != playIntent) return@launch
                logger.e(TAG, "play failed intent=$intent ${describeError(e)}")
                _playerState.value = PlayerUiState.Error(mapPlayerError(e))
                _isPlaying.value = false
            }
        }
    }

    /** Replays the current track after a player error, keeping the queue. Does nothing with no current track. */
    fun retryPlayback() {
        val track = _currentTrack.value ?: return
        playKeepingQueue(track)
    }

    /**
     * Seeks within the current item, clamped to [0, duration]. The
     * duration is the engine's when known, otherwise the track's;
     * with no known duration only the lower bound applies. Does
     * nothing when the engine holds no item. The UI calls this on
     * slider release; while the user drags, the slider shows the
     * dragged position locally and polling keeps running underneath.
     */
    fun seekTo(positionMs: Long) {
        if (!engineHasMedia) return
        val duration = effectiveDurationMs()
        val clamped = if (duration > 0) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        playerEngine.seekTo(clamped)
        _playbackProgress.value = PlaybackProgress(clamped, duration)
        logger.d(TAG, "player seek intent=$playIntent positionMs=$clamped")
    }

    /**
     * Changes the audio quality preference, persists it, and, when a
     * track is loaded, re-resolves its play URL at the new quality
     * and resumes at the same position (and the same playing/paused
     * state). With nothing loaded, only the next play is affected.
     * A no-op when the preference is unchanged.
     */
    fun setQualityPreference(preference: AudioQuality) {
        val previous = _qualityPreference.value
        if (previous == preference) return
        _qualityPreference.value = preference
        logger.i(TAG, "quality preference changed ${previous.label} -> ${preference.label}")
        viewModelScope.launch {
            runCatching { qualityPreferenceStore.save(preference) }
                .onFailure {
                    logger.w(TAG, "quality preference save failed (${it::class.simpleName})")
                }
        }
        val track = _currentTrack.value ?: return
        if (!engineHasMedia) return
        val position = playerEngine.currentPositionMs().coerceAtLeast(0L)
        val wasPlaying = _isPlaying.value
        logger.i(
            TAG,
            "quality re-resolve intent=${playIntent + 1} bvid=${track.id} " +
                "positionMs=$position quality=${preference.label}",
        )
        playAt(track, startPositionMs = position, resumePlaying = wasPlaying)
    }

    fun togglePlayPause() {
        when (_playerState.value) {
            PlayerUiState.Playing, PlayerUiState.Buffering -> {
                playerEngine.pause()
                stopProgressPolling()
                publishProgress()
                _playerState.value = PlayerUiState.Paused
                _isPlaying.value = false
                logger.d(TAG, "player pause requested")
            }
            PlayerUiState.Paused -> {
                if (engineHasMedia) {
                    playerEngine.resume()
                    _playerState.value = PlayerUiState.Buffering
                    _isPlaying.value = true
                    logger.d(TAG, "player resume requested")
                } else {
                    // Paused before the engine ever got media (e.g.
                    // paused while fetching): replay the track.
                    _currentTrack.value?.let { play(it) }
                }
            }
            PlayerUiState.Fetching -> {
                // Stop the in-flight resolve; resume replays the track.
                playJob?.cancel()
                playerEngine.stop()
                engineHasMedia = false
                stopProgressPolling()
                _playerState.value = PlayerUiState.Paused
                _isPlaying.value = false
                logger.d(TAG, "player pause requested while fetching")
            }
            else -> Unit // Idle and Error: nothing to toggle; Error uses Retry.
        }
    }

    fun playNext() {
        trackAfterCurrent()?.let { playKeepingQueue(it) }
    }

    fun playPrevious() {
        trackBeforeCurrent()?.let { playKeepingQueue(it) }
    }

    /**
     * Creates a playlist named [name]. Blank names are rejected;
     * duplicate names are allowed. The new playlist goes last, in
     * creation order.
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            logger.w(TAG, "playlist create rejected: blank name")
            return
        }
        val created = Playlist.create(trimmed)
        val updated = _playlists.value + created
        _playlists.value = updated
        logger.i(TAG, "playlist created id=${created.id} name=\"$trimmed\"")
        persistPlaylists(updated)
    }

    /**
     * Deletes the playlist with [playlistId]. The default playlist
     * cannot be deleted; an unknown id is a no-op. The in-flight
     * queue is a snapshot, so deleting a playlist never disturbs
     * playback already in progress.
     */
    fun deletePlaylist(playlistId: String) {
        val target = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (target.isDefault) {
            logger.w(TAG, "playlist delete rejected: default playlist cannot be deleted")
            return
        }
        val updated = _playlists.value.filterNot { it.id == playlistId }
        _playlists.value = updated
        logger.i(
            TAG,
            "playlist deleted id=$playlistId name=\"${target.name}\" " +
                "trackCount=${target.tracks.size}",
        )
        persistPlaylists(updated)
    }

    /**
     * Adds [track] to the playlist with [playlistId], at the end.
     * Adding a track already present (same bvid:cid) is a no-op and
     * is logged. The stored copy never carries a play URL. An
     * unknown playlist id is a no-op.
     */
    fun addTrackToPlaylist(playlistId: String, track: Track) {
        val target = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val stored = track.copy(playUrl = "")
        if (target.tracks.any { it.key == stored.key }) {
            logger.i(
                TAG,
                "playlist add skipped: already present bvid=${track.id} cid=${track.cid} " +
                    "playlist=$playlistId",
            )
            return
        }
        val updated = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(tracks = playlist.tracks + stored)
            } else {
                playlist
            }
        }
        _playlists.value = updated
        logger.i(TAG, "playlist add bvid=${track.id} cid=${track.cid} playlist=$playlistId")
        persistPlaylists(updated)
    }

    /**
     * Removes the track with [trackKey] (bvid:cid) from the playlist
     * with [playlistId]. An unknown playlist id or a track that is
     * not in the playlist is a no-op.
     */
    fun removeTrackFromPlaylist(playlistId: String, trackKey: String) {
        val target = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (target.tracks.none { it.key == trackKey }) return
        val updated = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(tracks = playlist.tracks.filterNot { it.key == trackKey })
            } else {
                playlist
            }
        }
        _playlists.value = updated
        logger.i(TAG, "playlist remove key=$trackKey playlist=$playlistId")
        persistPlaylists(updated)
    }

    /** Drops every buffered log line. */
    fun clearLogs() {
        logger.buffer.clear()
    }

    override fun onCleared() {
        playJob?.cancel()
        progressJob?.cancel()
        playerEngine.setListener(null)
        playerEngine.stop()
        playerEngine.release()
        super.onCleared()
    }

    /**
     * The one fresh-URL retry after an HTTP playback failure, for the
     * same [intent]. A failure here, or a second player error, stops
     * in the error state.
     */
    private fun refetchAndPlay(intent: Int) {
        val track = _currentTrack.value ?: return
        _playerState.value = PlayerUiState.Fetching
        logger.i(TAG, "player refetching URL once intent=$intent")
        playJob = viewModelScope.launch {
            try {
                val stream = repository.fetchPlayUrl(track, _qualityPreference.value)
                if (intent != playIntent) return@launch
                engineIntent = intent
                engineHasMedia = true
                playerEngine.play(
                    stream.baseUrl,
                    cacheKey(track, stream.qualityId),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (intent != playIntent) return@launch
                logger.e(TAG, "player refetch failed intent=$intent ${describeError(e)}")
                _playerState.value = PlayerUiState.Error(mapPlayerError(e))
                _isPlaying.value = false
            }
        }
    }

    /** The track after the current one in the queue, or null at the end (or with no queue). */
    private fun trackAfterCurrent(): Track? {
        val current = _currentTrack.value ?: return null
        val index = currentQueue.indexOfFirst { it.id == current.id }
        if (index < 0 || index + 1 >= currentQueue.size) return null
        return currentQueue[index + 1]
    }

    /** The track before the current one in the queue, or null at the start (or with no queue). */
    private fun trackBeforeCurrent(): Track? {
        val current = _currentTrack.value ?: return null
        val index = currentQueue.indexOfFirst { it.id == current.id }
        if (index <= 0) return null
        return currentQueue[index - 1]
    }

    /**
     * The stored list with the default playlist guaranteed present:
     * a missing or empty stored value bootstraps just the default,
     * and a stored list without the default gets it prepended.
     */
    private fun ensureDefaultPlaylist(stored: List<Playlist>?): List<Playlist> {
        if (stored.isNullOrEmpty()) return listOf(Playlist.default())
        if (stored.any { it.isDefault }) return stored
        return listOf(Playlist.default()) + stored
    }

    /** Persists [lists] in the background; a failure is logged and swallowed. */
    private fun persistPlaylists(lists: List<Playlist>) {
        viewModelScope.launch {
            runCatching { playlistStore.save(lists) }
                .onFailure {
                    logger.w(TAG, "playlist save failed (${it::class.simpleName})")
                }
        }
    }

    /** Starts the progress poll if it is not already running. Only runs while playing. */
    private fun startProgressPolling() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (true) {
                publishProgress()
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    /** One progress sample from the engine, with the track duration as the fallback. */
    private fun publishProgress() {
        val position = playerEngine.currentPositionMs().coerceAtLeast(0L)
        _playbackProgress.value = PlaybackProgress(position, effectiveDurationMs())
    }

    /** The engine's duration when known, otherwise the current track's duration, otherwise 0. */
    private fun effectiveDurationMs(): Long {
        val engineDuration = playerEngine.durationMs()
        if (engineDuration > 0) return engineDuration
        return _currentTrack.value?.duration?.inWholeMilliseconds?.coerceAtLeast(0L) ?: 0L
    }

    private fun updateTrackInList(detailed: Track) {
        val state = _searchState.value as? SearchUiState.Success ?: return
        _searchState.value = state.copy(
            tracks = state.tracks.map { if (it.id == detailed.id) detailed else it },
        )
    }

    companion object {
        const val TAG = "Player"
        const val SEARCH_TAG = "Search"

        /** Progress polling cadence while playing. */
        const val PROGRESS_POLL_INTERVAL_MS: Long = 500L
    }
}

/** Audio cache key: bvid + cid + quality id, never the (expiring) URL. */
fun cacheKey(track: Track, qualityId: Int): String = "${track.id}:${track.cid}:$qualityId"

/** The failure message and kind for an exception, shared by search and player errors. */
private fun describeFailure(error: Throwable): Pair<String, SearchErrorKind> = when (error) {
    is BiliNetworkException -> "Network problem. Check your connection and try again." to
        SearchErrorKind.NETWORK
    is BiliApiException -> when (error.code) {
        -352, -412 -> "Request blocked, try again shortly." to SearchErrorKind.BLOCKED
        -799 -> "Too many requests, try again shortly." to SearchErrorKind.RATE_LIMITED
        else -> "Bilibili returned an error (code ${error.code}). Try again." to
            SearchErrorKind.API
    }
    is BiliParseException -> "Bilibili changed its data format. Please try again later." to
        SearchErrorKind.PARSE
    else -> "Something went wrong. Try again." to SearchErrorKind.API
}

/** Maps a search failure onto the distinct error state the UI shows. */
fun mapSearchError(error: Throwable, query: String): SearchUiState.Error {
    val (message, kind) = describeFailure(error)
    return SearchUiState.Error(message = message, kind = kind, query = query)
}

/** Maps a playback failure (detail or play URL resolution) onto the message the player error state shows. */
fun mapPlayerError(error: Throwable): String = describeFailure(error).first

/** Factory carrying the repository, the player engine, and the quality and playlist stores into [SearchViewModel]. No DI framework in v0.1. */
class SearchViewModelFactory(
    private val repository: SearchRepository,
    private val playerEngine: PlayerEngine = NoOpPlayerEngine,
    private val qualityPreferenceStore: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
    private val playlistStore: PlaylistStore = InMemoryPlaylistStore(),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SearchViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SearchViewModel(
            repository,
            playerEngine,
            MusiqueLog.shared,
            qualityPreferenceStore,
            playlistStore,
        ) as T
    }
}
