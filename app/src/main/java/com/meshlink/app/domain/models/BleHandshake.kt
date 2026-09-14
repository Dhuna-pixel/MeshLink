package com.meshlink.app.domain.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Control message exchanged immediately after BLE GATT service discovery and CCCD subscription.
 *
 * Establishes mutual application-level identity (Node ID, Display Name, capabilities)
 * before marking a peer connection as READY.
 *
 * Does not enter normal message routing.
 */
@Serializable
data class BleHandshake(
    val type: String = "HANDSHAKE",
    val protocolVersion: Int = 1,
    val nodeId: String,
    val displayName: String,
    val isRelayCapable: Boolean = true
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun toJson(handshake: BleHandshake): String {
            return json.encodeToString(serializer(), handshake)
        }

        fun fromJson(jsonStr: String): BleHandshake? {
            return try {
                val parsed = json.decodeFromString(serializer(), jsonStr)
                if (parsed.type == "HANDSHAKE" && parsed.nodeId.isNotBlank()) parsed else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
