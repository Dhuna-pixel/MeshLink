package com.meshlink.app.data.transport

/** Bounded retry/discovery bookkeeping. Addresses are session endpoints, never node identities. */
class BleConnectionPolicy(val maxConnections: Int = 4, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val retries = linkedMapOf<String, Pair<Int, Long>>()
    private val sightings = linkedMapOf<String, Long>()
    @Synchronized fun discovered(address: String): Boolean {
        val first = address !in sightings
        if (sightings.size >= 128 && first) sightings.remove(sightings.keys.first())
        sightings[address] = now()
        return first
    }
    @Synchronized fun stale(): List<String> {
        val expired = sightings.filterValues { now() - it > 30_000 }.keys.toList()
        expired.forEach(sightings::remove)
        return expired
    }
    @Synchronized fun mayConnect(address: String, active: Int): Boolean {
        val previous = retries[address]
        return active < maxConnections && (previous == null || (previous.first < 5 && now() >= previous.second))
    }
    @Synchronized fun attempted(address: String) {
        val count = retries[address]?.first ?: 0
        if (retries.size >= 128 && address !in retries) retries.remove(retries.keys.first())
        retries[address] = count + 1 to (now() + (2_000L shl count.coerceAtMost(5)))
    }
    @Synchronized fun ready(address: String) { retries.remove(address) }
    @Synchronized fun reset() { retries.clear(); sightings.clear() }
}
