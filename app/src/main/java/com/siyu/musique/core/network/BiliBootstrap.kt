package com.siyu.musique.core.network

import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Anonymous device bootstrap, ported from NaivG/bilimusic's
 * BiliBootstrap and verified live (Nov 14, 2026 probe):
 *
 * 1. Locally generate the device identity: _uuid (uppercase hex UUID
 *    + 5 random digits + "infoc"), b_lsid, b_nut (unix seconds).
 * 2. GET /x/frontend/finger/spi for buvid3 (b_3) and buvid4 (b_4).
 * 3. POST GenWebTicket for the bili_ticket JWT and the nav WBI keys.
 * 4. GET https://www.bilibili.com once to activate the buvid; the
 *    body is discarded and a failure here is ignored.
 *
 * Bootstrap failure never blocks startup or search: [ensureBootstrap]
 * records the failure in [lastError], returns false, and never throws.
 * Unsigned search worked in the smoke tests without any bootstrap.
 * State persists through [BootstrapStore]; a later call retries the
 * steps that did not complete.
 */
class BiliBootstrap(
    private val client: BiliClient,
    private val cookieJar: CookieJar,
    private val store: BootstrapStore,
    private val wbiKeys: WbiKeyStore,
    private val generateIdentity: () -> DeviceIdentity = ::generateDeviceIdentity,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    data class DeviceIdentity(val uuid: String, val bLsid: String, val bNut: String)

    /** The last bootstrap failure, or null when the last run succeeded. */
    @Volatile
    var lastError: Throwable? = null
        private set

    private val mutex = Mutex()
    private var completed = false

    suspend fun ensureBootstrap(): Boolean = mutex.withLock {
        if (completed) return true
        try {
            runSteps()
            completed = true
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e
            false
        }
    }

    private suspend fun runSteps() {
        var state = store.load()
        if (state == null) {
            val identity = generateIdentity()
            state = BootstrapState(
                uuid = identity.uuid,
                bLsid = identity.bLsid,
                bNut = identity.bNut,
            )
            store.save(state)
        }
        seedCookies(state)
        if (state.wbiImgKey.isNotEmpty() && state.wbiSubKey.isNotEmpty() &&
            nowMillis() - state.wbiFetchedAtMillis < WbiKeyStore.TTL_MILLIS
        ) {
            wbiKeys.seed(state.wbiImgKey, state.wbiSubKey)
        }

        if (state.buvid3.isEmpty() || state.buvid4.isEmpty()) {
            val data = client.getData(FINGER_SPI_URL)
            val (buvid3, buvid4) = parseFingerSpi(data)
            state = state.copy(buvid3 = buvid3, buvid4 = buvid4)
            store.save(state)
            seedCookies(state)
        }

        val ticketExpired = state.biliTicket.isEmpty() ||
            state.ticketExpiresAtSeconds <= nowSeconds()
        if (ticketExpired) {
            val ts = nowSeconds()
            val hexsign = hmacSha256Hex(TICKET_KEY, "ts$ts")
            val csrf = cookieJar.valueOf("bili_jct") ?: ""
            val url = "$TICKET_URL?key_id=ec02" +
                "&hexsign=${WbiSigner.percentEncode(hexsign)}" +
                "&${WbiSigner.percentEncode("context[ts]")}=$ts" +
                "&csrf=${WbiSigner.percentEncode(csrf)}"
            val ticket = parseWebTicket(client.postData(url))
            val createdAt = if (ticket.createdAtSeconds > 0) {
                ticket.createdAtSeconds
            } else {
                ts
            }
            var updated = state.copy(
                biliTicket = ticket.ticket,
                ticketExpiresAtSeconds = createdAt + ticket.ttlSeconds,
            )
            val imgUrl = ticket.navImgUrl
            val subUrl = ticket.navSubUrl
            if (!imgUrl.isNullOrEmpty() && !subUrl.isNullOrEmpty()) {
                val imgKey = WbiSigner.keyFromImageUrl(imgUrl)
                val subKey = WbiSigner.keyFromImageUrl(subUrl)
                wbiKeys.seed(imgKey, subKey)
                updated = updated.copy(
                    wbiImgKey = imgKey,
                    wbiSubKey = subKey,
                    wbiFetchedAtMillis = nowMillis(),
                )
            }
            state = updated
            store.save(state)
            seedCookies(state)
        }

        // Activation hit: body discarded, failure ignored.
        runCatching { client.fetchRaw("https://www.bilibili.com/") }
    }

    private fun seedCookies(state: BootstrapState) {
        cookieJar.set("_uuid", state.uuid, COOKIE_DOMAIN)
        cookieJar.set("b_lsid", state.bLsid, COOKIE_DOMAIN)
        cookieJar.set("b_nut", state.bNut, COOKIE_DOMAIN)
        if (state.buvid3.isNotEmpty()) {
            cookieJar.set("buvid3", state.buvid3, COOKIE_DOMAIN)
        }
        if (state.buvid4.isNotEmpty()) {
            cookieJar.set("buvid4", state.buvid4, COOKIE_DOMAIN)
        }
        if (state.biliTicket.isNotEmpty() &&
            state.ticketExpiresAtSeconds > nowSeconds()
        ) {
            cookieJar.set(
                "bili_ticket",
                state.biliTicket,
                COOKIE_DOMAIN,
                expiresAtMillis = state.ticketExpiresAtSeconds * 1000L,
            )
        }
    }

    companion object {
        const val FINGER_SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi"
        const val TICKET_URL =
            "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket"

        /** Domain shared by api.bilibili.com and www.bilibili.com. */
        const val COOKIE_DOMAIN = "bilibili.com"

        /** Fixed HMAC key for the GenWebTicket hexsign (from NaivG). */
        const val TICKET_KEY = "XgwSnGZ1p"
    }
}

/** The locally generated device identity. No network involved. */
fun generateDeviceIdentity(): BiliBootstrap.DeviceIdentity {
    val uuid = UUID.randomUUID().toString().uppercase() +
        (1..5).joinToString("") { Random.nextInt(10).toString() } +
        "infoc"
    val nowMillis = System.currentTimeMillis()
    val head = "%08X".format(nowMillis and 0xFFFFFFFFL)
    val tail = (1..8).joinToString("") {
        "0123456789ABCDEF"[Random.nextInt(16)].toString()
    }
    return BiliBootstrap.DeviceIdentity(
        uuid = uuid,
        bLsid = head + tail,
        bNut = (nowMillis / 1000L).toString(),
    )
}
