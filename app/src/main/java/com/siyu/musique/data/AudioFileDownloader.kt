package com.siyu.musique.data

import com.siyu.musique.core.network.BiliClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The download seam: streams one audio URL to a file, reporting
 * progress as it goes. The ViewModel drives downloads through
 * this interface only, so unit tests fake it; production uses
 * [OkHttpAudioFileDownloader]. The URL is never logged anywhere
 * on this path: callers log keys, quality ids, and byte counts
 * only.
 */
interface AudioFileDownloader {
    /**
     * Streams [url] to [target], creating parent dirs, calling
     * [onProgress] with the bytes written so far and the total
     * size when the server sent one (null when unknown). Throws
     * on transport or HTTP failure; [target] may be partial
     * then, and the caller removes it.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    )
}

/**
 * Throttles download progress callbacks: at most one per
 * [intervalMs], so a fast download does not push hundreds of UI
 * updates per second (every 8KB chunk used to emit, and the
 * player screen recomposed for each one). The first report
 * always goes through, and the caller flushes a final report
 * when the stream ends. Pure apart from the injected [clock],
 * so unit tests drive it with a fake clock.
 */
internal class ProgressReportGate(
    private val intervalMs: Long,
    private val clock: () -> Long,
) {
    private var lastReportMs: Long? = null

    fun shouldReport(): Boolean {
        val now = clock()
        val last = lastReportMs
        if (last == null || now - last >= intervalMs) {
            lastReportMs = now
            return true
        }
        return false
    }
}

/**
 * The [AudioFileDownloader] backed by OkHttp, streaming the
 * response body to the file with byte counting. The request
 * carries the same Firefox User-Agent and bilibili.com Referer
 * as the player path: the CDN answers 403 without them. The
 * blocking IO runs on [Dispatchers.IO]; progress callbacks
 * arrive from that thread, and StateFlow writes from any
 * thread are safe. Progress callbacks are throttled by a
 * [ProgressReportGate], so a fast download reports about ten
 * times per second instead of once per buffer read.
 */
class OkHttpAudioFileDownloader(
    private val client: OkHttpClient = OkHttpGateway.defaultClient(),
) : AudioFileDownloader {

    override suspend fun download(
        url: String,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BiliClient.USER_AGENT)
                .header("Referer", REFERER)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed: HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw IOException("Download failed: empty body")
                val totalBytes = body.contentLength().takeIf { it >= 0 }
                target.parentFile?.mkdirs()
                var downloadedBytes = 0L
                val gate = ProgressReportGate(
                    PROGRESS_REPORT_INTERVAL_MS,
                    System::currentTimeMillis,
                )
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (gate.shouldReport()) {
                                onProgress(downloadedBytes, totalBytes)
                            }
                        }
                    }
                }
                onProgress(downloadedBytes, totalBytes)
            }
        }
    }

    companion object {
        /** The Referer the CDN requires, same as the player path. */
        const val REFERER = "https://www.bilibili.com/"

        /** Stream buffer: 8KB per read tunes IO only; the report gate throttles callbacks. */
        private const val BUFFER_SIZE = 8 * 1024

        /** Minimum gap between progress callbacks: about ten UI updates per second. */
        private const val PROGRESS_REPORT_INTERVAL_MS = 100L
    }
}

/**
 * A downloader that always fails: the ViewModel's default, so
 * tests and runs without a wired downloader fail loudly instead
 * of silently pretending a download happened. Production always
 * wires [OkHttpAudioFileDownloader].
 */
object NoOpAudioFileDownloader : AudioFileDownloader {
    override suspend fun download(
        url: String,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Unit = throw IllegalStateException("No audio file downloader wired")
}
