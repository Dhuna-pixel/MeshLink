package com.meshlink.app.domain.network

import java.security.MessageDigest

/** Local opt-in allowlist. Empty enabled configuration denies every peer (fail closed). */
data class DemoTopology(val enabled: Boolean = false, val permittedIds: Set<String> = emptySet()) {
    fun permits(nodeId: String) = !enabled || nodeId in permittedIds
    fun permitsAdvertisement(token: ByteArray) = !enabled || permittedIds.any { identityToken(it).contentEquals(token) }
    companion object {
        fun identityToken(id: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).copyOf(8)
        fun chain(localId: String, orderedIds: List<String>): DemoTopology {
            require(orderedIds.size in 2..32 && orderedIds.distinct().size == orderedIds.size) { "Use 2–32 distinct node IDs in the same order on every phone." }
            require(orderedIds.all { it.isNotBlank() && it.length <= 80 }) { "Each node ID must contain 1–80 characters." }
            val position = orderedIds.indexOf(localId)
            require(position >= 0) { "The chain must contain this phone's exact local node ID." }
            return DemoTopology(true, listOfNotNull(orderedIds.getOrNull(position - 1), orderedIds.getOrNull(position + 1)).toSet())
        }
    }
}
