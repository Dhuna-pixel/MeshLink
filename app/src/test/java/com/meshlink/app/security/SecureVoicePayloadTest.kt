package com.meshlink.app.security

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.io.File

class SecureVoicePayloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val signing = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val recipient = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun metadata() = VoicePayloadMetadata(transferId = "transfer", sourceId = "A", destinationId = "B",
        conversationId = "chat", createdAt = System.currentTimeMillis(), durationMs = 1200, audioBytes = 1025, chunkCount = 3)
    private fun source() = folder.newFile().apply { writeBytes(ByteArray(1025) { it.toByte() }) }
    private fun seal(m: VoicePayloadMetadata, input: File = source()) = SecureVoicePayload.encrypt(input, folder.root, m, signing.private, recipient.public)
    private fun open(f: File, m: VoicePayloadMetadata, consume: () -> Boolean = { true }) = SecureVoicePayload.decrypt(f, folder.root, m, recipient.private, signing.public, m.createdAt) { consume() }
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun roundTripAndReplay() {
        val m = metadata(); val input = source(); val encrypted = seal(m, input)
        var seen = false
        val consume = { if (seen) false else { seen = true; true } }
        assertArrayEquals(input.readBytes(), open(encrypted, m, consume).readBytes())
        fails { open(encrypted, m, consume) }
    }
    @Test fun metadataBinding() {
        val m = metadata(); val f = seal(m)
        listOf(m.copy(transferId = "other"), m.copy(sourceId = "C"), m.copy(destinationId = "C"),
            m.copy(conversationId = "other"), m.copy(durationMs = 1300), m.copy(chunkCount = 2),
            m.copy(chunkBytes = 256), m.copy(codec = "pcm"), m.copy(version = 2),
            m.copy(durationMs = 30001)).forEach { changed -> fails { open(f, changed) } }
    }
    @Test fun ciphertextTamperingTruncationAndCleanup() {
        val m = metadata(); val f = seal(m)
        val before = folder.root.listFiles()!!.size
        val bytes = f.readBytes(); bytes[bytes.size - 100] = (bytes[bytes.size - 100].toInt() xor 1).toByte(); f.writeBytes(bytes)
        fails { open(f, m) }; assertEquals(before, folder.root.listFiles()!!.size)
        f.writeBytes(bytes.copyOf(bytes.size - 500))
        fails { open(f, m) }; assertEquals(before, folder.root.listFiles()!!.size)
    }
    @Test fun wrongKeysAndExpiration() {
        val m = metadata(); val f = seal(m)
        val wrong = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fails { SecureVoicePayload.decrypt(f, folder.root, m, wrong.private, signing.public, m.createdAt) { true } }
        val stranger = KeyPairGenerator.getInstance("EC").generateKeyPair()
        fails { SecureVoicePayload.decrypt(f, folder.root, m, recipient.private, stranger.public, m.createdAt) { true } }
        fails { SecureVoicePayload.decrypt(f, folder.root, m, recipient.private, signing.public, m.createdAt + EndToEnd.LIFETIME + 1) { true } }
    }
    @Test fun invalidInputAndEncryptionFailureCleanup() {
        val m = metadata(); val empty = folder.newFile()
        fails { seal(m, empty) }
        fails { seal(m.copy(audioBytes = 100000, chunkCount = 196)) }
        val input = source(); val before = folder.root.listFiles()!!.size
        fails { SecureVoicePayload.encrypt(input, folder.root, m, recipient.private, recipient.public) }
        assertEquals(before, folder.root.listFiles()!!.size)
    }
    @Test fun freshEncryptionAndReplayStoreFailureCleanup() {
        val m = metadata(); val input = source()
        val a = seal(m, input); val b = seal(m, input)
        assertFalse(a.readBytes().contentEquals(b.readBytes()))
        val before = folder.root.listFiles()!!.size
        fails { open(a, m) { error("Replay database unavailable") } }
        assertEquals(before, folder.root.listFiles()!!.size)
    }
}
