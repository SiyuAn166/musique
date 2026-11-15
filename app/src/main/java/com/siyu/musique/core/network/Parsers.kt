package com.siyu.musique.core.network

import com.siyu.musique.model.Track
import com.siyu.musique.model.parseDurationText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Pure parsers for the Bilibili responses Step 1 uses. Every field
 * name here was confirmed by a live probe (Nov 14, 2026) or the Nov 9
 * smoke-test reports; nothing is guessed. Parsers take the envelope's
 * data element, never the raw body.
 */

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objectOrNull(key: String): JsonObject? =
    (this[key] as? JsonObject)

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    (this[key] as? JsonArray)

/** A JSON number or numeric string, read through [JsonPrimitive.content] to keep full precision. */
private fun JsonObject.contentOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun parseError(what: String, data: JsonElement): BiliParseException =
    BiliParseException(what, preview = data.toString().take(BiliClient.PREVIEW_LENGTH))

/**
 * Search results: data.result is an array of {result_type, data}
 * groups; only result_type "video" is kept in v0.1. Video items carry
 * bvid, title (with <em> highlight tags), author, pic (starting with
 * //), and duration as total-minutes text ("419:2"). Items without a
 * bvid are skipped, never fatal.
 */
fun parseSearchResults(data: JsonElement): List<Track> {
    val root = data as? JsonObject ?: throw parseError("Search data is not an object", data)
    val groups = root.arrayOrNull("result")
        ?: throw parseError("Search data has no result array", data)
    val tracks = mutableListOf<Track>()
    for (group in groups) {
        val groupObj = group as? JsonObject ?: continue
        if (groupObj.stringOrNull("result_type") != "video") continue
        val items = groupObj.arrayOrNull("data") ?: continue
        for (item in items) {
            val video = item as? JsonObject ?: continue
            val bvid = video.stringOrNull("bvid")?.takeIf { it.isNotEmpty() } ?: continue
            val title = cleanSearchTitle(video.stringOrNull("title") ?: "")
            val artist = video.stringOrNull("author")
                ?: video.stringOrNull("uname")
                ?: ""
            val cover = normalizeCover(video.stringOrNull("pic") ?: "")
            val duration = video.stringOrNull("duration")?.let(::parseDurationText)
                ?: Duration.ZERO
            tracks.add(
                Track(
                    id = bvid,
                    cid = "",
                    title = title,
                    artist = artist,
                    cover = cover,
                    duration = duration,
                ),
            )
        }
    }
    return tracks
}

/** One page of keyword search results: the page's video tracks plus whether more pages remain. */
data class SearchPage(val tracks: List<Track>, val hasMore: Boolean)

/**
 * Search page: the video tracks from [parseSearchResults] plus
 * hasMore from data.page < data.numPages (the server caps
 * numResults at 1000, 20 videos per page). A missing numPages
 * means no more pages; a missing page falls back to
 * [requestedPage].
 */
fun parseSearchPage(data: JsonElement, requestedPage: Int): SearchPage {
    val tracks = parseSearchResults(data)
    val root = data as JsonObject
    val numPages = root.intOrNull("numPages")
        ?: return SearchPage(tracks, hasMore = false)
    val page = root.intOrNull("page") ?: requestedPage
    return SearchPage(tracks, hasMore = page < numPages)
}

/**
 * Covers starting with // get an https: prefix, and plain http://
 * covers are upgraded to https://: the details response returns an
 * http:// pic (see the VIEW_DATA fixture), and Android blocks
 * cleartext HTTP by default, so an http cover never loads on
 * device. Anything else passes through.
 */
fun normalizeCover(pic: String): String = when {
    pic.startsWith("//") -> "https:$pic"
    pic.startsWith("http://") -> "https://" + pic.removePrefix("http://")
    else -> pic
}

/** One part of a multi-part video: its cid, part name, and duration. */
data class VideoPart(val cid: String, val title: String, val duration: Duration)

/**
 * Video details: the video-level [track] (title, artist, cover,
 * first part's cid and duration) plus every [part] in order. A
 * single-part video has exactly one part; a 100-part compilation
 * has 100, and they play in order as a queue.
 */
data class VideoDetail(val track: Track, val parts: List<VideoPart>)

/**
 * Video details from /x/web-interface/wbi/view: title, owner.name,
 * pic, and every page (cid, part name, duration in seconds). When
 * pages is empty, the top-level cid and duration fields are the
 * fallback, as a single unnamed part.
 */
