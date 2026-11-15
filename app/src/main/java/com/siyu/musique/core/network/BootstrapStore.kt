package com.siyu.musique.core.network

/**
 * Everything the bootstrap persists: the locally generated device
 * identity, the buvid pair, the web ticket, and the WBI keys with
 * their fetch time. The production implementation is DataStore
 * ([com.siyu.musique.data.DataStoreBootstrapStore]); tests use
 * [InMemoryBootstrapStore].
 */
data class BootstrapState(
    val uuid: String,
    val bLsid: String,
    val bNut: String,
    val buvid3: String = "",
    val buvid4: String = "",
    val biliTicket: String = "",
    val ticketExpiresAtSeconds: Long = 0L,
    val wbiImgKey: String = "",
    val wbiSubKey: String = "",
    val wbiFetchedAtMillis: Long = 0L,
)

interface BootstrapStore {
    suspend fun load(): BootstrapState?
    suspend fun save(state: BootstrapState)
}

/** In-memory store for tests and for runs without persistence. */
class InMemoryBootstrapStore(initial: BootstrapState? = null) : BootstrapStore {
    private var state: BootstrapState? = initial

    override suspend fun load(): BootstrapState? = state

    override suspend fun save(state: BootstrapState) {
        this.state = state
    }
}
