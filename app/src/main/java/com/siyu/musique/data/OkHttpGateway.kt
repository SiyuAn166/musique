package com.siyu.musique.data

import com.siyu.musique.core.network.HttpGateway
import com.siyu.musique.core.network.HttpRequest
import com.siyu.musique.core.network.HttpResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The [HttpGateway] backed by OkHttp. This is the only file in the
 * app that imports OkHttp: everything above it talks to the gateway
 * interface, so the core stays testable with a fake. Timeouts are 15
 * seconds, matching the design. Transport failures surface as
 * [java.io.IOException] and are converted and retried by
 * [com.siyu.musique.core.network.BiliClient].
 */
class OkHttpGateway(
    private val client: OkHttpClient = defaultClient(),
) : HttpGateway {

    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(request.url)
            for ((name, value) in request.headers) {
                builder.addHeader(name, value)
            }
            when (request.method) {
                "GET" -> builder.get()
                "POST" -> builder.post(
                    request.body?.toRequestBody() ?: ByteArray(0).toRequestBody(),
                )
                else -> builder.method(request.method, request.body?.toRequestBody())
            }
            client.newCall(builder.build()).execute().use { response ->
                HttpResponse(
                    status = response.code,
                    headers = response.headers.toMultimap(),
                    body = response.body?.string() ?: "",
                )
            }
        }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        const val TIMEOUT_SECONDS = 15L
    }
}
