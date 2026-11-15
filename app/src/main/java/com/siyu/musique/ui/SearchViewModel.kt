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
import com.siyu.musique.data.AppCache
import com.siyu.musique.data.InMemoryAppCache
import com.siyu.musique.data.InMemoryPlaybackModeStore
import com.siyu.musique.data.InMemoryPlaybackStateStore
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.PlaybackMode
import com.siyu.musique.data.PlaybackModeStore
import com.siyu.musique.data.PlaybackStateStore
import com.siyu.musique.data.PlaylistStore
import com.siyu.musique.data.SavedPlayback
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.data.describeError
import com.siyu.musique.model.Playlist
import com.siyu.musique.model.RepeatMode
import com.siyu.musique.model.Track
import com.siyu.musique.player.NoOpPlayerEngine
import com.siyu.musique.player.PlaybackControls
import com.siyu.musique.player.PlaybackControlsRegistry
import com.siyu.musique.player.PlaybackError
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.player.PlayerMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    private val playbackModeStore: PlaybackModeStore = InMemoryPlaybackModeStore(),
    private val appCache: AppCache = InMemoryAppCache(),
    private val playbackStateStore: PlaybackStateStore = InMemoryPlaybackStateStore(),
    private val random: Random = Random.Default,
) : ViewModel(), PlaybackControls {

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    /** Search suggestions for the current field text, or empty when none apply. */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

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

    /** Label of the resolved stream for the current track ("192K", "Hi-Res"), null before resolve. */
    private val _resolvedQualityLabel = MutableStateFlow<String?>(null)
    val resolvedQualityLabel: StateFlow<String?> = _resolvedQualityLabel.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    /**
     * Player volume, 0f (silent) to 1f (full). Full by default,
     * in memory only: it resets to full on restart. Applied to
     * the engine, which keeps it across track changes.
     */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** The playlists in creation order, default first. Empty only until the initial load lands. */
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** Total cached bytes (audio + covers), null until first measured. */
    private val _cacheSizeBytes = MutableStateFlow<Long?>(null)
    val cacheSizeBytes: StateFlow<Long?> = _cacheSizeBytes.asStateFlow()

    /** Whether a cache clear is running; the settings screen disables its clear button meanwhile. */
    private val _clearingCache = MutableStateFlow(false)
    val clearingCache: StateFlow<Boolean> = _clearingCache.asStateFlow()

    /** The app log lines, for the log screen. */
    val logLines: StateFlow<List<LogLine>> = logger.buffer.snapshot

    private var lastQuery: String = ""

    /**
     * The queue prev/next/auto-advance move inside: a snapshot of the
     * search list or playlist the current track was played from.
     * Mirrored in [_queue] for the queue sheet; every adoption goes
     * through [updateQueue] so the two never drift apart.
     */
    private var currentQueue: List<Track> = emptyList()

    /** The playing queue, for the queue sheet on the player page. */
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    /** Adopts [newQueue] as the queue, in both the field and the exposed flow. */
    private fun updateQueue(newQueue: List<Track>) {
        currentQueue = newQueue
        _queue.value = newQueue
    }

    /**
     * The parts of the video currently playing, for the parts
     * section of the queue sheet. Learned from the details call
     * when a track plays, or on demand when the sheet opens for
     * a track whose cid was already filled. Empty while unknown
     * or for single-part videos.
     */
    private val _currentParts = MutableStateFlow<List<Track>>(emptyList())
    val currentParts: StateFlow<List<Track>> = _currentParts.asStateFlow()

    /** The video id [currentParts] belongs to, or null while the parts are unknown. */
    private var partsKnownForVideoId: String? = null

    /**
     * Tracks played before the current one, newest last, recorded
     * only while shuffling: previous in shuffle mode walks back
     * through this instead of the queue order. Cleared whenever a
     * new queue is adopted, so it never points at another list's
     * tracks. Capped, so endless skipping cannot grow it forever.
     */
    private val shuffleHistory: ArrayDeque<Track> = ArrayDeque()

    private var playJob: Job? = null
    private var loadMoreJob: Job? = null
    private var progressJob: Job? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    /**
     * The query the latest keystroke asked suggestions for. A
     * suggestion fetch only publishes when its query still matches
     * this, so a slow response can never overwrite a newer query's
     * dropdown (or repopulate it after a search cleared it).
     */
    private var latestSuggestQuery: String = ""

    /** Incremented on every tap; stale async work checks against it. */
    private var playIntent: Int = 0

    /** The intent the engine is currently playing for; callbacks for older intents are stale. */
    private var engineIntent: Int = 0

    /** Whether the current intent already used its one fresh-URL retry. */
    private var refetchedForIntent: Boolean = false

    /** Whether the engine currently holds a loaded item (so resume works after pause). */
    private var engineHasMedia: Boolean = false

    init {
        // The background service's media session routes
        // notification, lock screen, and headset commands here.
        PlaybackControlsRegistry.handler = this
        viewModelScope.launch {
            runCatching { qualityPreferenceStore.load() }
                .onSuccess { stored -> stored?.let { _qualityPreference.value = it } }
                .onFailure {
                    logger.w(TAG, "quality preference load failed (${it::class.simpleName})")
                }
        }
        viewModelScope.launch {
            runCatching { playbackModeStore.load() }
                .onSuccess { stored ->
                    stored?.let {
                        _repeatMode.value = it.repeatMode
                        _shuffleEnabled.value = it.shuffleEnabled
                    }
                }
                .onFailure {
                    logger.w(TAG, "playback mode load failed (${it::class.simpleName})")
                }
        }
        viewModelScope.launch {
            val restored = runCatching { playbackStateStore.load() }
                .onFailure {
                    logger.w(TAG, "playback state load failed (${it::class.simpleName})")
                }
                .getOrNull()
            // Restore only when nothing has played yet: a tap
            // that landed before this load finished wins, and a
            // restore must never clobber it.
            if (restored != null && _currentTrack.value == null && playIntent == 0) {
                val current = restored.queue.firstOrNull { it.key == restored.currentKey }
                if (current != null) {
                    val duration = current.duration.inWholeMilliseconds.coerceAtLeast(0L)
                    updateQueue(restored.queue)
                    _currentTrack.value = current
                    _playbackProgress.value = PlaybackProgress(
                        restored.positionMs.coerceIn(0L, duration),
                        duration,
                    )
                    _playerState.value = PlayerUiState.Paused
                    _isPlaying.value = false
                    logger.i(
                        TAG,
                        "playback restored key=${current.key} " +
                            "positionMs=${_playbackProgress.value.positionMs}",
                    )
                }
            }
        }
        viewModelScope.launch {
            val result = runCatching { playlistStore.load() }
            result.onFailure {
                logger.w(TAG, "playlist load failed (${it::class.simpleName})")
            }
            val stored = result.getOrNull()
            val lists = ensureDefaultPlaylist(stored)
            // Drop songs stored twice (added before the dedupe fix
            // learned that an empty cid matches any part): keep the
            // first entry of each song.
            val deduped = lists.map { playlist ->
                playlist.copy(tracks = playlist.tracks.deduplicatedBySong())
            }
            _playlists.value = deduped
            // A successful load that came back empty (or without the
            // default) means first launch or a corrupt value: persist
            // the bootstrapped list. A load that held duplicates
            // persists the cleaned list once. A failed load never
            // persists, so a transient read failure cannot wipe
            // stored lists.
            val needsBootstrap = stored.isNullOrEmpty() || stored.none { it.isDefault }
            if (result.isSuccess && (needsBootstrap || deduped != lists)) {
                persistPlaylists(deduped)
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
                if (_repeatMode.value == RepeatMode.ONE) {
                    // Replay in place: seek to the start and resume
                    // on the loaded item, no fresh URL needed. The
                    // engine's onPlaying callback republishes the
                    // playing state and restarts progress polling.
                    _currentTrack.value?.let {
                        logger.i(TAG, "player repeat one bvid=${it.id}")
                        playerEngine.seekTo(0L)
                        playerEngine.resume()
                    }
                    return
                }
                val next = pickNextTrack()
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

    /**
     * The search field's text changed: debounce, then fetch
     * suggestions for it. A blank query clears the dropdown and
     * sends no request. Every keystroke cancels the previous
     * fetch, so only the latest text ever queries the server.
     * Failures leave the dropdown empty: suggestions are
     * best-effort and must never disturb searching.
     */
    fun onQueryChanged(query: String) {
        val trimmed = query.trim()
        latestSuggestQuery = trimmed
        suggestJob?.cancel()
        if (trimmed.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val fetched = try {
                repository.fetchSuggestions(trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (trimmed == latestSuggestQuery) {
                _suggestions.value = fetched
            }
        }
    }

    /** Hides the suggestion dropdown and drops any fetch in flight. */
    fun clearSuggestions() {
        latestSuggestQuery = ""
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    fun search(query: String) {
        val trimmed = query.trim()
        clearSuggestions()
        loadMoreJob?.cancel()
        // Cancel the previous search: a newer query (or a clear
        // back to Idle) must win, never be overwritten by a stale
        // result arriving late.
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _searchState.value = SearchUiState.Idle
            return
        }
        lastQuery = trimmed
        _searchState.value = SearchUiState.Loading
        searchJob = viewModelScope.launch {
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
     * the queue is left alone. Tapping the track that is already
     * loaded is a no-op: only switching songs restarts playback.
     */
    fun play(track: Track) {
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid}")
        if (isCurrentTrack(track)) {
            logger.i(TAG, "play ignored: already current bvid=${track.id} cid=${track.cid}")
            return
        }
        adoptSearchQueueFor(track)
        playAt(track, startPositionMs = 0L, resumePlaying = true, adoptPartsQueue = true)
    }

    /**
     * Play [track] from [playlist]: the same resolve-and-play flow
     * as [play], with the playlist's tracks (a snapshot at play time)
     * as the queue. Later edits to the playlist do not rewrite the
     * in-flight queue. A track with an empty cid resolves its
     * details first, through the existing flow. Tapping the track
     * that is already loaded is a no-op, same as [play].
     */
    fun playFromPlaylist(track: Track, playlist: Playlist) {
        logger.i(
            TAG,
            "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid} " +
                "playlist=${playlist.id}",
        )
        if (isCurrentTrack(track)) {
            logger.i(TAG, "play ignored: already current bvid=${track.id} cid=${track.cid}")
            return
        }
        updateQueue(playlist.tracks)
        shuffleHistory.clear()
        playAt(track, startPositionMs = 0L, resumePlaying = true)
    }

    /**
     * Play [track] from the queue sheet: the track must already be
     * in the queue, which is left untouched. Tapping the loaded
     * track is a no-op, same as [play].
     */
    fun playFromQueue(track: Track) {
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid} queue")
        if (isCurrentTrack(track)) {
            logger.i(TAG, "play ignored: already current bvid=${track.id} cid=${track.cid}")
            return
        }
        playKeepingQueue(track)
    }

    /**
     * Loads the current video's parts on demand, for the parts
     * section of the queue sheet. A no-op when the parts are
     * already known for this video or nothing is loaded. A
     * failure leaves the parts unknown (the sheet shows the
     * queue only) and is logged, never surfaced.
     */
    fun loadPartsForCurrent() {
        val current = _currentTrack.value ?: return
        if (partsKnownForVideoId == current.id) return
        viewModelScope.launch {
            val detailed = runCatching { repository.fetchDetail(current) }
                .onFailure {
                    logger.w(TAG, "parts load failed (${it::class.simpleName})")
                }
                .getOrNull() ?: return@launch
            // Only keep the result when the same video still plays.
            if (_currentTrack.value?.id == current.id) {
                _currentParts.value = detailed.parts
                partsKnownForVideoId = current.id
            }
        }
    }

    /**
     * Play [part] from the parts section: the parts become the
     * queue, so the video plays through in order from there.
     * Tapping the loaded part is a no-op, same as [play].
     */
    fun playPart(part: Track) {
        val parts = _currentParts.value
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${part.id} cid=${part.cid} part")
        if (isCurrentTrack(part)) {
            logger.i(TAG, "play ignored: already current bvid=${part.id} cid=${part.cid}")
            return
        }
        if (parts.size > 1) {
            updateQueue(parts)
            shuffleHistory.clear()
        }
        playKeepingQueue(part)
    }

    /**
     * Play [track] without touching the queue: previous/next,
     * auto-advance, and playback retry keep moving inside the queue
     * the current track came from. [startPositionMs] resumes into
     * the track: the restore path passes the saved position.
     */
    private fun playKeepingQueue(track: Track, startPositionMs: Long = 0L) {
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid}")
        playAt(track, startPositionMs = startPositionMs, resumePlaying = true)
    }

    /**
     * Saves the current queue, track, and position for the next
     * launch. Best effort: a failure is logged and forgotten.
     * Nothing is saved before the first play.
     */
    private fun persistPlaybackState() {
        val current = _currentTrack.value ?: return
        if (currentQueue.isEmpty()) return
        val state = SavedPlayback(
            queue = currentQueue,
            currentKey = current.key,
            positionMs = _playbackProgress.value.positionMs,
        )
        viewModelScope.launch {
            runCatching { playbackStateStore.save(state) }
                .onFailure {
                    logger.w(TAG, "playback state save failed (${it::class.simpleName})")
                }
        }
    }

    /**
     * True when [track] is the track already loaded in the engine,
     * so tapping it must change nothing. Same video, and the same
     * part when both sides name one: a row whose cid is still empty
     * names the video itself, which is what the resolve would play,
     * so it counts as the same track. The error state is excluded:
     * after a failure the tap retries instead of doing nothing.
     */
    private fun isCurrentTrack(track: Track): Boolean {
        val current = _currentTrack.value ?: return false
        if (_playerState.value is PlayerUiState.Error) return false
        return current.isSameSongAs(track)
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
            updateQueue(state.tracks)
            shuffleHistory.clear()
        }
    }

    /**
     * The shared resolve-and-play flow: a new intent, cid fill when
     * missing, fresh play URL at the current quality preference,
     * hand-off to the engine. [startPositionMs] seeks into the new
     * item once the engine has it (quality changes resume where the
     * old item left off); [resumePlaying] false pauses immediately
     * after the hand-off, keeping a paused track paused. When
     * [adoptPartsQueue] is true (search plays only) and the details
     * call finds more than one part, the parts become the queue so
     * a multi-part video plays through in order instead of stopping
     * after the first part; the search list row keeps the
     * video-level track while the player shows the part playing.
     */
    private fun playAt(
        track: Track,
        startPositionMs: Long,
        resumePlaying: Boolean,
        adoptPartsQueue: Boolean = false,
    ) {
        playIntent++
        val intent = playIntent
        refetchedForIntent = false
        _resolvedQualityLabel.value = null
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
        // A new video means new parts: forget the old list. Parts
        // of the same video survive part switches (same id), and
        // the details call below relearns them when it runs.
        if (track.id != partsKnownForVideoId) {
            _currentParts.value = emptyList()
            partsKnownForVideoId = null
        }
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
                    val detailed = repository.fetchDetail(current)
                    if (intent != playIntent) return@launch
                    updateTrackInList(detailed.track)
                    _currentParts.value = detailed.parts
                    partsKnownForVideoId = current.id
                    if (adoptPartsQueue && detailed.parts.size > 1) {
                        updateQueue(detailed.parts)
                        shuffleHistory.clear()
                        current = detailed.parts[0]
                    } else {
                        current = detailed.track
                    }
                    _currentTrack.value = current
                }
                val stream = repository.fetchPlayUrl(current, _qualityPreference.value)
                if (intent != playIntent) return@launch
                _resolvedQualityLabel.value = AudioQuality.labelForStreamId(stream.qualityId)
                _currentTrack.value = current.copy(playUrl = stream.baseUrl)
                engineIntent = intent
                engineHasMedia = true
                playerEngine.play(
                    stream.baseUrl,
                    cacheKey(current, stream.qualityId),
                    PlayerMediaMetadata(current.title, current.artist, current.cover),
                )
                logger.i(TAG, "player play intent=$intent quality=${stream.qualityId}")
                if (startPositionMs > 0) {
                    playerEngine.seekTo(startPositionMs)
                    publishProgress()
                }
                persistPlaybackState()
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
        persistPlaybackState()
        logger.d(TAG, "player seek intent=$playIntent positionMs=$clamped")
    }

    /**
     * Changes the audio quality preference, persists it, and, when a
     * track is loaded, re-resolves its play URL at the new quality
     * and resumes at the same position (and the same playing/paused
     * state). With nothing loaded, only the next play is affected.
     * A no-op when the preference is unchanged.
     */
    /** Set the player volume, clamped to [0f, 1f]. */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _volume.value = clamped
        playerEngine.setVolume(clamped)
    }

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
            PlayerUiState.Playing, PlayerUiState.Buffering -> pausePlayback()
            PlayerUiState.Paused -> resumePlayback()
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

    /** Pause playback. A no-op unless something is playing or buffering. */
    fun pausePlayback() {
        if (_playerState.value != PlayerUiState.Playing &&
            _playerState.value != PlayerUiState.Buffering
        ) {
            return
        }
        playerEngine.pause()
        stopProgressPolling()
        publishProgress()
        _playerState.value = PlayerUiState.Paused
        _isPlaying.value = false
        persistPlaybackState()
        logger.d(TAG, "player pause requested")
    }

    /** Resume playback. A no-op unless paused. With no media loaded yet (paused while fetching), the track is replayed instead. */
    fun resumePlayback() {
        if (_playerState.value != PlayerUiState.Paused) return
        if (engineHasMedia) {
            playerEngine.resume()
            _playerState.value = PlayerUiState.Buffering
            _isPlaying.value = true
            logger.d(TAG, "player resume requested")
        } else {
            // Paused before the engine ever got media (e.g.
            // paused while fetching, or a restored track):
            // replay the track at the shown position.
            // Through playKeepingQueue, not play: the tap
            // guard in play would treat this as a re-tap
            // of the current track and do nothing.
            _currentTrack.value?.let {
                playKeepingQueue(it, _playbackProgress.value.positionMs)
            }
        }
    }

    /** Notification, lock screen, and headset play/pause: the same toggle as the play/pause button. */
    override fun onToggleRequested() = togglePlayPause()

    /** Notification, lock screen, and headset next: the same skip as the next button. */
    override fun onNextRequested() = playNext()

    /** Notification, lock screen, and headset previous: the same skip as the previous button. */
    override fun onPreviousRequested() = playPrevious()

    fun playNext() {
        pickNextTrack()?.let { playKeepingQueue(it) }
    }

    fun playPrevious() {
        pickPreviousTrack()?.let { playKeepingQueue(it) }
    }

    /** Cycles the repeat mode OFF -> ALL -> ONE -> OFF and persists it. */
    fun cycleRepeatMode() {
        _repeatMode.value = _repeatMode.value.next()
        logger.i(TAG, "repeat mode ${_repeatMode.value}")
        persistPlaybackMode()
    }

    /** Toggles shuffle and persists it. The queue order is untouched. */
    fun toggleShuffle() {
        _shuffleEnabled.value = !_shuffleEnabled.value
        logger.i(TAG, "shuffle ${_shuffleEnabled.value}")
        persistPlaybackMode()
    }

    private fun persistPlaybackMode() {
        val mode = PlaybackMode(_repeatMode.value, _shuffleEnabled.value)
        viewModelScope.launch {
            runCatching { playbackModeStore.save(mode) }
                .onFailure {
                    logger.w(TAG, "playback mode save failed (${it::class.simpleName})")
                }
        }
    }

    /**
     * The track a next press (or auto-advance) should play. With
     * shuffle on, a random queue member other than the current
     * track, and the current track is pushed onto the shuffle
     * history so previous can walk back. Otherwise the next track
     * in queue order, wrapping to the first when repeat-all is on;
     * null at the end of the queue with repeat off.
     */
    private fun pickNextTrack(): Track? {
        val current = _currentTrack.value ?: return null
        if (currentQueue.isEmpty()) return null
        if (_shuffleEnabled.value) {
            val candidates = currentQueue.filter { it.key != current.key }
            if (shuffleHistory.size >= SHUFFLE_HISTORY_LIMIT) shuffleHistory.removeFirst()
            shuffleHistory.addLast(current)
            return candidates.ifEmpty { listOf(current) }.random(random)
        }
        val index = indexOfCurrent()
        if (index < 0) return null
        if (index + 1 < currentQueue.size) return currentQueue[index + 1]
        return if (_repeatMode.value == RepeatMode.ALL) currentQueue.first() else null
    }

    /**
     * The track a previous press should play. With shuffle on and a
     * non-empty history, the most recently played track; otherwise
     * the previous track in queue order, wrapping to the last when
     * repeat-all is on; null at the start of the queue with repeat
     * off.
     */
    private fun pickPreviousTrack(): Track? {
        if (_shuffleEnabled.value && shuffleHistory.isNotEmpty()) {
            return shuffleHistory.removeLast()
        }
        val index = indexOfCurrent()
        if (index < 0) return null
        if (index > 0) return currentQueue[index - 1]
        return if (_repeatMode.value == RepeatMode.ALL && currentQueue.isNotEmpty()) {
            currentQueue.last()
        } else {
            null
        }
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
    /**
     * Adds [track] to one playlist (stored without its play URL).
     * Returns true when the track was actually added, false when it
     * was already present or the playlist does not exist, so the UI
     * can report which one happened.
     */
    fun addTrackToPlaylist(playlistId: String, track: Track): Boolean {
        val target = _playlists.value.firstOrNull { it.id == playlistId } ?: return false
        val stored = track.copy(playUrl = "")
        if (target.tracks.any { it.isSameSongAs(stored) }) {
            logger.i(
                TAG,
                "playlist add skipped: already present bvid=${track.id} cid=${track.cid} " +
                    "playlist=$playlistId",
            )
            return false
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
        return true
    }

    /**
     * Removes the track with [trackKey] (bvid:cid) from the playlist
     * with [playlistId]. An unknown playlist id or a track that is
     * not in the playlist is a no-op.
     */
    fun removeTrackFromPlaylist(playlistId: String, trackKey: String) {
        removeTracksFromPlaylist(playlistId, setOf(trackKey))
    }

    /**
     * Removes every track whose key is in [trackKeys] from one
     * playlist, in a single update and a single persist: the
     * detail screen's checkbox selection deletes through here.
     * An empty set, an unknown playlist, or keys that match
     * nothing are all no-ops; other playlists are untouched.
     */
    fun removeTracksFromPlaylist(playlistId: String, trackKeys: Set<String>) {
        if (trackKeys.isEmpty()) return
        val target = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val removed = target.tracks.count { it.key in trackKeys }
        if (removed == 0) return
        val updated = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(tracks = playlist.tracks.filterNot { it.key in trackKeys })
            } else {
                playlist
            }
        }
        _playlists.value = updated
        logger.i(TAG, "playlist remove count=$removed playlist=$playlistId")
        persistPlaylists(updated)
    }

    /** Drops every buffered log line. */
    fun clearLogs() {
        logger.buffer.clear()
    }

    /** Measures the caches (audio + covers) for the settings screen. A failure leaves the last known size untouched. */
    fun loadCacheSize() {
        viewModelScope.launch {
            runCatching { appCache.sizeBytes() }
                .onSuccess { _cacheSizeBytes.value = it }
                .onFailure {
                    logger.w("Cache", "cache size failed (${it::class.simpleName})")
                }
        }
    }

    /** Clears the caches (audio + covers), then re-measures so the settings screen shows the freed size. Ignored while a clear is already running. */
    fun clearCache() {
        if (_clearingCache.value) return
        viewModelScope.launch {
            _clearingCache.value = true
            runCatching { appCache.clear() }
                .onSuccess { logger.i("Cache", "cache cleared") }
                .onFailure {
                    logger.w("Cache", "cache clear failed (${it::class.simpleName})")
                }
            _clearingCache.value = false
            loadCacheSize()
        }
    }

    override fun onCleared() {
        playJob?.cancel()
        progressJob?.cancel()
        if (PlaybackControlsRegistry.handler === this) {
            PlaybackControlsRegistry.handler = null
        }
        playerEngine.setListener(null)
        // No stop() here on purpose: the player is shared with
        // the background service, and clearing the ViewModel
        // (leaving the app) must not kill background playback.
        // The service stops the player when it is destroyed.
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
                _resolvedQualityLabel.value = AudioQuality.labelForStreamId(stream.qualityId)
                engineIntent = intent
                engineHasMedia = true
                playerEngine.play(
                    stream.baseUrl,
                    cacheKey(track, stream.qualityId),
                    PlayerMediaMetadata(track.title, track.artist, track.cover),
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

    /**
     * The current track's position in the queue, or -1. Parts of
     * one video share a bvid, so the lookup is by [Track.key]
     * (bvid:cid) first; it falls back to bvid alone for queue
     * entries whose cid was still empty when the queue was
     * snapshotted (a playlist track played before its details
     * resolved).
     */
    private fun indexOfCurrent(): Int {
        val current = _currentTrack.value ?: return -1
        val byKey = currentQueue.indexOfFirst { it.key == current.key }
        if (byKey >= 0) return byKey
        return currentQueue.indexOfFirst { it.id == current.id }
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
            var pollsSinceSave = 0
            while (true) {
                publishProgress()
                pollsSinceSave++
                if (pollsSinceSave >= PROGRESS_POLLS_PER_SAVE) {
                    pollsSinceSave = 0
                    persistPlaybackState()
                }
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

        /** Polls between playback-state saves while playing: 20 polls at 500ms is one save every 10 seconds, so a killed process still resumes near where it left off. */
        private const val PROGRESS_POLLS_PER_SAVE: Int = 20

        /** Cap on the shuffle history, so endless skipping cannot grow it forever. */
        private const val SHUFFLE_HISTORY_LIMIT = 50

        /** Keystroke quiet time before suggestions are fetched. */
        const val SUGGEST_DEBOUNCE_MS: Long = 300L
    }
}

/** Audio cache key: bvid + cid + quality id, never the (expiring) URL. */
fun cacheKey(track: Track, qualityId: Int): String = "${track.id}:${track.cid}:$qualityId"

/** Drops later entries that are the same song as an earlier one, keeping the first of each. */
private fun List<Track>.deduplicatedBySong(): List<Track> {
    val kept = mutableListOf<Track>()
    for (track in this) {
        if (kept.none { it.isSameSongAs(track) }) kept.add(track)
    }
    return kept
}

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

/** Factory carrying the repository, the player engine, the app cache, and the quality, playlist, playback mode, and playback state stores into [SearchViewModel]. No DI framework in v0.1. */
class SearchViewModelFactory(
    private val repository: SearchRepository,
    private val playerEngine: PlayerEngine = NoOpPlayerEngine,
    private val qualityPreferenceStore: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
    private val playlistStore: PlaylistStore = InMemoryPlaylistStore(),
    private val playbackModeStore: PlaybackModeStore = InMemoryPlaybackModeStore(),
    private val appCache: AppCache = InMemoryAppCache(),
    private val playbackStateStore: PlaybackStateStore = InMemoryPlaybackStateStore(),
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
            playbackModeStore,
            appCache,
            playbackStateStore,
        ) as T
    }
}
