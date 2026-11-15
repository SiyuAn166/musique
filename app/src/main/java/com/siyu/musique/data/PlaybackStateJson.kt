package com.siyu.musique.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The JSON codec for the saved playback: one object holding the
 * queue (the same track shape as the playlist store), the
 * current track's key, and the position in milliseconds.
 * Decoding is strict about structure: anything that is not an
 * object with a track array and a current key that names a
 * queue member decodes to null, and the caller starts fresh.
 * Pure Kotlin so it is unit tested directly.
 */
object PlaybackStateJson {

    /** Encodes [state] as one JSON string. */
    fun encode(state: SavedPlayback): String = buildJsonObject {
        put("queue", PlaylistJson.tracksToJsonArray(state.queue))
        put("currentKey", state.currentKey)
        put("positionMs", state.positionMs)
    }.toString()

    /** Decodes one stored JSON string, or null when it is not a usable saved playback. */
    fun decode(raw: String): SavedPlayback? {
        val obj = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return null
        }
        val queueElement = obj["queue"] ?: return null
        val queue = try {
            PlaylistJson.tracksFromJsonArray(queueElement.jsonArray)
        } catch (e: Exception) {
            return null
        }
        val currentKey = obj["currentKey"]?.jsonPrimitive?.contentOrNull ?: return null
        if (queue.none { it.key == currentKey }) return null
        val positionMs = (obj["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0L)
        return SavedPlayback(queue, currentKey, positionMs)
    }
}
