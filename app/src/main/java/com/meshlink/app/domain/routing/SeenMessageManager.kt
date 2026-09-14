package com.meshlink.app.domain.routing

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Thread-safe LRU-backed cache of processed message IDs.
 *
 * Prevents message looping and duplicate delivery in ad-hoc multi-hop meshes.
 * Stores up to [maxCapacity] message IDs with automatic eviction of the oldest entries.
 */
class SeenMessageManager(private val maxCapacity: Int = 500) {

    // Thread-safe synchronized LRU set
    private val seenSet: MutableSet<String> = Collections.synchronizedSet(
        object : LinkedHashMap<String, Boolean>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > maxCapacity
            }
        }.let { Collections.newSetFromMap(it) }
    )

    /**
     * Checks whether the message ID has already been recorded in the cache.
     *
     * @return true if the message was already seen, false otherwise.
     */
    fun hasSeen(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        return seenSet.contains(messageId)
    }

    /**
     * Records a message ID into the seen cache.
     *
     * @return true if it was newly added, false if it was already present.
     */
    fun markSeen(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        return seenSet.add(messageId)
    }

    /**
     * Returns current count of tracked message IDs.
     */
    fun size(): Int = seenSet.size

    /**
     * Clears all cached message IDs (e.g. for testing or reset).
     */
    fun clear() {
        seenSet.clear()
    }
}
