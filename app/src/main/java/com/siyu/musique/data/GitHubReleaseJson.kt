package com.siyu.musique.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One asset attached to a GitHub release: its file name, its size
 * in bytes as the API reports it, and its browser download URL.
 */
data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
)

/**
 * The latest release as the GitHub API reports it: its tag plus
 * its attached files.
 */
data class LatestRelease(
    val tagName: String,
    val assets: List<ReleaseAsset>,
) {
    /** The asset with this file name, or null when the release does not carry it. */
    fun assetNamed(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

/**
 * The version.json asset CI attaches to every versioned release:
 * the build's versionCode and its tag. The update check compares
 * the versionCode against the installed build; the tag is what
 * the dialog shows.
 */
data class VersionInfo(
    val versionCode: Int,
    val tag: String,
)

/**
 * Parses the GitHub releases API payloads with the JsonElement API
 * only (no serialization compiler plugin, same as the playlist
 * and download stores). Malformed top-level payloads throw
 * [IllegalArgumentException]; malformed asset entries are skipped
 * the way lenient playlist entries are, so one bad entry never
 * hides the assets that parse.
 */
object GitHubReleaseJson {

    /** Parses a `releases/latest` response body. Throws when the tag is missing or the JSON is malformed. */
    fun parseLatestRelease(raw: String): LatestRelease {
        val root = Json.parseToJsonElement(raw).jsonObject
        val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("release JSON has no tag_name")
        val assets = root["assets"]?.jsonArray?.mapNotNull { parseAsset(it) } ?: emptyList()
        return LatestRelease(tagName, assets)
    }

    /** Parses a version.json asset body. Throws when the versionCode or the tag is missing or malformed. */
    fun parseVersionInfo(raw: String): VersionInfo {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val versionCode = obj["versionCode"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("version.json has no versionCode")
        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("version.json has no tag")
        return VersionInfo(versionCode, tag)
    }

    private fun parseAsset(element: JsonElement): ReleaseAsset? = runCatching {
        val obj = element.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val size = obj["size"]?.jsonPrimitive?.longOrNull ?: return@runCatching null
        val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?: return@runCatching null
        ReleaseAsset(name, size, url)
    }.getOrNull()
}

/** True when the release's versionCode is newer than the installed build. Equal or older means up to date. */
fun isUpdateAvailable(latestVersionCode: Int, installedVersionCode: Int): Boolean =
    latestVersionCode > installedVersionCode
