package com.meshlink.app.utils

import java.util.UUID

/**
 * Utility to generate clean, readable identifiers for nodes and packets.
 */
object IdGenerator {

    /**
     * Generates a short, readable node ID, e.g. "NODE-A4F2"
     */
    fun generateNodeId(prefix: String = "NODE"): String {
        val randomHex = UUID.randomUUID().toString().substring(0, 4).uppercase()
        return "$prefix-$randomHex"
    }

    /**
     * Generates a unique message ID, e.g. "MSG-8B12"
     */
    fun generateMessageId(prefix: String = "MSG"): String {
        val randomHex = UUID.randomUUID().toString().substring(0, 6).uppercase()
        return "$prefix-$randomHex"
    }
}
