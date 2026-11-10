package com.meshlink.app.domain.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VoiceManifestTest {
    private fun manifest() = VoiceManifest(transferId = "transfer", messageId = "message",
        sourceId = "a", destinationId = "b", conversationId = "chat", createdAt = 1,
        durationMs = 1000, encryptedBytes = 513, sha256 = "a".repeat(64), chunkCount = 2)
    private fun rejects(value: VoiceManifest) {
        assertThrows(IllegalArgumentException::class.java) { value.validate() }
    }
    @Test fun roundTrip() {
        val value = manifest()
        assertEquals(value, Json.decodeFromString<VoiceManifest>(Json.encodeToString(value)))
        value.validate()
    }
    @Test fun exactChunkLengths() {
        assertEquals(512, manifest().expectedChunkBytes(0))
        assertEquals(1, manifest().expectedChunkBytes(1))
        assertThrows(IllegalArgumentException::class.java) { manifest().expectedChunkBytes(2) }
    }
    @Test fun rejectsUnsupportedAndMalformedMetadata() {
        rejects(manifest().copy(version = 2))
        rejects(manifest().copy(codec = "pcm"))
        rejects(manifest().copy(sha256 = "bad"))
        rejects(manifest().copy(chunkCount = Int.MAX_VALUE))
        rejects(manifest().copy(chunkBytes = 0))
        rejects(manifest().copy(sourceId = "b"))
        rejects(manifest().copy(transferId = ""))
    }
    @Test fun enforcesResourceLimits() {
        rejects(manifest().copy(durationMs = 30_001))
        rejects(manifest().copy(encryptedBytes = 98_305))
        rejects(manifest().copy(encryptedBytes = 0))
        rejects(manifest().copy(encryptedBytes = Int.MAX_VALUE))
    }
}
