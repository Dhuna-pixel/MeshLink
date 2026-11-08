package com.meshlink.app.data.transport

/** Bounded retry/discovery bookkeeping. Addresses are session endpoints, never node identities. */
class BleConnectionPolicy(val maxConnections: Int = 4, private val now: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        fun sessionExpired(ageMillis: Long, authenticated: Boolean) =
            ageMillis >= if (authenticated) 3_600_000L else 60_000L
    }
    private val paused = mutableMapOf<String, String>()
    @Synchronized fun pause(address: String, token: String) { paused[token] = address }
    @Synchronized fun paused(address: String, token: String? = null) = address in paused.values || token in paused.keys
    @Synchronized fun requestConnection(address: String, token: String? = null) {
        paused.entries.removeAll { it.key == token || it.value == address }
        retries.remove(address)
    }
    private val retries = linkedMapOf<String, Pair<Int, Long>>()
    private data class Sighting(val time: Long, val token: String?)
    private val sightings = linkedMapOf<String, Sighting>()
    private val advertisements = linkedMapOf<String, Pair<String, Long>>()
    @Synchronized fun advertised(token: String, address: String) {
        if (advertisements.size >= 128 && token !in advertisements) advertisements.remove(advertisements.keys.first())
        advertisements[token] = address to now()
    }
    @Synchronized fun advertisedAddress(token: String): String? =
        advertisements[token]?.takeIf { now() - it.second <= 30_000 }?.first
    @Synchronized fun discovered(address: String, token: String? = null): Boolean {
        val first = address !in sightings
        if (sightings.size >= 128 && first) sightings.remove(sightings.keys.first())
        val previous = sightings[address]?.token
        sightings[address] = Sighting(now(), token ?: previous)
        return first || (token != null && token != previous)
    }
    @Synchronized fun stale(): List<String> {
        val expired = sightings.filterValues { now() - it.time > 30_000 }.keys.toList()
        expired.forEach(sightings::remove)
        return expired
    }
    @Synchronized fun mayConnect(address: String, active: Int): Boolean {
        val previous = retries[address]
        return !paused(address) && active < maxConnections && (previous == null || (previous.first < 5 && now() >= previous.second))
    }
    @Synchronized fun attempted(address: String) {
        val count = retries[address]?.first ?: 0
        if (retries.size >= 128 && address !in retries) retries.remove(retries.keys.first())
        retries[address] = count + 1 to (now() + (2_000L shl count.coerceAtMost(5)))
    }
    @Synchronized fun ready(address: String) { retries.remove(address); sightings.remove(address) }
    @Synchronized fun reset() { paused.clear(); advertisements.clear(); retries.clear(); sightings.clear() }
}
