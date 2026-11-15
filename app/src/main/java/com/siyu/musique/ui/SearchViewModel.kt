package com.siyu.musique.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.siyu.musique.core.logging.LogLine
import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.data.describeError
import com.siyu.musique.model.Track
import com.siyu.musique.player.NoOpPlayerEngine
import com.siyu.musique.player.PlaybackError
import com.siyu.musique.player.PlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 * The queue is the current search result list; previous/next move
 * inside it only and stop at either end.
 */
class SearchViewModel(
    private val repository: SearchRepository,
    private val playerEngine: PlayerEngine = NoOpPlayerEngine,
    private val logger: MusiqueLogger = MusiqueLog.shared,
) : ViewModel() {

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerUiState>(PlayerUiState.Idle)
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** The app log lines, for the log screen. */
    val logLines: StateFlow<List<LogLine>> = logger.buffer.snapshot

    private var lastQuery: String = ""
    private var playJob: Job? = null
    private var loadMoreJob: Job? = null

    /** Incremented on every tap; stale async work checks against it. */
    private var playIntent: Int = 0

    /** The intent the engine is currently playing for; callbacks for older intents are stale. */
    private var engineIntent: Int = 0

    /** Whether the current intent already used its one fresh-URL retry. */
    private var refetchedForIntent: Boolean = false

    /** Whether the engine currently holds a loaded item (so resume works after pause). */
    private var engineHasMedia: Boolean = false

    init {
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
                logger.d(TAG, "player playing intent=$playIntent")
            }

            override fun onPaused() {
                if (engineIntent != playIntent) return
                _playerState.value = PlayerUiState.Paused
                _isPlaying.value = false
                logger.d(TAG, "player paused intent=$playIntent")
            }

            override fun onEnded() {
                if (engineIntent != playIntent) return
                _playerState.value = PlayerUiState.Paused
                _isPlaying.value = false
                logger.i(TAG, "player ended intent=$playIntent")
            }

            override fun onError(error: PlaybackError) {
                if (engineIntent != playIntent) return
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
     * land in [PlayerUiState.Error].
     */
    fun play(track: Track) {
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${track.id} cid=${track.cid}")
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
        _currentTrack.value = track
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
                val stream = repository.fetchPlayUrl(current)
                if (intent != playIntent) return@launch
                _currentTrack.value = current.copy(playUrl = stream.baseUrl)
                engineIntent = intent
                engineHasMedia = true
                playerEngine.play(
                    stream.baseUrl,
                    cacheKey(current, stream.qualityId),
                )
                logger.i(TAG, "player play intent=$intent quality=${stream.qualityId}")
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

    /** Replays the current track after a player error. Does nothing with no current track. */
    fun retryPlayback() {
        val track = _currentTrack.value ?: return
        play(track)
    }

    fun togglePlayPause() {
        when (_playerState.value) {
            PlayerUiState.Playing, PlayerUiState.Buffering -> {
                playerEngine.pause()
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
                _playerState.value = PlayerUiState.Paused
                _isPlaying.value = false
                logger.d(TAG, "player pause requested while fetching")
            }
            else -> Unit // Idle and Error: nothing to toggle; Error uses Retry.
        }
    }

    fun playNext() {
        val state = _searchState.value as? SearchUiState.Success ?: return
        val current = _currentTrack.value ?: return
        val index = state.tracks.indexOfFirst { it.id == current.id }
        if (index >= 0 && index + 1 < state.tracks.size) {
            play(state.tracks[index + 1])
        }
    }

    fun playPrevious() {
        val state = _searchState.value as? SearchUiState.Success ?: return
        val current = _currentTrack.value ?: return
        val index = state.tracks.indexOfFirst { it.id == current.id }
        if (index > 0) {
            play(state.tracks[index - 1])
        }
    }

    /** Drops every buffered log line. */
    fun clearLogs() {
        logger.buffer.clear()
    }

    override fun onCleared() {
        playJob?.cancel()
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
                val stream = repository.fetchPlayUrl(track)
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

    private fun updateTrackInList(detailed: Track) {
        val state = _searchState.value as? SearchUiState.Success ?: return
        _searchState.value = state.copy(
            tracks = state.tracks.map { if (it.id == detailed.id) detailed else it },
        )
    }

    companion object {
        const val TAG = "Player"
        const val SEARCH_TAG = "Search"
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

/** Factory carrying the repository and the player engine into [SearchViewModel]. No DI framework in v0.1. */
class SearchViewModelFactory(
    private val repository: SearchRepository,
    private val playerEngine: PlayerEngine = NoOpPlayerEngine,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SearchViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SearchViewModel(repository, playerEngine) as T
    }
}
