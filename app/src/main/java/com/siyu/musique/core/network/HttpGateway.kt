package com.siyu.musique.core.network

/**
 * One HTTP request. Kept deliberately small: [BiliClient] builds the
 * URL (including WBI signing) and the headers, the gateway only sends
 * bytes. [body] is null for GET and for the empty-body POSTs used by
 * GenWebTicket.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

/**
 * One HTTP response. [headers] maps header names to all their values;
 * use [headerValues] for a case-insensitive lookup (needed for the
 * possibly repeated Set-Cookie headers).
 */
class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun headerValues(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: emptyList()
}

/**
 * The only transport seam. The production implementation is OkHttp
 * ([com.siyu.musique.data.OkHttpGateway]); tests use a fake. A gateway
 * throws [java.io.IOException] (or a subclass) for transport failures;
 * [BiliClient] turns those into [BiliNetworkException] and retries
 * them. A gateway never interprets Bilibili business codes.
 */
fun interface HttpGateway {
    suspend fun execute(request: HttpRequest): HttpResponse
}
