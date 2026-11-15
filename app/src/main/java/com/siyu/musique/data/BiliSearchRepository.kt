package com.siyu.musique.data

import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.av2bv
import com.siyu.musique.model.Track
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The real repository: search and details through [ApiService].
 * The bootstrap runs in the background from construction and never
 * blocks a search: search proceeds immediately, and the client's
 * serial request queue orders the calls.
 */
class BiliSearchRepository(
    private val apiService: ApiService,
    private val bootstrap: BiliBootstrap,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SearchRepository {

    init {
        scope.launch { bootstrap.ensureBootstrap() }
    }

    override suspend fun search(query: String): List<Track> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val directBvid = directBvid(trimmed)
        if (directBvid != null) {
            val placeholder = Track(
                id = directBvid,
                cid = "",
                title = "",
                artist = "",
                cover = "",
                duration = Duration.ZERO,
            )
            return listOf(fetchDetail(placeholder))
        }
        return apiService.search(trimmed)
    }

    override suspend fun fetchDetail(track: Track): Track {
        val detail = apiService.fetchDetail(track.id)
        return track.copy(
            cid = detail.cid,
            title = detail.title.ifEmpty { track.title },
            artist = detail.artist.ifEmpty { track.artist },
            cover = detail.cover.ifEmpty { track.cover },
            duration = if (detail.duration > Duration.ZERO) detail.duration else track.duration,
        )
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
        const val BV_LENGTH = 12
        val AV_INPUT = Regex("(?i)^av(\\d+)$")
    }
}
