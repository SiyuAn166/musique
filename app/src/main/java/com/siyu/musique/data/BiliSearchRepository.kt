package com.siyu.musique.data

import com.siyu.musique.core.logging.LogRedactor
import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliNetworkException
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.core.network.av2bv
import com.siyu.musique.model.Track
import kotlin.time.Duration
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

    init {
        scope.launch { bootstrap.ensureBootstrap() }
    }

    override suspend fun search(query: String): List<Track> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        logger.i(TAG, "search query=\"$trimmed\"")
        try {
            val directBvid = directBvid(trimmed)
            val tracks = if (directBvid != null) {
                val placeholder = Track(
                    id = directBvid,
                    cid = "",
                    title = "",
                    artist = "",
                    cover = "",
                    duration = Duration.ZERO,
                )
                listOf(fetchDetail(placeholder))
            } else {
                apiService.search(trimmed)
            }
            logger.i(TAG, "search ok count=${tracks.size}")
            return tracks
        } catch (e: Exception) {
            logger.e(TAG, "search failed: ${describeError(e)}")
            throw e
        }
    }

    override suspend fun fetchDetail(track: Track): Track {
        try {
            val detail = apiService.fetchDetail(track.id)
            val merged = track.copy(
                cid = detail.cid,
                title = detail.title.ifEmpty { track.title },
                artist = detail.artist.ifEmpty { track.artist },
                cover = detail.cover.ifEmpty { track.cover },
                duration = if (detail.duration > Duration.ZERO) detail.duration else track.duration,
            )
            logger.i(TAG, "detail ok bvid=${track.id} cid=${merged.cid}")
            return merged
        } catch (e: Exception) {
            logger.e(TAG, "detail failed for ${track.id}: ${describeError(e)}")
            throw e
        }
    }

    override suspend fun fetchPlayUrl(track: Track): AudioStream {
        if (track.cid.isEmpty()) {
            throw BiliParseException("Cannot resolve a play URL without a cid for ${track.id}")
        }
        try {
            val stream = apiService.fetchPlayUrl(track.id, track.cid)
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
