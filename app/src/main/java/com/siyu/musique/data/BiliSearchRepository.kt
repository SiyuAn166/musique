package com.siyu.musique.data

import com.siyu.musique.core.logging.LogRedactor
import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.core.network.av2bv
import com.siyu.musique.model.Track
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The real repository: search, details, and play URL resolution
 * through [ApiService]. The bootstrap runs in the background from
 * construction and never blocks a search: search proceeds
 * immediately, and the client's serial request queue orders the
 * calls.
 *
 * Logging names the query, ids, counts, and failure kinds only. Play
 * URLs are logged by CDN host, never in full.
 */
class BiliSearchRepository(
    private val apiService: ApiService,
    private val bootstrap: BiliBootstrap,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: MusiqueLogger = MusiqueLog.shared,
) : SearchRepository {

    /** The keyword search being paged, or null for BV/AV lookups and before any search. */
    private var currentQuery: String? = null

    /** The last page loaded for [currentQuery]; 0 when nothing is loaded. */
    private var currentPage: Int = 0

    /** Every track loaded so far for [currentQuery], deduped by bvid. */
    private var accumulated: List<Track> = emptyList()

    /** True while a [loadMore] fetch is in flight, so a second call no-ops instead of doubling the request. */
    private var loadingMore: Boolean = false

    /** Bumped on every new search, so a page fetched for an older search is dropped when it lands. */
    private var searchGeneration: Int = 0

    override var hasMorePages: Boolean = false
        private set

    init {
        scope.launch { bootstrap.ensureBootstrap() }
    }

    override suspend fun search(query: String): List<Track> {
        val trimmed = query.trim()
        resetPagination()
        if (trimmed.isEmpty()) return emptyList()
        logger.i(TAG, "search query=\"$trimmed\"")
        try {
            val directBvid = directBvid(trimmed)
            val tracks: List<Track>
            if (directBvid != null) {
                val placeholder = Track(
                    id = directBvid,
                    cid = "",
                    title = "",
                    artist = "",
                    cover = "",
                    duration = Duration.ZERO,
                )
                tracks = listOf(fetchDetail(placeholder).track)
                // Single-item lookups never page.
                currentPage = 1
                accumulated = tracks
                hasMorePages = false
            } else {
                val page = apiService.search(trimmed)
                tracks = page.tracks
                currentQuery = trimmed
                currentPage = 1
                accumulated = tracks
                hasMorePages = page.hasMore
            }
            logger.i(TAG, "search ok count=${tracks.size}")
            return tracks
        } catch (e: Exception) {
            logger.e(TAG, "search failed: ${describeError(e)}")
            throw e
        }
    }

    override suspend fun loadMore(): SearchPageResult {
        val query = currentQuery
            ?: return SearchPageResult(accumulated, currentPage, hasMorePages)
        if (loadingMore || !hasMorePages) {
            return SearchPageResult(accumulated, currentPage, hasMorePages)
        }
        val generation = searchGeneration
        loadingMore = true
        try {
            val nextPage = currentPage + 1
            val page = apiService.search(query, nextPage)
            // A new search started while this page was loading: drop
            // the stale page and keep the new search's list untouched.
            if (generation != searchGeneration) {
                return SearchPageResult(accumulated, currentPage, hasMorePages)
            }
            currentPage = nextPage
            hasMorePages = page.hasMore
            val seen = accumulated.mapTo(mutableSetOf()) { it.id }
            accumulated = accumulated + page.tracks.filter { seen.add(it.id) }
            logger.i(
                TAG,
                "search page query=\"$query\" page=$nextPage " +
                    "count=${page.tracks.size} hasMore=${page.hasMore}",
            )
            return SearchPageResult(accumulated, currentPage, hasMorePages)
        } catch (e: Exception) {
            logger.e(TAG, "search page failed: ${describeError(e)}")
            throw e
        } finally {
            loadingMore = false
        }
    }

    /** Forgets the current search's pages. The in-flight flag is left alone: the loading call owns it through its finally block. */
    private fun resetPagination() {
        searchGeneration++
        currentQuery = null
        currentPage = 0
        accumulated = emptyList()
        hasMorePages = false
    }

    override suspend fun fetchDetail(track: Track): DetailedTrack {
        try {
            val detail = apiService.fetchDetail(track.id)
            val video = detail.track
            // The cover prefers the track's own: the search cover is
            // already showing in the list, so it is known good, while
            // the detail pic has come back in a form that fails to
            // load (plain http:// before normalizeCover upgraded
            // it). The detail cover only fills an empty one.
            val merged = track.copy(
                cid = video.cid,
                title = video.title.ifEmpty { track.title },
                artist = video.artist.ifEmpty { track.artist },
                cover = track.cover.ifEmpty { video.cover },
                duration = if (video.duration > Duration.ZERO) video.duration else track.duration,
            )
            // Every part becomes its own track: the part name when
            // the video has one (multi-part compilations name each
            // song), the video title otherwise, each with its own
            // cid and duration. The search row keeps the video-level
            // merged track; playback walks the parts in order.
            val parts = detail.parts.map { part ->
                merged.copy(
                    cid = part.cid,
                    title = part.title.ifEmpty { merged.title },
                    duration = if (part.duration > Duration.ZERO) part.duration else merged.duration,
                )
            }.ifEmpty { listOf(merged) }
            logger.i(TAG, "detail ok bvid=${track.id} cid=${merged.cid} parts=${parts.size}")
            return DetailedTrack(merged, parts)
        } catch (e: Exception) {
            logger.e(TAG, "detail failed for ${track.id}: ${describeError(e)}")
            throw e
        }
    }

    override suspend fun fetchSuggestions(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            apiService.fetchSuggestions(trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "suggest failed: ${describeError(e)}")
            emptyList()
        }
    }

    override suspend fun fetchPlayUrl(track: Track, preference: AudioQuality): AudioStream {
        if (track.cid.isEmpty()) {
            throw BiliParseException("Cannot resolve a play URL without a cid for ${track.id}")
        }
        try {
            val stream = apiService.fetchPlayUrl(track.id, track.cid, preference)
            logger.i(
                TAG,
                "playurl bvid=${track.id} cid=${track.cid} " +
                    "quality=${stream.qualityId} host=${LogRedactor.hostOf(stream.baseUrl)}",
            )
            return stream
        } catch (e: Exception) {
            logger.e(TAG, "playurl failed for ${track.id}: ${describeError(e)}")
            throw e
        }
    }

    /**
     * A BV id (12 characters, "BV" + 10) goes straight to details.
     * An "av<number>" input converts to a BV id first. Anything else
     * returns null and is treated as a keyword.
     */
    private fun directBvid(trimmed: String): String? {
        if (trimmed.length == BV_LENGTH && trimmed.startsWith("BV")) return trimmed
        val avMatch = AV_INPUT.matchEntire(trimmed) ?: return null
        val aid = avMatch.groupValues[1].toLongOrNull() ?: return null
        return runCatching { av2bv(aid) }.getOrNull()
    }

    companion object {
        const val TAG = "Repository"
        const val BV_LENGTH = 12
        val AV_INPUT = Regex("(?i)^av(\\d+)$")
    }
}

/**
 * A failure kind for logs: business code, network, parse, or the
 * exception class. Never the message: messages and previews can carry
 * URLs or response bodies.
 */
fun describeError(error: Throwable): String = when (error) {
    is BiliApiException -> "api code ${error.code}"
    is BiliNetworkException -> "network"
    is BiliParseException -> "parse"
    else -> error::class.simpleName ?: "unknown"
}
