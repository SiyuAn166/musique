package com.siyu.musique.core.network

import java.net.URLEncoder

/**
 * WBI request signing, verified live against /x/web-interface/wbi/view
 * (see the pre-dev smoke test 2 report). Keys come from GenWebTicket
 * nav or from /x/web-interface/nav; they are never hardcoded here.
 *
 * Signing: filter the characters ! ' ( ) * out of every value, sort
 * the keys, add wts (unix seconds), then w_rid is the MD5 of the
 * percent-encoded query string plus the mixin key. Spaces encode as
 * %20, never +.
 */
object WbiSigner {

    /** The 64-entry reorder table for the mixin key. */
    val mixinTable: IntArray = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62,
        11, 36, 20, 34, 44, 52,
    )

    private val filteredChars = setOf('!', '\'', '(', ')', '*')

    /**
     * The 32-character mixin key: imgKey + subKey reordered by
     * [mixinTable], first 32 characters kept. Both keys together must
     * be at least 64 characters.
     */
    fun mixinKeyOf(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        require(raw.length >= 64) {
            "WBI keys too short: imgKey+subKey is ${raw.length} chars, need at least 64"
        }
        return buildString(32) {
            for (i in 0 until 32) append(raw[mixinTable[i]])
        }
    }

    /** The key carried by a WBI image URL: the filename without extension. */
    fun keyFromImageUrl(url: String): String {
        val filename = url.substringAfterLast('/')
        return filename.substringBefore('.')
    }

    /**
     * Signs [params] with [mixinKey] at [wtsSeconds] and returns the
     * full query string, w_rid included. Callers append it to the URL
     * after '?'.
     */
    fun sign(params: Map<String, String>, mixinKey: String, wtsSeconds: Long): String {
        val all = (params + ("wts" to wtsSeconds.toString()))
            .mapValues { (_, value) -> value.filterNot { it in filteredChars } }
            .toSortedMap()
        val query = all.entries.joinToString("&") { (key, value) ->
            "${percentEncode(key)}=${percentEncode(value)}"
        }
        val wRid = md5Hex(query + mixinKey)
        return "$query&w_rid=$wRid"
    }

    /** Percent-encoding with space as %20 (never +). */
    fun percentEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
