package com.meshlink.app.domain.voice

import kotlinx.serialization.Serializable

/** Application transfer metadata; must travel inside an authenticated endpoint envelope.
 * This is not an authorization decision and must never be accepted directly from a relay.
 */
@Serializable
data class VoiceManifest(
    val version: Int = 1,
    val transferId: String,
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val conversationId: String,
    val createdAt: Long,
    val durationMs: Long,
    val codec: String = "audio/mp4;codecs=mp4a.40.2",
    val encryptedBytes: Int,
    val sha256: String,
    val chunkBytes: Int = CHUNK_BYTES,
    val chunkCount: Int
) {
    fun validate() {
        require(version == 1) { "Unsupported voice protocol" }
        require(listOf(transferId, messageId, sourceId, destinationId, conversationId)
            .all { it.length in 1..80 && it.none(Char::isISOControl) })
        require(sourceId != destinationId)
        require(createdAt > 0)
        require(durationMs in 1..MAX_DURATION_MS) { "Audio too long" }
        require(codec == "audio/mp4;codecs=mp4a.40.2") { "Unsupported codec" }
        require(encryptedBytes in 1..MAX_ENCRYPTED_BYTES) { "File too large" }
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(chunkBytes == CHUNK_BYTES)
        require(chunkCount == (encryptedBytes + chunkBytes - 1) / chunkBytes)
    }

    fun expectedChunkBytes(index: Int): Int {
        validate()
        require(index in 0 until chunkCount)
        return minOf(chunkBytes, encryptedBytes - index * chunkBytes)
    }

    companion object {
        // Conservative payload budget: 512 binary bytes become 684 base64 bytes,
        // leaving room inside the existing 2,000-byte plaintext envelope limit.
        // The final signed packet still needs a 4,096-byte wire-size check.
        const val CHUNK_BYTES = 512
        const val MAX_ENCRYPTED_BYTES = 96 * 1024
        const val MAX_DURATION_MS = 30_000L
    }
}
