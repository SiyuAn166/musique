package com.siyu.musique.data

import com.siyu.musique.model.Playlist
import com.siyu.musique.model.Track
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The JSON codec for the stored playlist list: one JSON string
 * holding an array of playlists, each with an id, a name, and its
 * tracks (bvid, cid, title, artist, cover, duration in
 * milliseconds). Play URLs are never written: they expire, so they
 * are resolved fresh on every play.
 *
 * Written with the JsonElement API only (no serialization compiler
 * plugin in this project, same as the Bilibili parsers), and pure
 * Kotlin so it is unit tested directly. Decoding is lenient: entries
 * missing an id, a name, or a track bvid are skipped, and missing
 * track fields default to empty/zero, so a partially corrupt stored
 * string still yields the playlists that parse. A top-level value
 * that is not a JSON array decodes to null, and the caller
 * bootstraps the default playlist.
 */
object PlaylistJson {

    /** Encodes [playlists] (creation order) as one JSON string. */
    fun encode(playlists: List<Playlist>): String {
        val array = buildJsonArray {
            for (playlist in playlists) {
                add(
                    buildJsonObject {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("tracks", buildJsonArray {
                            for (track in playlist.tracks) {
                                add(
                                    buildJsonObject {
                                        put("bvid", track.id)
                                        put("cid", track.cid)
                                        put("title", track.title)
                                        put("artist", track.artist)
                                        put("cover", track.cover)
                                        put(
                                            "durationMs",
                                            track.duration.inWholeMilliseconds,
                                        )
                                    },
                                )
                            }
                        })
                    },
                )
            }
        }
        return array.toString()
    }

    /**
     * Decodes one stored JSON string back to the playlist list, or
     * null when the string is not a JSON array at all. See the class
     * doc for the lenient per-entry rules.
     */
    fun decode(raw: String): List<Playlist>? {
        val element = try {
            Json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return null
        }
        val array = try {
            element.jsonArray
        } catch (e: Exception) {
            return null
        }
        val playlists = mutableListOf<Playlist>()
        for (item in array) {
            val obj = try {
                item.jsonObject
            } catch (e: Exception) {
                continue
            }
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val tracksElement = obj["tracks"]
            val tracksArray = if (tracksElement == null) {
                emptyList()
            } else {
                try {
                    tracksElement.jsonArray
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val tracks = mutableListOf<Track>()
            for (trackElement in tracksArray) {
                val trackObj = try {
                    trackElement.jsonObject
                } catch (e: Exception) {
                    continue
                }
                val bvid = trackObj["bvid"]?.jsonPrimitive?.contentOrNull ?: continue
                tracks.add(
                    Track(
                        id = bvid,
                        cid = trackObj["cid"]?.jsonPrimitive?.contentOrNull ?: "",
                        title = trackObj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        artist = trackObj["artist"]?.jsonPrimitive?.contentOrNull ?: "",
                        cover = trackObj["cover"]?.jsonPrimitive?.contentOrNull ?: "",
                        duration = (
                            trackObj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
                            ).coerceAtLeast(0L).milliseconds,
                    ),
                )
            }
            playlists.add(Playlist(id = id, name = name, tracks = tracks))
        }
        return playlists
    }
}
