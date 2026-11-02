package com.meshlink.app.utils

import com.google.gson.Gson
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Utility for serializing and deserializing domain objects to/from JSON byte arrays and strings.
 * Uses Kotlinx Serialization with graceful Gson fallback.
 */
object JsonSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val gson = Gson()

    fun packetToJson(packet: MessagePacket): String {
        return try {
            json.encodeToString(packet)
        } catch (e: Exception) {
            gson.toJson(packet)
        }
    }

    fun jsonToPacket(jsonStr: String): MessagePacket? {
        return try {
            json.decodeFromString<MessagePacket>(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    fun packetToBytes(packet: MessagePacket): ByteArray {
        return packetToJson(packet).toByteArray(Charsets.UTF_8)
    }

    fun bytesToPacket(bytes: ByteArray): MessagePacket? {
        val jsonStr = String(bytes, Charsets.UTF_8)
        return jsonToPacket(jsonStr)
    }

    fun nodeToJson(node: Node): String {
        return try {
            json.encodeToString(node)
        } catch (e: Exception) {
            gson.toJson(node)
        }
    }

    fun jsonToNode(jsonStr: String): Node? {
        return try {
            json.decodeFromString<Node>(jsonStr)
        } catch (e: Exception) {
            try {
                gson.fromJson(jsonStr, Node::class.java)
            } catch (e2: Exception) {
                null
            }
        }
    }
}
