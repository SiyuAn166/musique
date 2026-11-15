package com.siyu.musique.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The JSON codec for the stored download records: one JSON string
 * holding an array of records, each with the track (the same
 * shape as the playlist store, via [PlaylistJson]), the quality
 * id, the file name, the size in bytes, and the download time.
 * Play URLs are never written: the track codec does not write
 * them, and a downloaded file needs no URL to play.
 *
 * Written with the JsonElement API only (no serialization compiler
 * plugin in this project, same as the Bilibili parsers), and pure
 * Kotlin so it is unit tested directly. Decoding is lenient:
 * entries missing a track, a quality id, or a file name are
 * skipped, and missing sizes and times default to zero, so a
 * partially corrupt stored string still yields the records that
 * parse. A top-level value that is not a JSON array decodes to
 * null, and the caller starts with an empty list.
 */
object DownloadJson {

    /** Encodes [records] (download order) as one JSON string. */
    fun encode(records: List<DownloadRecord>): String {
        val array = buildJsonArray {
            for (record in records) {
                add(
                    buildJsonObject {
                        put(
                            "track",
                            PlaylistJson.tracksToJsonArray(listOf(record.track)).first(),
                        )
                        put("qualityId", record.qualityId)
                        put("fileName", record.fileName)
                        put("sizeBytes", record.sizeBytes)
                        put("downloadedAtMs", record.downloadedAtMs)
                    },
                )
            }
        }
        return array.toString()
    }

    /**
     * Decodes one stored JSON string back to the record list, or
     * null when the string is not a JSON array at all. See the
     * class doc for the lenient per-entry rules.
     */
    fun decode(raw: String): List<DownloadRecord>? {
        val array = try {
            Json.parseToJsonElement(raw).jsonArray
        } catch (e: Exception) {
            return null
        }
        val records = mutableListOf<DownloadRecord>()
        for (item in array) {
            val obj = try {
                item.jsonObject
            } catch (e: Exception) {
                continue
            }
            val trackElement = obj["track"] ?: continue
            val track = PlaylistJson.tracksFromJsonArray(listOf(trackElement))
                .firstOrNull() ?: continue
            val qualityId = obj["qualityId"]?.jsonPrimitive?.intOrNull ?: continue
            val fileName = obj["fileName"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() } ?: continue
            val sizeBytes =
                (obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0L)
            val downloadedAtMs =
                (obj["downloadedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0L)
            records.add(
                DownloadRecord(
                    track = track,
                    qualityId = qualityId,
                    fileName = fileName,
                    sizeBytes = sizeBytes,
                    downloadedAtMs = downloadedAtMs,
                ),
            )
        }
        return records
    }
}