fun parseViewDetail(data: JsonElement, bvid: String): VideoDetail {
    val root = data as? JsonObject ?: throw parseError("View data is not an object", data)
    val title = root.stringOrNull("title") ?: bvid
    val artist = root.objectOrNull("owner")?.stringOrNull("name") ?: ""
    val cover = normalizeCover(root.stringOrNull("pic") ?: "")
    val pages = root.arrayOrNull("pages")
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    val parts = if (pages.isNotEmpty()) {
        pages.map { page ->
            VideoPart(
                cid = page.contentOrNull("cid")
                    ?: throw parseError("View page has no cid", data),
                title = page.stringOrNull("part") ?: "",
                duration = (page.longOrNull("duration") ?: 0L).coerceAtLeast(0L).seconds,
            )
        }
    } else {
        val cid = root.contentOrNull("cid")
            ?: throw parseError("View data has no cid", data)
        val durationSeconds = root.longOrNull("duration") ?: 0L
        listOf(VideoPart(cid, "", durationSeconds.coerceAtLeast(0L).seconds))
    }
    val first = parts.first()
    return VideoDetail(
        track = Track(
            id = bvid,
            cid = first.cid,
            title = title,
            artist = artist,
            cover = cover,
            duration = first.duration,
        ),
        parts = parts,
    )
}

/** One pagelist entry: cid, part name, duration in seconds. */
data class PagelistEntry(val cid: String, val part: String, val duration: Duration)

/**
 * Pagelist fallback: data is an array of parts. Used only when
 * wbi/view fails; returns every part in order.
 */
fun parsePagelistParts(data: JsonElement): List<VideoPart> {
    val parts = data as? JsonArray ?: throw parseError("Pagelist data is not an array", data)
    if (parts.isEmpty()) throw parseError("Pagelist is empty", data)
    return parts.map { element ->
        val entry = element as? JsonObject
            ?: throw parseError("Pagelist entry is not an object", data)
        val cid = entry.contentOrNull("cid")
            ?: throw parseError("Pagelist entry has no cid", data)
        VideoPart(
            cid = cid,
            title = entry.stringOrNull("part") ?: "",
            duration = (entry.longOrNull("duration") ?: 0L).coerceAtLeast(0L).seconds,
        )
    }
}

/** The first pagelist entry only; kept for callers that need one part. */
fun parsePagelist(data: JsonElement): PagelistEntry {
    val first = parsePagelistParts(data).first()
    return PagelistEntry(cid = first.cid, part = first.title, duration = first.duration)
}

/**
 * WBI keys from /x/web-interface/nav: data.wbi_img holds img_url and
 * sub_url; the key is the filename without the extension. nav answers
 * code -101 anonymously, so callers must allow that code.
 */
fun parseNavKeys(data: JsonElement): WbiKeyStore.WbiKeys {
    val root = data as? JsonObject ?: throw parseError("Nav data is not an object", data)
    val wbiImg = root.objectOrNull("wbi_img")
        ?: throw parseError("Nav data has no wbi_img", data)
    val imgUrl = wbiImg.stringOrNull("img_url")
        ?: throw parseError("Nav wbi_img has no img_url", data)
    val subUrl = wbiImg.stringOrNull("sub_url")
        ?: throw parseError("Nav wbi_img has no sub_url", data)
    return WbiKeyStore.WbiKeys(
        imgKey = WbiSigner.keyFromImageUrl(imgUrl),
        subKey = WbiSigner.keyFromImageUrl(subUrl),
    )
}

/** finger/spi: data holds b_3 (buvid3) and b_4 (buvid4). */
fun parseFingerSpi(data: JsonElement): Pair<String, String> {
    val root = data as? JsonObject ?: throw parseError("finger/spi data is not an object", data)
    val b3 = root.stringOrNull("b_3")
        ?: throw parseError("finger/spi data has no b_3", data)
    val b4 = root.stringOrNull("b_4")
        ?: throw parseError("finger/spi data has no b_4", data)
    return b3 to b4
}

/** GenWebTicket result: the JWT ticket, its ttl, and the nav WBI image URLs. */
data class WebTicket(
    val ticket: String,
    val ttlSeconds: Long,
    val createdAtSeconds: Long,
    val navImgUrl: String?,
    val navSubUrl: String?,
)

