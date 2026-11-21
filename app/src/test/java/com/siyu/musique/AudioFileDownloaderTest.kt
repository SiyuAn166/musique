package com.siyu.musique

import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.DownloadHttpException
import com.siyu.musique.data.OkHttpAudioFileDownloader
import com.siyu.musique.data.describeError
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The OkHttp downloader against a canned interceptor, so no
 * network is involved: the request it builds (Range, UA,
 * Referer), a 206 streaming to the file, and a 403 surfacing
 * as a typed HTTP failure whose status the log can print.
 */
class AudioFileDownloaderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("musique-downloader-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun clientReturning(
        code: Int,
        body: ByteArray,
        captured: MutableList<okhttp3.Request>,
    ) = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                captured += request
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 206) "Partial Content" else "Forbidden")
                    .body(body.toResponseBody("audio/mpeg".toMediaTypeOrNull()))
                    .build()
            },
        )
        .build()

    @Test
    fun `a range request streams a 206 body to the target file`() = runTest {
        val bytes = "audio-bytes".toByteArray()
        val captured = mutableListOf<okhttp3.Request>()
        val downloader = OkHttpAudioFileDownloader(clientReturning(206, bytes, captured))
        val target = File(dir, "song.m4a")
        val progress = mutableListOf<Pair<Long, Long?>>()

        downloader.download("https://cdn.example/song.m4s", target) { got, total ->
            progress += got to total
        }

        assertEquals(1, captured.size)
        val request = captured.single()
        assertEquals("bytes=0-", request.header("Range"))
        assertEquals(BiliClient.USER_AGENT, request.header("User-Agent"))
        assertEquals("https://www.bilibili.com/", request.header("Referer"))
        assertEquals("audio-bytes", target.readText())
        assertTrue(progress.isNotEmpty())
        assertEquals(bytes.size.toLong(), progress.last().first)
        assertEquals(bytes.size.toLong(), progress.last().second)
    }

    @Test
    fun `a 403 throws a typed http failure whose status the log can print`() = runTest {
        val captured = mutableListOf<okhttp3.Request>()
        val downloader = OkHttpAudioFileDownloader(
            clientReturning(403, ByteArray(0), captured),
        )

        var thrown: DownloadHttpException? = null
        try {
            downloader.download("https://cdn.example/song.m4s", File(dir, "song.m4a")) { _, _ -> }
        } catch (e: DownloadHttpException) {
            thrown = e
        }
        assertEquals(403, thrown?.status)
        assertEquals("http 403", describeError(thrown!!))
        // The request still carried Range: the CDN rejected the
        // mirror, not the request shape.
        assertEquals("bytes=0-", captured.single().header("Range"))
    }
}
