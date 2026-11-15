package com.siyu.musique.data

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** One plain HTTP response for the update check: the status plus the body text. */
class UpdateHttpResponse(
    val status: Int,
    val body: String,
)

/**
 * The transport seam for the update check: plain GETs and a file
 * download, with no Bilibili headers, cookies, or signing. The
 * production implementation is OkHttp ([OkHttpUpdateGateway]);
 * tests use a fake. Transport failures surface as [IOException];
 * HTTP failures come back as the response status, so the checker
 * can tell a rate limit from a missing release.
 */
interface UpdateGateway {
    /** GETs [url] with [headers], returning the status and body text. Throws [IOException] on transport failure. */
    suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse

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
 * The [UpdateGateway] backed by its own plain OkHttpClient: no
 * Bilibili User-Agent, no Referer, no cookie jar, no auth of any
 * kind. The repo is public, so the anonymous GitHub API is all
 * the check needs. The blocking IO runs on [Dispatchers.IO];
 * progress callbacks are throttled by a [ProgressReportGate],
 * the same as the audio downloader, so a fast download reports
 * about ten times per second instead of once per buffer read.
 */
class OkHttpUpdateGateway(
    private val client: OkHttpClient = defaultClient(),
) : UpdateGateway {

    override suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            for ((name, value) in headers) {
                builder.addHeader(name, value)
            }
            client.newCall(builder.get().build()).execute().use { response ->
                UpdateHttpResponse(
                    status = response.code,
                    body = response.body?.string() ?: "",
                )
            }
        }

    override suspend fun download(
        url: String,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
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
        /** A plain User-Agent: GitHub requires one, and nothing here identifies the user. */
        const val USER_AGENT = "Musique-Android"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        const val TIMEOUT_SECONDS = 15L

        /** Stream buffer: 8KB per read tunes IO only; the report gate throttles callbacks. */
        private const val BUFFER_SIZE = 8 * 1024

        /** Minimum gap between progress callbacks: about ten UI updates per second. */
        private const val PROGRESS_REPORT_INTERVAL_MS = 100L
    }
}
