package com.siyu.musique.data

import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.core.network.AudioStream
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.model.Track

/**
 * The only data entry point, per the v0.1 design. The ViewModel and
 * UI talk to this interface only; the production implementation is
 * [BiliSearchRepository], tests use fakes.
 *
 * Errors surface as exceptions from the core network layer
 * ([com.siyu.musique.core.network.BiliApiException],
 * [com.siyu.musique.core.network.BiliNetworkException],
 * [com.siyu.musique.core.network.BiliParseException]) so the
 * ViewModel can show a distinct message per failure kind.
 */
/**
 * The accumulated result of loading one more search page: every
 * track loaded so far for the current query, the page just loaded,
 * and whether more pages remain.
 */
data class SearchPageResult(val tracks: List<Track>, val page: Int, val hasMore: Boolean)

/**
 * The result of resolving a track's details: the video-level
 * [track] (for the search list row) plus every [part] of the video
 * as its own track, in order. [parts] always holds at least the
 * video itself.
 */
data class DetailedTrack(val track: Track, val parts: List<Track>)

interface SearchRepository {
    /**
     * Tracks matching [query]. A blank query sends no request and
     * returns an empty list. A BV id goes straight to details; an
     * "av<number>" input converts to a BV id first; anything else is
     * a keyword search. A new search resets pagination to page 1.
     */
    suspend fun search(query: String): List<Track>

    /**
     * Whether the most recent search has another page to load.
     * False before any search, for single-item BV/AV lookups, and
     * once the last page is loaded. Repositories that do not page
     * keep the default false.
     */
    val hasMorePages: Boolean
        get() = false

    /**
     * Loads the next page of the current keyword search, appends it
     * to the accumulated list (deduped by bvid), and returns the
     * accumulated result. A no-op returning the current accumulated
     * list when there is no current query, a load is already in
     * flight, or there are no more pages. Repositories that do not
     * page keep the default, which reports no pages at all.
     */
    suspend fun loadMore(): SearchPageResult =
        SearchPageResult(emptyList(), page = 0, hasMore = false)

    /**
     * Fills in [track]'s cid (and title/artist/cover/duration when
     * the details call has them) from the video details endpoint,
     * with the pagelist as a cid-only fallback. [parts] holds every
     * part of the video as its own track (same bvid, per-part cid,
     * title, and duration); a single-part video yields one part.
     * Callers that play from search use the parts as the queue so
     * a multi-part video plays through instead of stopping after
     * the first part.
     */
    suspend fun fetchDetail(track: Track): DetailedTrack

    /**
     * Every part of [track] as its own track, for list expansion:
     * the part name when the video has one, the video title
     * otherwise, each with its own cid and duration. The default
     * goes through [fetchDetail]; implementations that can probe
     * the parts cheaply (the unsigned pagelist) override it, so a
     * list can learn which rows are collections without a signed
     * details call per row.
     */
    suspend fun fetchParts(track: Track): List<Track> = fetchDetail(track).parts

    /**
     * Resolves the playable DASH audio stream for [track], which must
     * already carry a cid (call [fetchDetail] first when it does
     * not), picking by [preference]'s try order. The URL is resolved
     * fresh on every call and never cached: play URLs expire. The
     * default implementation throws, so fakes that only care about
     * search keep compiling; the production repository and playback
     * fakes override it.
     */
    suspend fun fetchPlayUrl(
        track: Track,
        preference: AudioQuality = AudioQuality.BEST,
    ): AudioStream {
        throw BiliParseException("Play URL resolution is not implemented by this repository")
    }

    /**
     * Search suggestions for [query], in the server's order.
     * Best-effort by contract: a blank query sends no request, and
     * any failure yields an empty list instead of throwing, so the
     * suggestion dropdown can never disturb searching. The default
     * returns an empty list, so search-only fakes keep compiling.
     */
    suspend fun fetchSuggestions(query: String): List<String> = emptyList()
}
