package com.siyu.musique.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    data class Success(val tracks: List<Track>, val query: String) : SearchUiState
    data class Empty(val query: String) : SearchUiState
    data class Error(val message: String, val kind: SearchErrorKind, val query: String) :
        SearchUiState
}

/**
 * Holds search state, the current queue, and placeholder playback
 * state.
 *
 * Step 1: search hits the real Bilibili API through [SearchRepository];
 * a blank query sends no request and returns to Idle. Tapping a track
 * fills in its cid from the details endpoint and keeps the Step 0
 * mini-player placeholder behavior: the track is marked current and
 * "playing", but there is no sound yet (Step 3 wires playback to
 * ExoPlayer). A failed cid fill keeps the tapped track as-is; the
 * failure is best-effort in this step.
 *
 * The queue is the current search result list; previous/next move
 * inside it only and stop at either end.
 */
class SearchViewModel(
    private val repository: SearchRepository,
) : ViewModel() {

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var lastQuery: String = ""
    private var detailJob: Job? = null

    fun search(query: String) {
        val trimmed = query.trim()
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
                    SearchUiState.Success(tracks, trimmed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchState.value = mapSearchError(e, trimmed)
            }
        }
    }

    /** Re-runs the last non-blank search. Does nothing before any search. */
    fun retry() {
        if (lastQuery.isNotEmpty()) search(lastQuery)
    }

    fun play(track: Track) {
        detailJob?.cancel()
        _currentTrack.value = track
        _isPlaying.value = true
        if (track.cid.isNotEmpty()) return
        detailJob = viewModelScope.launch {
            try {
                val detailed = repository.fetchDetail(track)
                // Only apply the fill if this tap is still current:
                // a newer tap cancels this job, and a stale result
                // must never overwrite the current track.
                if (_currentTrack.value?.id == track.id) {
                    _currentTrack.value = detailed
                    updateTrackInList(detailed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort in Step 1: keep the tapped track and
                // the placeholder behavior. Step 3 surfaces detail
                // failures because playback needs the cid.
            }
        }
    }

    fun togglePlayPause() {
        if (_currentTrack.value != null) {
            _isPlaying.update { !it }
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

    private fun updateTrackInList(detailed: Track) {
        val state = _searchState.value as? SearchUiState.Success ?: return
        _searchState.value = state.copy(
            tracks = state.tracks.map { if (it.id == detailed.id) detailed else it },
        )
    }
}

/** Maps a search failure onto the distinct error state the UI shows. */
fun mapSearchError(error: Throwable, query: String): SearchUiState.Error = when (error) {
    is BiliNetworkException -> SearchUiState.Error(
        message = "Network problem. Check your connection and try again.",
        kind = SearchErrorKind.NETWORK,
        query = query,
    )
    is BiliApiException -> when (error.code) {
        -352, -412 -> SearchUiState.Error(
            message = "Request blocked, try again shortly.",
            kind = SearchErrorKind.BLOCKED,
            query = query,
        )
        -799 -> SearchUiState.Error(
            message = "Too many requests, try again shortly.",
            kind = SearchErrorKind.RATE_LIMITED,
            query = query,
        )
        else -> SearchUiState.Error(
            message = "Bilibili returned an error (code ${error.code}). Try again.",
            kind = SearchErrorKind.API,
            query = query,
        )
    }
    is BiliParseException -> SearchUiState.Error(
        message = "Bilibili changed its data format. Please try again later.",
        kind = SearchErrorKind.PARSE,
        query = query,
    )
    else -> SearchUiState.Error(
        message = "Something went wrong. Try again.",
        kind = SearchErrorKind.API,
        query = query,
    )
}

/** Factory carrying the repository into [SearchViewModel]. No DI framework in v0.1. */
class SearchViewModelFactory(
    private val repository: SearchRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SearchViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SearchViewModel(repository) as T
    }
}
