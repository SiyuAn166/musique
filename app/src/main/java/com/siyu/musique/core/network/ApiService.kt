package com.siyu.musique.core.network

import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.core.logging.MusiqueLogger
import com.siyu.musique.model.Track

/**
 * Bilibili API semantics: which endpoint, which parameters, and how
 * the JSON becomes domain models. Transport (headers, cookies,
 * retries, signing) lives in [BiliClient]; parsing lives in
 * [Parsers.kt]. Logging names ids and outcomes only, never URLs with
 * query strings or response bodies.
 */
class ApiService(
    private val client: BiliClient,
    private val logger: MusiqueLogger = MusiqueLog.shared,
) {

    /**
     * Keyword search, first page only, unsigned, keyword only: no
     * page parameter in v0.1. Returns video results only.
     */
    suspend fun search(keyword: String): List<Track> {
        val url = "$SEARCH_URL?keyword=${WbiSigner.percentEncode(keyword)}"
        return parseSearchResults(client.getData(url, signed = false))
    }

    /**
     * Video details for [bvid]: the signed wbi/view path (plain
     * /x/web-interface/view fails anonymously with HTTP 412 even when
     * signed, verified in smoke test 2). On any failure, falls back
     * to the unsigned pagelist, which gives the first part's cid but
     * no title, artist, or cover: the fallback track carries empty
     * strings there and the caller merges it with the search result.
     */
    suspend fun fetchDetail(bvid: String): Track {
        val detailUrl = "$WBI_VIEW_URL?bvid=${WbiSigner.percentEncode(bvid)}"
        try {
            return parseViewDetail(client.getData(detailUrl, signed = true), bvid)
        } catch (detailError: Exception) {
            logger.w(
                TAG,
                "detail wbi/view failed for $bvid " +
                    "(${detailError::class.simpleName}), trying pagelist",
            )
            try {
                val pagelistUrl =
                    "$PAGELIST_URL?bvid=${WbiSigner.percentEncode(bvid)}"
                val entry = parsePagelist(client.getData(pagelistUrl, signed = false))
                logger.i(TAG, "detail pagelist fallback ok for $bvid cid=${entry.cid}")
                return Track(
                    id = bvid,
                    cid = entry.cid,
                    title = "",
                    artist = "",
                    cover = "",
                    duration = entry.duration,
                )
            } catch (fallbackError: Exception) {
                logger.e(
                    TAG,
                    "detail failed for $bvid (${fallbackError::class.simpleName})",
                )
                fallbackError.addSuppressed(detailError)
                throw fallbackError
            }
        }
    }

    /**
     * Stream URL for [bvid] + [cid]: /x/player/playurl, fnval 4048,
     * no qn, unsigned (same transport as search). Returns the picked
     * DASH audio stream: 30280 first, then 30232, then 30216, never
     * Dolby/Hi-Res. The URL expires, so callers resolve fresh on
     * every play intent and never cache it.
     */
    suspend fun fetchPlayUrl(bvid: String, cid: String): AudioStream {
        val url = "$PLAYURL_URL?bvid=${WbiSigner.percentEncode(bvid)}" +
            "&cid=${WbiSigner.percentEncode(cid)}&fnval=4048"
        return parsePlayUrl(client.getData(url, signed = false))
    }

    /**
     * WBI keys from /x/web-interface/nav. Anonymous calls answer code
     * -101 while still carrying data.wbi_img, so -101 is allowed.
     */
    suspend fun fetchNavKeys(): WbiKeyStore.WbiKeys {
        val envelope = client.getEnvelope(NAV_URL, signed = false, allowCodes = setOf(-101))
        return parseNavKeys(envelope.data)
    }

    companion object {
        const val TAG = "ApiService"
        const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/search/all/v2"
        const val WBI_VIEW_URL = "https://api.bilibili.com/x/web-interface/wbi/view"
        const val PAGELIST_URL = "https://api.bilibili.com/x/player/pagelist"
        const val PLAYURL_URL = "https://api.bilibili.com/x/player/playurl"
        const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    }
}
