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
     * with the pagelist as a cid-only fallback.
     */
    suspend fun fetchDetail(track: Track): Track

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
}
