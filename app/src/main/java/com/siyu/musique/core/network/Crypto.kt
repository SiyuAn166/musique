package com.siyu.musique.core.network

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Lowercase hex MD5 of the UTF-8 bytes of [input]. Used for w_rid. */
fun md5Hex(input: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return digest.toHex()
}

/**
 * Lowercase hex HMAC-SHA256 of [message] under [key] (both UTF-8).
 * Used for the GenWebTicket hexsign with the fixed key "XgwSnGZ1p".
 */
fun hmacSha256Hex(key: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
}

private fun ByteArray.toHex(): String {
    val hex = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val b = this[i].toInt()
        hex[i * 2] = digits[(b shr 4) and 0x0F]
        hex[i * 2 + 1] = digits[b and 0x0F]
    }
    return hex.concatToString()
}
