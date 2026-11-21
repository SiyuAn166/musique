package com.siyu.musique.core.network

import java.io.IOException

/**
 * Bilibili returned HTTP 200 but a non-zero business code, or a risk
 * control signal (HTTP 412, v_voucher pseudo-success) mapped onto a
 * business code. [code] is the Bilibili code: -101 not logged in,
 * -352 / -412 risk control, -799 too frequent.
 */
class BiliApiException(val code: Int, message: String) : Exception(message)

/**
 * A transport failure: timeout, connection failure, or a non-200 HTTP
 * status that is not a Bilibili business error. [retryable] is false
 * for statuses that retrying cannot fix (e.g. 404); transport retries
 * only happen for timeouts, connection failures, 408, 429, and 5xx.
 */
class BiliNetworkException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = true,
) : Exception(message, cause)

/**
 * The response was not the JSON shape we expected: not JSON at all, or
 * a field we need is missing or the wrong type. [preview] holds the
 * first 200 characters of the response (or of the offending JSON
 * fragment) so a format change is visible in logs and bug reports.
 */
class BiliParseException(message: String, val preview: String = "") : Exception(message)

/**
 * A file download got a non-2xx HTTP status. [status] is the code
 * alone, so it is safe to log: no URL, no body, nothing that can
 * leak a signed play URL. The downloader throws this instead of a
 * bare IOException so the failure log can say which status the
 * CDN answered with (403 and 567 both mean the CDN rejected the
 * request shape, not the song).
 */
class DownloadHttpException(val status: Int) : IOException("Download failed: HTTP $status")
