package com.siyu.musique.data

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
interface SearchRepository {
    /**
     * Tracks matching [query]. A blank query sends no request and
     * returns an empty list. A BV id goes straight to details; an
     * "av<number>" input converts to a BV id first; anything else is
     * a keyword search.
     */
    suspend fun search(query: String): List<Track>

    /**
     * Fills in [track]'s cid (and title/artist/cover/duration when
     * the details call has them) from the video details endpoint,
     * with the pagelist as a cid-only fallback.
     */
    suspend fun fetchDetail(track: Track): Track
}