/** One DASH audio stream from /x/player/playurl. [baseUrl] is the stream URL; it expires, so it is never stored long term and never logged in full. */
data class AudioStream(val qualityId: Int, val baseUrl: String, val bandwidth: Long)

/**
 * The first stream matching [preference]'s try order, or null when
 * none matches. Server order is not trusted: selection is by id,
 * never by array index. The default is [AudioQuality.BEST], which
 * tries Hi-Res and Dolby first (they are normally absent
 * anonymously, so they cost nothing) and falls all the way down to
 * 64K; explicit preferences fall downward only.
 */
fun pickAudioStream(
    streams: List<AudioStream>,
    preference: AudioQuality = AudioQuality.BEST,
): AudioStream? {
    for (qualityId in preference.tryOrder) {
        streams.firstOrNull { it.qualityId == qualityId }?.let { return it }
    }
    return null
}

/**
 * Play URL from /x/player/playurl (fnval 4048): data.dash.audio holds
 * the DASH audio streams; durl, dash.flac, and dash.dolby are ignored.
 * Streams without an id or an empty baseUrl are skipped. Throws
 * [BiliParseException] when dash/audio is missing or empty, or when
 * no stream matches [preference]'s try order.
 */
fun parsePlayUrl(data: JsonElement, preference: AudioQuality = AudioQuality.BEST): AudioStream {
    val root = data as? JsonObject ?: throw parseError("Playurl data is not an object", data)
    val dash = root.objectOrNull("dash")
        ?: throw parseError("Playurl data has no dash", data)
    val audio = dash.arrayOrNull("audio")
        ?: throw parseError("Playurl dash has no audio array", data)
    if (audio.isEmpty()) throw parseError("Playurl audio array is empty", data)
    val streams = audio.mapNotNull { element ->
        val stream = element as? JsonObject ?: return@mapNotNull null
        val id = (stream["id"] as? JsonPrimitive)?.intOrNull
            ?: (stream["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: return@mapNotNull null
        val baseUrl = stream.stringOrNull("baseUrl")?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        AudioStream(id, baseUrl, stream.longOrNull("bandwidth") ?: 0L)
    }
    return pickAudioStream(streams, preference)
        ?: throw parseError(
            "Playurl has no playable audio stream for quality ${preference.label} " +
                "(tried ${preference.tryOrder.joinToString()})",
            data,
        )
}

/** How many search suggestions the dropdown shows at most. */
const val SUGGESTION_LIMIT: Int = 10

/**
 * Search suggestions: the suggest endpoint answers under `result`
 * on the envelope object itself (no `data` field), so this parser
 * takes the whole envelope object, unlike the others. result.tag
 * is an array of {value, term, name, ...}; `value` is the plain
 * suggestion text (`name` carries <em> highlight markup, never
 * used). Lenient by design: suggestions are best-effort, so any
 * missing or misshaped node yields an empty list instead of
 * throwing, blank values are skipped, and `term` fills in when
 * `value` is missing. Field names verified live Nov 15, 2026.
 */
fun parseSuggest(envelope: JsonElement): List<String> {
    val root = envelope as? JsonObject ?: return emptyList()
    val tags = root.objectOrNull("result")?.arrayOrNull("tag") ?: return emptyList()
    val suggestions = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (tag in tags) {
        val item = tag as? JsonObject ?: continue
        val text = item.stringOrNull("value")?.ifBlank { null }
            ?: item.stringOrNull("term")?.ifBlank { null }
            ?: continue
        if (seen.add(text)) suggestions.add(text)
        if (suggestions.size >= SUGGESTION_LIMIT) break
    }
    return suggestions
}

/** GenWebTicket: data.ticket, data.ttl, data.created_at, data.nav {img, sub}. */
fun parseWebTicket(data: JsonElement): WebTicket {
    val root = data as? JsonObject ?: throw parseError("GenWebTicket data is not an object", data)
    val ticket = root.stringOrNull("ticket")
        ?: throw parseError("GenWebTicket data has no ticket", data)
    val nav = root.objectOrNull("nav")
    return WebTicket(
        ticket = ticket,
        ttlSeconds = root.longOrNull("ttl") ?: 259200L,
        createdAtSeconds = root.longOrNull("created_at") ?: 0L,
        navImgUrl = nav?.stringOrNull("img"),
        navSubUrl = nav?.stringOrNull("sub"),
    )
}
