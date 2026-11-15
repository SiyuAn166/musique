package com.siyu.musique

import com.siyu.musique.core.network.HttpGateway
import com.siyu.musique.core.network.HttpRequest
import com.siyu.musique.core.network.HttpResponse

/**
 * Test double for [HttpGateway]. Either script a queue of responses
 * / errors with [enqueue] / [enqueueError] (consumed in order), or
 * set [handler] to answer by request. Every request is recorded in
 * [requests] in order.
 */
class FakeGateway : HttpGateway {
    val requests = mutableListOf<HttpRequest>()

    var handler: (HttpRequest) -> HttpResponse = {
        error("FakeGateway has no scripted response and no handler for ${it.url}")
    }

    private val scripted = ArrayDeque<Any>()

    fun enqueue(response: HttpResponse) {
        scripted.addLast(response)
    }

    fun enqueueError(error: Throwable) {
        scripted.addLast(error)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        if (scripted.isNotEmpty()) {
            val next = scripted.removeFirst()
            if (next is Throwable) throw next
            return next as HttpResponse
        }
        return handler(request)
    }
}

/** A Bilibili envelope response: {"code":...,"message":...,"data":...}. */
fun envelopeResponse(
    code: Int,
    dataJson: String,
    status: Int = 200,
    message: String = "OK",
    headers: Map<String, List<String>> = emptyMap(),
): HttpResponse = HttpResponse(
    status = status,
    headers = headers,
    body = """{"code":$code,"message":"$message","ttl":1,"data":$dataJson}""",
)

fun plainResponse(
    status: Int,
    body: String,
    headers: Map<String, List<String>> = emptyMap(),
): HttpResponse = HttpResponse(status, headers, body)
