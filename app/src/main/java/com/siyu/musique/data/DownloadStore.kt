package com.siyu.musique.data

import com.siyu.musique.model.Track
import java.io.File

/** Directory under filesDir holding downloaded audio files. App-private: uninstall deletes it with everything else. */
const val DOWNLOADS_DIR_NAME = "downloads"

/**
 * The downloads dir when no explicit one is wired (tests and
 * previews that never download): a temp dir, never the app's
 * real files. Production always passes the filesDir dir through
 * [BiliDependencies.downloadsDir].
 */
internal fun defaultDownloadsDir(): File = File(
    System.getProperty("java.io.tmpdir") ?: ".",
    "musique-$DOWNLOADS_DIR_NAME",
)

/**
 * One downloaded audio file: the track's metadata, the resolved
 * DASH stream id it was downloaded at, the file name under
 * [DOWNLOADS_DIR_NAME], the file size in bytes, and when the
 * download finished. The stored track never carries a play URL:
 * URLs expire, and playing a downloaded file needs no URL at
 * all. Records are keyed by (track key, quality id): different
 * qualities of the same track are separate files.
 */
data class DownloadRecord(
    val track: Track,
    val qualityId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val downloadedAtMs: Long,
)

/** File name for [track] downloaded at [qualityId]: bvid, cid, and stream id, so names never collide. */
fun downloadFileName(track: Track, qualityId: Int): String =
    "${track.id}_${track.cid}_$qualityId.m4a"

/**
 * Persistence for the download records: one stored value, the
 * whole list in download order. The production implementation is
 * DataStore ([DataStoreDownloadStore], one JSON string via
 * [DownloadJson]); tests and runs without persistence use
 * [InMemoryDownloadStore]. A missing value means "never saved",
 * and callers start with an empty list: unlike playlists there
 * is nothing to bootstrap.
 */
interface DownloadStore {
    /** The stored records in download order, or null when nothing was ever saved. */
    suspend fun load(): List<DownloadRecord>?

    /** Persists [records], replacing any previous value. */
    suspend fun save(records: List<DownloadRecord>)
}

/** In-memory store for tests and for runs without persistence. */
class InMemoryDownloadStore(
    initial: List<DownloadRecord>? = null,
) : DownloadStore {
    private var stored: List<DownloadRecord>? = initial

    override suspend fun load(): List<DownloadRecord>? = stored

    override suspend fun save(records: List<DownloadRecord>) {
        stored = records
    }
}
