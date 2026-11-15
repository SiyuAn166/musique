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
import com.siyu.musique.data.AudioFileDownloader
import com.siyu.musique.data.DownloadRecord
import com.siyu.musique.data.DownloadStore
import com.siyu.musique.data.InMemoryAppCache
import com.siyu.musique.data.InMemoryDownloadStore
import com.siyu.musique.data.InMemoryPlaybackModeStore
import com.siyu.musique.data.InMemoryPlaybackStateStore
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.NoOpAudioFileDownloader
import com.siyu.musique.data.defaultDownloadsDir
import com.siyu.musique.data.downloadFileName
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
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
 * Download progress for one (track, quality) pair. [fraction] is
 * 0..1 while downloading, null when the total size is unknown or
 * the stream has not resolved yet; [failed] is true after an
 * error, until a retry starts.
 */
data class DownloadProgress(val fraction: Float?, val failed: Boolean = false)

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
 * through [PlaylistStore]), plus the synthetic Downloads playlist
 * right after the default, derived from the download records and
 * never persisted. Creating, deleting, adding to, and removing from
 * playlists update the stored flow synchronously and persist
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
 *
 * Downloads: [downloadTrack] streams a track's audio at the
 * current quality preference into [downloadsDir] (app-private,
 * deleted on uninstall) and records it in [downloadStore].
 * Playback prefers a downloaded file over the network, and a
 * local file that fails to play is deleted with its record and
 * falls back to the network once, for the same intent.
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
    private val downloadStore: DownloadStore = InMemoryDownloadStore(),
    private val audioFileDownloader: AudioFileDownloader = NoOpAudioFileDownloader,
    private val downloadsDir: File = defaultDownloadsDir(),
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

    /** The resolved DASH stream id for the current track, null before resolve. The download button compares it against the stored records. */
    private val _resolvedQualityId = MutableStateFlow<Int?>(null)
    val resolvedQualityId: StateFlow<Int?> = _resolvedQualityId.asStateFlow()

    /** The downloaded files, in download order. Empty until the initial load lands. */
    private val _downloadRecords = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val downloadRecords: StateFlow<List<DownloadRecord>> = _downloadRecords.asStateFlow()

    /**
     * Downloads in flight or failed, keyed by track key while the
     * stream resolves, then by track key plus quality id (see
     * [downloadProgressKey]). Entries leave when the download
     * finishes; failed entries stay until a retry starts.
     */
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> =
        _downloadProgress.asStateFlow()

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

    /** The stored playlists in creation order, default first. Empty only until the initial load lands. Never holds the synthetic Downloads playlist: that one is derived in [playlists] and must never be persisted. */
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /**
     * The playlists the UI shows: the stored lists, with the
     * synthetic Downloads playlist inserted right after the
     * default, before the user playlists. The Downloads playlist
     * is derived from the download records (one row per song, in
     * download order), so it appears even when empty and updates
     * as songs download or are deleted. Empty only until the
     * initial playlist load lands, like the stored flow.
     */
    val playlists: StateFlow<List<Playlist>> = combine(
        _playlists,
        _downloadRecords,
    ) { stored, records ->
        if (stored.isEmpty()) {
            emptyList()
        } else {
            val downloads = Playlist.downloads(
                records.map { it.track }.distinctBy { it.key },
            )
            val defaultIndex = stored.indexOfFirst { it.isDefault }
            if (defaultIndex < 0) {
                listOf(downloads) + stored
            } else {
                stored.take(defaultIndex + 1) + downloads + stored.drop(defaultIndex + 1)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
     * through [updateQueue] so the two never drift apart. A queue
     * adopted from search keeps growing as more search pages load
     * (see [extendSearchQueue]); a playlist queue never does.
     */
    private var currentQueue: List<Track> = emptyList()

    /**
     * The search query the current queue came from, or null when
     * the queue came from a playlist, a restore, or nowhere yet.
     * Only a queue with a matching origin grows when that
     * search loads another page.
     */
    private var queueFromSearchQuery: String? = null

    /** The playing queue, for the queue sheet on the player page. */
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    /** Adopts [newQueue] as the queue, in both the field and the exposed flow. */
    private fun updateQueue(newQueue: List<Track>) {
        currentQueue = newQueue
        _queue.value = newQueue
    }

    /**
     * The parts of the video currently playing, shown as indented
     * child rows under the video in the queue sheet. Learned from
     * the details call when a track plays, in the background
     * after the hand-off for a track whose cid was already
     * filled, or on demand when the sheet opens. Empty while
     * unknown or for single-part videos.
     */
    private val _currentParts = MutableStateFlow<List<Track>>(emptyList())
    val currentParts: StateFlow<List<Track>> = _currentParts.asStateFlow()

    /**
     * Parts learned for videos expanded in the search results or
     * a playlist, keyed by bvid. Separate from [_currentParts],
     * which only ever holds the playing video's parts: list
     * expansion must work for any video, playing or not, and the
     * entries stay cached for the session so expanding the same
     * video twice never refetches. A failed load caches nothing,
     * so the next expand tap retries.
     */
    private val _videoParts = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val videoParts: StateFlow<Map<String, List<Track>>> = _videoParts.asStateFlow()

    /** Video ids whose parts are loading right now, so an expand tap never fires twice. */
    private val partsLoading = mutableSetOf<String>()

    /**
     * Loads [video]'s parts into [videoParts] for list expansion,
     * through the cheap parts probe. A no-op when the parts are
     * already known or a load is in flight. A failure only logs:
     * the row shows no chevron and the next tap retries.
     */
    fun loadVideoParts(video: Track) {
        if (_videoParts.value.containsKey(video.id) || video.id in partsLoading) return
        partsLoading += video.id
        viewModelScope.launch {
            try {
                val parts = repository.fetchParts(video)
                _videoParts.value = _videoParts.value + (video.id to parts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "parts load failed bvid=${video.id} ${describeError(e)}")
            } finally {
                partsLoading -= video.id
            }
        }
    }

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

    /** Download jobs in flight, keyed by track key: a second tap while downloading is ignored. */
    private val downloadJobs = mutableMapOf<String, Job>()

    /**
     * The record the engine is currently playing from a file, or
     * null when it plays a network stream. Cleared on every new
     * intent: a local file that errors is deleted with its record
     * and falls back to the network, once.
     */
    private var playingLocalRecord: DownloadRecord? = null

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
                // The saved key may name a part ("bvid:cid") of a
                // queue video instead of a queue member itself:
                // the queue holds videos, while playback saves
                // the part that was playing. A key whose video id
                // matches a queue member restores that member
                // with the saved part's cid; any other key
                // restores nothing.
                val current = restored.queue.firstOrNull { it.key == restored.currentKey }
                    ?: restored.queue.firstOrNull {
                        it.id == restored.currentKey.substringBefore(":")
                    }?.let { member ->
                        val savedCid = restored.currentKey.substringAfter(":", "")
                        if (savedCid.isEmpty()) null else member.copy(cid = savedCid)
                    }
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
                playlist.copy(tracks = playlist.tracks.deduplicatedByKey())
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
        viewModelScope.launch {
            val stored = runCatching { downloadStore.load() }
                .onFailure {
                    logger.w(DOWNLOAD_TAG, "download load failed (${it::class.simpleName})")
                }
                .getOrNull() ?: emptyList()
            _downloadRecords.value = stored
            cleanupOrphanDownloads(stored)
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
                val localRecord = playingLocalRecord
                if (!refetchedForIntent && (error.isHttpFailure || localRecord != null)) {
                    if (localRecord != null) {
                        // Self-healing: the local file failed
                        // (missing or corrupt), so drop it and its
                        // record, then fall back to the network for
                        // this intent, once, like the HTTP retry.
                        playingLocalRecord = null
                        removeDownloadFile(localRecord)
                        removeDownloadRecord(localRecord)
                        logger.i(
                            DOWNLOAD_TAG,
                            "local file failed, falling back to network " +
                                "key=${localRecord.track.key} quality=${localRecord.qualityId}",
                        )
                    }
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
                extendSearchQueue(result.tracks, state.query)
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
        playAt(track, startPositionMs = 0L, resumePlaying = true)
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
        queueFromSearchQuery = null
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
     * Loads the current video's parts on demand, for the indented
     * part rows in the queue sheet. A no-op when the parts are
     * already known for this video or nothing is loaded. A
     * failure leaves the parts unknown (the sheet shows the
     * queue only) and is logged, never surfaced.
     */
    fun loadPartsForCurrent() {
        val current = _currentTrack.value ?: return
        learnPartsForVideo(current)
    }

    /**
     * Fetches [video]'s parts in the background and keeps them
     * when the same video is still current: part-aware navigation
     * and the queue sheet's tree rows need the parts even for
     * tracks whose cid was already filled (downloads, restored
     * playback), which never run the details call in [playAt].
     * Deduped by [partsKnownForVideoId]: same-video part switches
     * never refetch. A failure only logs.
     */
    private fun learnPartsForVideo(video: Track) {
        if (partsKnownForVideoId == video.id) return
        viewModelScope.launch {
            val detailed = runCatching { repository.fetchDetail(video) }
                .onFailure {
                    logger.w(TAG, "parts load failed (${it::class.simpleName})")
                }
                .getOrNull() ?: return@launch
            // Only keep the result when the same video still plays.
            if (_currentTrack.value?.id == video.id) {
                _currentParts.value = detailed.parts
                partsKnownForVideoId = video.id
            }
        }
    }

    /**
     * Play [part] from the indented part rows in the queue sheet:
     * the queue is left untouched, so the video's parts play
     * within the list the video came from. Tapping the loaded
     * part is a no-op, same as [play].
     */
    fun playPart(part: Track) {
        logger.i(TAG, "play intent=${playIntent + 1} bvid=${part.id} cid=${part.cid} part")
        if (isCurrentTrack(part)) {
            logger.i(TAG, "play ignored: already current bvid=${part.id} cid=${part.cid}")
            return
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
     * playlist playback survives a retry. An adopted search queue
     * remembers its query so later pages can extend it.
     */
    private fun adoptSearchQueueFor(track: Track) {
        val state = _searchState.value as? SearchUiState.Success ?: return
        if (state.tracks.any { it.id == track.id }) {
            updateQueue(state.tracks)
            queueFromSearchQuery = state.query
            shuffleHistory.clear()
        }
    }

    /**
     * Grows a search-adopted queue with a newly loaded search page.
     * Only when the queue came from this exact query: a playlist
     * queue, a restored queue, or a queue from another query never
     * grows. Tracks already in the queue (a re-run of the same
     * query) are skipped, so the queue never holds a video twice.
     * The shuffle history is untouched: growing the queue only
     * adds candidates, it never invalidates what already played.
     */
    private fun extendSearchQueue(tracks: List<Track>, query: String) {
        if (queueFromSearchQuery != query) return
        if (currentQueue.isEmpty()) return
        val existingIds = currentQueue.map { it.id }.toSet()
        val appended = tracks.filter { it.id !in existingIds }
        if (appended.isEmpty()) return
        updateQueue(currentQueue + appended)
        logger.i(
            TAG,
            "queue extended from search query=\"$query\" size=${currentQueue.size}",
        )
    }

    /**
     * Prefetches the next search page when playback gets close to
     * the end of a search-adopted queue with more pages, so the
     * queue grows before playback runs out of tracks even when
     * the user never scrolls the search list. The load itself is
     * [loadMore], which no-ops while a load is in flight or no
     * pages remain, and the landed page extends the queue through
     * [extendSearchQueue]. A no-op for playlist and restored
     * queues, and when the search has moved on to another query.
     */
    private fun prefetchSearchQueueIfNeeded() {
        val origin = queueFromSearchQuery ?: return
        val state = _searchState.value as? SearchUiState.Success ?: return
        if (state.query != origin || !state.hasMore) return
        val index = indexOfCurrent()
        if (index < 0) return
        if (index >= currentQueue.size - QUEUE_PREFETCH_THRESHOLD) {
            logger.d(TAG, "queue prefetch query=\"$origin\" index=$index size=${currentQueue.size}")
            loadMore()
        }
    }

    /**
     * The shared resolve-and-play flow: a new intent, cid fill when
     * missing, fresh play URL at the current quality preference,
     * hand-off to the engine. [startPositionMs] seeks into the new
     * item once the engine has it (quality changes resume where the
     * old item left off); [resumePlaying] false pauses immediately
     * after the hand-off, keeping a paused track paused. The queue
     * is never rewritten here: a multi-part video keeps the list
     * it came from, and part-aware navigation walks its parts
     * within that list. The details call learns the video's
     * parts; for a track whose cid was already filled, the parts
     * are learned in the background after the hand-off.
     */
    private fun playAt(
        track: Track,
        startPositionMs: Long,
        resumePlaying: Boolean,
    ) {
        playIntent++
        val intent = playIntent
        refetchedForIntent = false
        playingLocalRecord = null
        _resolvedQualityLabel.value = null
        _resolvedQualityId.value = null
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
        // Playing near the end of a search queue pulls the next
        // search page in before playback runs out of tracks.
        prefetchSearchQueueIfNeeded()
        playJob = viewModelScope.launch {
            try {
                var current = track
                if (current.cid.isEmpty()) {
                    val detailed = repository.fetchDetail(current)
                    if (intent != playIntent) return@launch
                    // The search list keeps the video-level row:
                    // the detailed form (first part's cid and
                    // duration) belongs to the playing track
                    // only, never back in the list, or a
                    // collection row would turn into its first
                    // song after playing.
                    _currentParts.value = detailed.parts
                    partsKnownForVideoId = current.id
                    current = detailed.track
                    _currentTrack.value = current
                }
                // A downloaded file wins over the network: the
                // current-quality file first, then any downloaded
                // file for the track, then the normal network path.
                val local = findLocalFile(current)
                val playUrl: String
                val qualityId: Int
                if (local != null) {
                    playUrl = File(downloadsDir, local.fileName).toURI().toString()
                    qualityId = local.qualityId
                    playingLocalRecord = local
                } else {
                    val stream = repository.fetchPlayUrl(current, _qualityPreference.value)
                    if (intent != playIntent) return@launch
                    playUrl = stream.baseUrl
                    qualityId = stream.qualityId
                }
                _resolvedQualityLabel.value = AudioQuality.labelForStreamId(qualityId)
                _resolvedQualityId.value = qualityId
                _currentTrack.value = current.copy(playUrl = if (local != null) "" else playUrl)
                engineIntent = intent
                engineHasMedia = true
                playerEngine.play(
                    playUrl,
                    cacheKey(current, qualityId),
                    PlayerMediaMetadata(current.title, current.artist, current.cover),
                )
                if (local != null) {
                    logger.i(
                        TAG,
                        "player play local intent=$intent quality=$qualityId " +
                            "file=${local.fileName}",
                    )
                } else {
                    logger.i(TAG, "player play intent=$intent quality=$qualityId")
                }
                // Parts for a cid-filled track were never learned
                // above: fetch them in the background so
                // part-aware navigation and the queue sheet's tree
                // rows work for downloads and restored playback
                // too. A no-op when the details call above
                // already learned them.
                learnPartsForVideo(current)
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
            // Set the state BEFORE resuming: ExoPlayer reports
            // onPlaying synchronously inside the resume call, so
            // setting Buffering after it would clobber the
            // Playing state and the mini bar would say Loading
            // forever. playAt and refetchAndPlay already order
            // their state writes this way.
            _playerState.value = PlayerUiState.Buffering
            _isPlaying.value = true
            playerEngine.resume()
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
     * history so previous can walk back; shuffle never walks
     * parts. Otherwise, when the current track is a part with a
     * later part in its video, the next part; else the next track
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
        val partIndex = currentPartIndex()
        if (partIndex >= 0 && partIndex + 1 < _currentParts.value.size) {
            return _currentParts.value[partIndex + 1]
        }
        val index = indexOfCurrent()
        if (index < 0) return null
        if (index + 1 < currentQueue.size) return currentQueue[index + 1]
        return if (_repeatMode.value == RepeatMode.ALL) currentQueue.first() else null
    }

    /**
     * The track a previous press should play. With shuffle on and a
     * non-empty history, the most recently played track. Otherwise,
     * when the current track is a part past the first in its video,
     * the previous part; else the previous track in queue order,
     * wrapping to the last when repeat-all is on; null at the start
     * of the queue with repeat off. Part walking is sequential
     * only: shuffle never walks parts, even with an empty history.
     */
    private fun pickPreviousTrack(): Track? {
        if (_shuffleEnabled.value && shuffleHistory.isNotEmpty()) {
            return shuffleHistory.removeLast()
        }
        if (!_shuffleEnabled.value) {
            val partIndex = currentPartIndex()
            if (partIndex > 0) return _currentParts.value[partIndex - 1]
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
     * The current track's position within [_currentParts], or -1
     * when the parts are unknown, single, or empty, or the current
     * track is not among them. Matched by [Track.key]: parts of
     * one video share a bvid, so the cid decides.
     */
    private fun currentPartIndex(): Int {
        val parts = _currentParts.value
        if (parts.size <= 1) return -1
        val current = _currentTrack.value ?: return -1
        return parts.indexOfFirst { it.key == current.key }
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
     * and the synthetic Downloads playlist cannot be deleted; an
     * unknown id is a no-op. The in-flight queue is a snapshot, so
     * deleting a playlist never disturbs playback already in
     * progress.
     */
    fun deletePlaylist(playlistId: String) {
        if (playlistId == Playlist.DOWNLOADS_ID) {
            logger.w(TAG, "playlist delete rejected: downloads playlist cannot be deleted")
            return
        }
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
     * Whether [candidate] duplicates [existing] as a playlist
     * entry. Same bvid with the same cid (both empty included) is
     * the same entry, and different parts of one video are
     * different entries. The mixed case, one side with an empty
     * cid (the whole collection) and the other naming a part,
     * duplicates only while the video is not known to have
     * parts: a collection added before its cid was filled in and
     * the same video added after must still count as one song,
     * but once the parts are known (the user expanded the video)
     * a part is its own entry, separate from the collection, so
     * both can sit in one playlist.
     */
    private fun isDuplicatePlaylistEntry(existing: Track, candidate: Track): Boolean {
        if (existing.id != candidate.id) return false
        if (existing.cid == candidate.cid) return true
        if (existing.cid.isNotEmpty() && candidate.cid.isNotEmpty()) return false
        val parts = _videoParts.value[candidate.id]
        return parts == null || parts.size <= 1
    }

    /**
     * Adds [track] to one playlist (stored without its play URL).
     * Returns true when the track was actually added, false when it
     * was already present or the playlist does not exist, so the UI
     * can report which one happened. The Downloads playlist is
     * never a target: it auto-fills on download, so an add there
     * is a no-op returning false.
     */
    fun addTrackToPlaylist(playlistId: String, track: Track): Boolean {
        if (playlistId == Playlist.DOWNLOADS_ID) {
            logger.w(TAG, "playlist add rejected: downloads playlist fills from downloads")
            return false
        }
        val target = _playlists.value.firstOrNull { it.id == playlistId } ?: return false
        val stored = track.copy(playUrl = "")
        if (target.tracks.any { isDuplicatePlaylistEntry(it, stored) }) {
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
     * nothing are all no-ops; other playlists are untouched. The
     * Downloads playlist is ignored here: deleting from it goes
     * through [deleteDownload], which removes the file as well.
     */
    fun removeTracksFromPlaylist(playlistId: String, trackKeys: Set<String>) {
        if (trackKeys.isEmpty()) return
        if (playlistId == Playlist.DOWNLOADS_ID) {
            logger.w(TAG, "playlist remove rejected: downloads delete through deleteDownload")
            return
        }
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

    /**
     * Downloads [track] at the current quality preference: the
     * details call first when the cid is missing, then a fresh
     * play URL, then the bytes streamed to a file in the
     * downloads dir. Progress lands in [downloadProgress]; a
     * failure deletes the partial file and marks the entry
     * failed, and tapping again retries. A track already
     * downloaded at the resolved quality is a no-op, as is a
     * second tap while a download is in flight.
     */
    fun downloadTrack(track: Track) {
        val jobKey = track.key
        if (downloadJobs[jobKey]?.isActive == true) return
        downloadJobs[jobKey] = viewModelScope.launch {
            var target: File? = null
            var downloaded = false
            var progressKey = jobKey
            try {
                publishDownloadProgress(jobKey, DownloadProgress(fraction = null))
                var current = track
                if (current.cid.isEmpty()) {
                    current = repository.fetchDetail(current).track
                }
                val stream = repository.fetchPlayUrl(current, _qualityPreference.value)
                progressKey = downloadProgressKey(current.key, stream.qualityId)
                if (progressKey != jobKey) {
                    removeDownloadProgress(jobKey)
                }
                val existing = _downloadRecords.value.firstOrNull {
                    it.track.key == current.key && it.qualityId == stream.qualityId
                }
                if (existing != null && File(downloadsDir, existing.fileName).exists()) {
                    removeDownloadProgress(progressKey)
                    logger.i(
                        DOWNLOAD_TAG,
                        "download skipped: already downloaded key=${current.key} " +
                            "quality=${stream.qualityId}",
                    )
                    return@launch
                }
                if (existing != null) {
                    // A record without its file: drop the stale
                    // record and download again.
                    removeDownloadRecord(existing)
                }
                target = File(downloadsDir, downloadFileName(current, stream.qualityId))
                audioFileDownloader.download(stream.baseUrl, target) { bytes, total ->
                    val fraction = if (total != null && total > 0) {
                        (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    publishDownloadProgress(progressKey, DownloadProgress(fraction))
                }
                val record = DownloadRecord(
                    track = current.copy(playUrl = ""),
                    qualityId = stream.qualityId,
                    fileName = target.name,
                    sizeBytes = target.length(),
                    downloadedAtMs = System.currentTimeMillis(),
                )
                downloaded = true
                val updated = _downloadRecords.value.filterNot {
                    it.track.key == record.track.key && it.qualityId == record.qualityId
                } + record
                _downloadRecords.value = updated
                persistDownloadRecords(updated)
                removeDownloadProgress(progressKey)
                logger.i(
                    DOWNLOAD_TAG,
                    "download done key=${record.track.key} quality=${record.qualityId} " +
                        "bytes=${record.sizeBytes}",
                )
            } catch (e: CancellationException) {
                // A cancelled download (deleted mid-flight) leaves
                // no progress entry behind: the spinner must not
                // linger on the player button.
                clearDownloadProgressFor(jobKey)
                throw e
            } catch (e: Exception) {
                logger.e(DOWNLOAD_TAG, "download failed key=${track.key} ${describeError(e)}")
                publishDownloadProgress(
                    progressKey,
                    DownloadProgress(fraction = null, failed = true),
                )
            } finally {
                if (!downloaded) {
                    runCatching { target?.delete() }
                }
                downloadJobs.remove(jobKey)
            }
        }
    }

    /**
     * Deletes one download: cancels a download in flight for the
     * same track, removes the file and the record, and persists.
     * A missing file or record is a no-op.
     */
    fun deleteDownload(record: DownloadRecord) {
        downloadJobs[record.track.key]?.cancel()
        clearDownloadProgressFor(record.track.key)
        removeDownloadFile(record)
        removeDownloadRecord(record)
        logger.i(
            DOWNLOAD_TAG,
            "download deleted key=${record.track.key} quality=${record.qualityId} " +
                "file=${record.fileName}",
        )
    }

    /**
     * The downloaded file to play for [track]: the record matching
     * the current quality preference's target stream id when the
     * preference names one, else the highest downloaded quality.
     * Records whose file is gone are dropped (and persisted away)
     * instead of ever being handed to the engine.
     */
    private fun findLocalFile(track: Track): DownloadRecord? {
        val records = _downloadRecords.value.filter { it.track.key == track.key }
        if (records.isEmpty()) return null
        val present = records.filter { File(downloadsDir, it.fileName).exists() }
        val stale = records.filter { it !in present }
        if (stale.isNotEmpty()) {
            val updated = _downloadRecords.value - stale.toSet()
            _downloadRecords.value = updated
            persistDownloadRecords(updated)
            logger.i(
                DOWNLOAD_TAG,
                "stale download records dropped count=${stale.size} key=${track.key}",
            )
        }
        if (present.isEmpty()) return null
        val preference = _qualityPreference.value
        if (preference != AudioQuality.BEST) {
            present.firstOrNull { it.qualityId == preference.tryOrder.first() }
                ?.let { return it }
        }
        return present.maxByOrNull { it.qualityId }
    }

    /**
     * Deletes files in the downloads dir that no record names:
     * partial files left by a download killed mid-flight. Runs
     * once at startup, after the records load. A missing or
     * unreadable dir is a no-op.
     */
    private fun cleanupOrphanDownloads(records: List<DownloadRecord>) {
        val kept = records.mapTo(mutableSetOf()) { it.fileName }
        val files = runCatching { downloadsDir.listFiles() }.getOrNull() ?: return
        for (file in files) {
            if (file.isFile && file.name !in kept) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    logger.i(DOWNLOAD_TAG, "orphan download deleted file=${file.name}")
                }
            }
        }
    }

    /** Deletes the file for [record], ignoring failures: the record removal is what matters. */
    private fun removeDownloadFile(record: DownloadRecord) {
        runCatching { File(downloadsDir, record.fileName).delete() }
    }

    /** Drops [record] from the flow and persists, a no-op when it is not there. */
    private fun removeDownloadRecord(record: DownloadRecord) {
        val updated = _downloadRecords.value.filterNot {
            it.track.key == record.track.key && it.qualityId == record.qualityId
        }
        if (updated.size == _downloadRecords.value.size) return
        _downloadRecords.value = updated
        persistDownloadRecords(updated)
    }

    /** Persists [records] in the background; a failure is logged and swallowed. */
    private fun persistDownloadRecords(records: List<DownloadRecord>) {
        viewModelScope.launch {
            runCatching { downloadStore.save(records) }
                .onFailure {
                    logger.w(DOWNLOAD_TAG, "download save failed (${it::class.simpleName})")
                }
        }
    }

    private fun publishDownloadProgress(key: String, progress: DownloadProgress) {
        _downloadProgress.value = _downloadProgress.value + (key to progress)
    }

    private fun removeDownloadProgress(key: String) {
        if (key !in _downloadProgress.value) return
        _downloadProgress.value = _downloadProgress.value - key
    }

    /**
     * Drops every progress entry for [trackKey], in both key
     * shapes: the bare track key while resolving, and the track
     * key plus quality id once the stream resolved.
     */
    private fun clearDownloadProgressFor(trackKey: String) {
        val remaining = _downloadProgress.value.filterKeys { key ->
            key != trackKey && !key.startsWith("$trackKey:")
        }
        if (remaining.size != _downloadProgress.value.size) {
            _downloadProgress.value = remaining
        }
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
     * The one fresh-URL retry after an HTTP playback failure (or
     * after a local file failed, whose record was already
     * dropped), for the same [intent]. Always the network path:
     * the local file is gone by the time this runs. A failure
     * here, or a second player error, stops in the error state.
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
                _resolvedQualityId.value = stream.qualityId
                engineIntent = intent
                engineHasMedia = true
                playingLocalRecord = null
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

    companion object {
        const val TAG = "Player"
        const val SEARCH_TAG = "Search"
        const val DOWNLOAD_TAG = "Download"

        /** Progress polling cadence while playing. */
        const val PROGRESS_POLL_INTERVAL_MS: Long = 500L

        /** Polls between playback-state saves while playing: 20 polls at 500ms is one save every 10 seconds, so a killed process still resumes near where it left off. */
        private const val PROGRESS_POLLS_PER_SAVE: Int = 20

        /** Cap on the shuffle history, so endless skipping cannot grow it forever. */
        private const val SHUFFLE_HISTORY_LIMIT = 50

        /**
         * How close to the end of a search queue playback must get
         * before the next search page is prefetched, so the queue
         * usually grows before playback runs out of tracks.
         */
        private const val QUEUE_PREFETCH_THRESHOLD = 3

        /** Keystroke quiet time before suggestions are fetched. */
        const val SUGGEST_DEBOUNCE_MS: Long = 300L
    }
}

/** Audio cache key: bvid + cid + quality id, never the (expiring) URL. */
fun cacheKey(track: Track, qualityId: Int): String = "${track.id}:${track.cid}:$qualityId"

/** Download progress map key for one (track, quality) pair: the track key plus the stream id. */
private fun downloadProgressKey(trackKey: String, qualityId: Int): String = "$trackKey:$qualityId"

/** Drops later entries with a key (bvid:cid) already kept, keeping the first of each. A stored collection (empty cid) and a stored part of the same video have different keys and both survive: they are distinct entries. */
private fun List<Track>.deduplicatedByKey(): List<Track> {
    val kept = mutableListOf<Track>()
    for (track in this) {
        if (kept.none { it.key == track.key }) kept.add(track)
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

/** Factory carrying the repository, the player engine, the app cache, the downloader and downloads dir, and the quality, playlist, playback mode, playback state, and download stores into [SearchViewModel]. No DI framework in v0.1. */
class SearchViewModelFactory(
    private val repository: SearchRepository,
    private val playerEngine: PlayerEngine = NoOpPlayerEngine,
    private val qualityPreferenceStore: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
    private val playlistStore: PlaylistStore = InMemoryPlaylistStore(),
    private val playbackModeStore: PlaybackModeStore = InMemoryPlaybackModeStore(),
    private val appCache: AppCache = InMemoryAppCache(),
    private val playbackStateStore: PlaybackStateStore = InMemoryPlaybackStateStore(),
    private val downloadStore: DownloadStore = InMemoryDownloadStore(),
    private val audioFileDownloader: AudioFileDownloader = NoOpAudioFileDownloader,
    private val downloadsDir: File = defaultDownloadsDir(),
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
            downloadStore,
            audioFileDownloader,
            downloadsDir,
        ) as T
    }
}
