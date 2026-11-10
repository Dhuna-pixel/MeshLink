package com.meshlink.app.security

import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.voice.VoiceManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.*

@Serializable
data class VoicePayloadMetadata(val version: Int = 1, val transferId: String, val sourceId: String,
    val destinationId: String, val conversationId: String, val createdAt: Long,
    val durationMs: Long, val audioBytes: Int, val codec: String = "audio/mp4;codecs=mp4a.40.2",
    val chunkBytes: Int = VoiceManifest.CHUNK_BYTES, val chunkCount: Int) {
    fun validate() {
        VoiceManifest(version, transferId, transferId, sourceId, destinationId, conversationId,
            createdAt, durationMs, codec, audioBytes, "0".repeat(64), chunkBytes, chunkCount).validate()
        require(audioBytes <= 95 * 1024)
    }
}

/** Internal primitive. Call VoicePayloadService for identity policy and durable replay protection.
 * Container records are independently authenticated existing E2E envelopes, not BLE frames.
 */
internal object SecureVoicePayload {
    private const val MAX_RECORD = 24_000
    private const val MAX_CONTAINER = 2_000_000L
    private fun packet(m: VoicePayloadMetadata, index: Int, body: String) = MessagePacket(
        "voice/1/${m.transferId}/$index", m.sourceId, destinationId = m.destinationId,
        payload = body, timestamp = m.createdAt)
    private fun digest(m: VoicePayloadMetadata) = MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(m).toByteArray()).b64()
    private fun write(out: DataOutputStream, packet: MessagePacket) {
        val bytes = Json.encodeToString(packet).toByteArray()
        require(bytes.size <= MAX_RECORD)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun read(input: DataInputStream): MessagePacket {
        val size = input.readInt(); require(size in 1..MAX_RECORD)
        return Json.decodeFromString(ByteArray(size).also(input::readFully).toString(Charsets.UTF_8))
    }
    fun encrypt(input: File, directory: File, m: VoicePayloadMetadata, signing: PrivateKey, recipient: PublicKey): File {
        m.validate(); require(input.length() == m.audioBytes.toLong())
        val output = File.createTempFile("voice-encrypted-", ".bin", directory)
        try {
            DataOutputStream(output.outputStream().buffered()).use { out ->
                write(out, EndToEnd.seal(packet(m, -1, Json.encodeToString(m)), signing, recipient))
                input.inputStream().use { source ->
                    repeat(m.chunkCount) { index ->
                        val size = minOf(m.chunkBytes, m.audioBytes - index * m.chunkBytes)
                        val bytes = ByteArray(size)
                        DataInputStream(source).readFully(bytes)
                        // Digest binds every chunk to all immutable transfer metadata.
                        val body = Json.encodeToString(listOf("MeshLink/voice/1", digest(m), index.toString(), bytes.b64()))
                        write(out, EndToEnd.seal(packet(m, index, body), signing, recipient))
                    }
                    require(source.read() == -1) { "Draft changed during encryption" }
                }
            }
            require(output.length() <= MAX_CONTAINER)
            return output
        } catch (e: Exception) { output.delete(); throw e }
    }
    fun decrypt(input: File, directory: File, expected: VoicePayloadMetadata, recipient: PrivateKey,
        sender: PublicKey, now: Long, consume: (File) -> Boolean): File {
        expected.validate(); require(input.length() in 1..MAX_CONTAINER)
        var output: File? = null
        try {
            DataInputStream(input.inputStream().buffered()).use { source ->
                fun open(index: Int): String {
                    val wire = read(source)
                    val baseline = packet(expected, index, "")
                    require(wire.messageId == baseline.messageId && wire.sourceId == expected.sourceId &&
                        wire.destinationId == expected.destinationId && wire.timestamp == expected.createdAt &&
                        wire.group == null && wire.ackFor == null && wire.control == null)
                    return EndToEnd.open(wire, recipient, sender, now).also { require(it.group == null) }.payload
                }
                require(Json.decodeFromString<VoicePayloadMetadata>(open(-1)) == expected)
                output = File.createTempFile("voice-open-", ".m4a", directory)
                output!!.outputStream().buffered().use { target ->
                    repeat(expected.chunkCount) { index ->
                        val body = Json.decodeFromString<List<String>>(open(index))
                        require(body.size == 4 && body[0] == "MeshLink/voice/1" && body[1] == digest(expected) && body[2] == index.toString())
                        val bytes = body[3].bytes64()
                        require(bytes.size == minOf(expected.chunkBytes, expected.audioBytes - index * expected.chunkBytes))
                        target.write(bytes)
                    }
                }
                require(source.read() == -1) { "Trailing voice records" }
                check(consume(checkNotNull(output))) { "Replayed voice transfer" }
            }
            return checkNotNull(output)
        } catch (e: Exception) { output?.delete(); throw e }
    }
}
