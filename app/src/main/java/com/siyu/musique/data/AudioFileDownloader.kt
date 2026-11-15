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
 * The [AudioFileDownloader] backed by OkHttp, streaming the
 * response body to the file with byte counting. The request
 * carries the same Firefox User-Agent and bilibili.com Referer
 * as the player path: the CDN answers 403 without them. The
 * blocking IO runs on [Dispatchers.IO]; progress callbacks
 * arrive from that thread, and StateFlow writes from any
 * thread are safe.
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
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            onProgress(downloadedBytes, totalBytes)
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

        /** Stream buffer: 8KB per read keeps progress callbacks frequent without chatter. */
        private const val BUFFER_SIZE = 8 * 1024
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
