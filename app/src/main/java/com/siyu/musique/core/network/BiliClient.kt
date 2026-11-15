package com.siyu.musique.core.network

import java.io.IOException
import java.net.URLDecoder
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Transport for every Bilibili call: headers, cookie ingest, timeout
 * retries, business-code checks, and risk-control re-signing.
 *
 * All requests go through one mutex, so calls are serial: the smoke
 * tests showed parallel bursts get throttled into timeouts.
 *
 * Signing: when [signed] is set, the URL query is WBI-signed with the
 * mixin key from [wbiKeys]. On a risk signal (-352 / -412 code, HTTP
 * 412, or the v_voucher pseudo-success) the keys are invalidated and
 * the request is re-signed and retried exactly once.
 */
class BiliClient(
    private val gateway: HttpGateway,
    private val cookieJar: CookieJar,
    private val wbiKeys: WbiKeyStore? = null,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = { Random.nextDouble() },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val maxAttempts: Int = 2,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** A Bilibili response envelope: business code, message, and data. */
    data class Envelope(val code: Int, val message: String, val data: JsonElement)

    /** GET [url] and return the envelope's data, enforcing code == 0. */
    suspend fun getData(
        url: String,
        signed: Boolean = false,
        allowCodes: Set<Int> = emptySet(),
    ): JsonElement = getEnvelope(url, signed, allowCodes).data

    /** POST [url] with an empty body and return the envelope's data. */
    suspend fun postData(
        url: String,
        allowCodes: Set<Int> = emptySet(),
    ): JsonElement = request("POST", url, signed = false, allowCodes).data

    /**
     * GET [url] and return the full envelope. Codes in [allowCodes]
     * are returned instead of throwing: /x/web-interface/nav answers
     * -101 anonymously while still carrying the WBI keys.
     */
    suspend fun getEnvelope(
        url: String,
        signed: Boolean = false,
        allowCodes: Set<Int> = emptySet(),
    ): Envelope = request("GET", url, signed, allowCodes)

    /**
     * GET [url] and return the raw response, discarding the body
     * without parsing it. Used for the www.bilibili.com activation
     * hit, which returns HTML. Cookies are still ingested.
     */
    suspend fun fetchRaw(url: String): HttpResponse = mutex.withLock {
        withTransportRetries { executeOnce("GET", url, signed = false) }
    }

    private suspend fun request(
        method: String,
        url: String,
        signed: Boolean,
        allowCodes: Set<Int>,
    ): Envelope {
        // Resolve the mixin key OUTSIDE the request mutex: the key
        // fetcher itself calls back into this client (unsigned nav
        // request), so resolving it while holding the mutex would
        // deadlock. The same applies to the fresh key after a risk
        // retry: invalidate, release the mutex, then re-resolve.
        var mixinKey: String? = null
        if (signed) {
            mixinKey = wbiKeys?.getMixinKey()
                ?: throw IllegalStateException("Signed request needs a WBI key store")
        }
        var riskRetried = false
        while (true) {
            try {
                return mutex.withLock {
                    withTransportRetries {
                        executeEnvelopeOnce(method, url, signed, allowCodes, mixinKey)
                    }
                }
            } catch (e: RiskRetry) {
                if (riskRetried) {
                    throw BiliApiException(-352, "Request blocked, try again shortly.")
                }
                riskRetried = true
                wbiKeys?.invalidate()
                mixinKey = wbiKeys?.getMixinKey()
            }
        }
    }

    private suspend fun <T> withTransportRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: BiliNetworkException) {
                if (!e.retryable || attempt >= maxAttempts) throw e
                sleeper(backoffMillis(attempt))
            }
        }
    }

    private fun backoffMillis(failedAttempt: Int): Long {
        val base = 300.0 * 2.0.pow(failedAttempt - 1)
        val jittered = base * (0.7 + 0.6 * jitter())
        return jittered.toLong().coerceAtMost(30_000L)
    }

    private suspend fun executeEnvelopeOnce(
        method: String,
        url: String,
        signed: Boolean,
        allowCodes: Set<Int>,
        mixinKey: String?,
    ): Envelope {
        val response = executeOnce(method, url, signed, mixinKey)
        val status = response.status
        if (status == 412) {
            if (signed) throw RiskRetry()
            throw BiliApiException(-412, "Request blocked, try again shortly.")
        }
        if (status == 408 || status == 429 || status in 500..599) {
            throw BiliNetworkException("HTTP $status from Bilibili", retryable = true)
        }
        if (status != 200) {
            throw BiliNetworkException(
                "HTTP $status from Bilibili",
                retryable = false,
            )
        }
        val element = try {
            json.parseToJsonElement(response.body)
        } catch (e: Exception) {
            throw BiliParseException(
                "Response is not JSON (HTTP 200)",
                preview = response.body.take(PREVIEW_LENGTH),
            )
        }
        val obj = element as? JsonObject
            ?: throw BiliParseException(
                "Response envelope is not a JSON object",
                preview = response.body.take(PREVIEW_LENGTH),
            )
        val code = obj["code"]?.jsonPrimitive?.intOrNull
            ?: throw BiliParseException(
                "Response envelope has no numeric code",
                preview = response.body.take(PREVIEW_LENGTH),
            )
        val message = obj["message"]?.jsonPrimitive?.content ?: ""
        val data = obj["data"] ?: JsonNull
        if (code == 0 && isVoucherOnly(data)) {
            // Fake success: code 0 but data holds only v_voucher.
            // NaivG treats this as -352 risk control, never as empty results.
            if (signed) throw RiskRetry()
            throw BiliApiException(-352, "Request blocked, try again shortly.")
        }
        if (code != 0 && code !in allowCodes) {
            if (signed && (code == -352 || code == -412)) throw RiskRetry()
            throw BiliApiException(code, message.ifEmpty { "Bilibili error $code" })
        }
        return Envelope(code, message, data)
    }

    private suspend fun executeOnce(
        method: String,
        url: String,
        signed: Boolean,
        mixinKey: String? = null,
    ): HttpResponse {
        val finalUrl = if (signed) {
            signUrl(url, mixinKey ?: throw IllegalStateException("Signed request needs a mixin key"))
        } else {
            url
        }
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = USER_AGENT
        headers["Referer"] = refererFor(finalUrl)
        if (method == "POST") {
            headers["Origin"] = "https://www.bilibili.com"
        }
        cookieJar.headerFor(finalUrl)?.let { headers["Cookie"] = it }
        val body = if (method == "POST") ByteArray(0) else null
        val response = try {
            gateway.execute(HttpRequest(method, finalUrl, headers, body))
        } catch (e: IOException) {
            throw BiliNetworkException("Network failure: ${e.message}", cause = e)
        }
        for (setCookie in response.headerValues("Set-Cookie")) {
            cookieJar.storeFromSetCookie(setCookie, finalUrl)
        }
        return response
    }

    private fun signUrl(url: String, mixinKey: String): String {
        val question = url.indexOf('?')
        val base = if (question >= 0) url.substring(0, question) else url
        val params = linkedMapOf<String, String>()
        if (question >= 0) {
            for (pair in url.substring(question + 1).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq >= 0) {
                    val key = URLDecoder.decode(pair.substring(0, eq), Charsets.UTF_8.name())
                    val value = URLDecoder.decode(pair.substring(eq + 1), Charsets.UTF_8.name())
                    params[key] = value
                } else {
                    params[URLDecoder.decode(pair, Charsets.UTF_8.name())] = ""
                }
            }
        }
        return "$base?${WbiSigner.sign(params, mixinKey, nowSeconds())}"
    }

    private fun refererFor(url: String): String =
        if (url.contains("search.bilibili.com")) {
            "https://search.bilibili.com/"
        } else {
            "https://www.bilibili.com/"
        }

    private fun isVoucherOnly(data: JsonElement): Boolean {
        val obj = data as? JsonObject ?: return false
        return obj.size == 1 && obj.containsKey("v_voucher")
    }

    /** Internal signal: re-sign with fresh keys and retry once. */
    private class RiskRetry : Exception()

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
        const val PREVIEW_LENGTH = 200
    }
}
