package com.meshlink.app.security

import android.content.Context
import com.meshlink.app.domain.voice.VoiceManifest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Milestone 3 boundary. No networking or delivery state is created by this API.
 * Caller owns returned files and must delete them after use. Input drafts are retained for preview.
 */
class VoicePayloadService(context: Context, private val identity: Identity, private val journal: MessageJournal) {
    companion object { private val operations = Mutex() }
    private val localIdentity = context.getSharedPreferences("mesh_identity", Context.MODE_PRIVATE)
    private fun requireLocal(id: String) { require(id == localIdentity.getString("id", null)) { "Local identity mismatch" } }
    private fun capacity() {
        require(directory.listFiles().orEmpty().sumOf { it.length() } < 6_000_000L && directory.usableSpace >= 2_000_000L) { "Voice staging storage full" }
    }
    private val cache = context.cacheDir.canonicalFile
    private val directory = File(cache, "secure-voice").apply { mkdirs() }
    data class Payload(val metadata: VoicePayloadMetadata, val encryptedFile: File) {
        val encryptedBytes: Long get() = encryptedFile.length()
    }
    private fun privateFile(file: File) {
        require(file.canonicalFile.path.startsWith(cache.path + File.separator)) { "Voice files must be app-private cache files" }
    }
    private fun verified(id: String): PeerCard {
        val peer = requireNotNull(identity.peer(id)) { "Voice requires a verified peer" }
        require(identity.participant(id)?.sameKeys(peer) == true) { "Peer blocked or identity mismatch" }
        return peer
    }
    suspend fun encrypt(draft: File, localId: String, recipientId: String, conversationId: String,
        durationMs: Long, transferId: String = UUID.randomUUID().toString()): Payload = withContext(Dispatchers.IO) { operations.withLock {
        requireLocal(localId); capacity()
        privateFile(draft)
        val recipient = verified(recipientId)
        identity.own(localId, "") // Ensure existing Keystore keys exist.
        require(draft.length() in 1..(95 * 1024).toLong())
        val size = draft.length().toInt()
        val metadata = VoicePayloadMetadata(transferId = transferId, sourceId = localId,
            destinationId = recipientId, conversationId = conversationId, createdAt = System.currentTimeMillis(),
            durationMs = durationMs, audioBytes = size, chunkCount = (size + VoiceManifest.CHUNK_BYTES - 1) / VoiceManifest.CHUNK_BYTES)
        val result = SecureVoicePayload.encrypt(draft, directory, metadata, identity.signing(), recipient.wrappingKey())
        try { require(verified(recipientId).sameKeys(recipient)); Payload(metadata, result) }
        catch (e: Exception) { result.delete(); throw e }
    } }
    suspend fun decrypt(payload: Payload, localId: String): File = withContext(Dispatchers.IO) { operations.withLock {
        requireLocal(localId); capacity()
        privateFile(payload.encryptedFile)
        require(payload.metadata.destinationId == localId)
        val sender = verified(payload.metadata.sourceId)
        SecureVoicePayload.decrypt(payload.encryptedFile, directory, payload.metadata, identity.decrypting(),
            sender.cert().publicKey, System.currentTimeMillis()) { verifiedAudio ->
            require(verified(sender.id).sameKeys(sender))
            journal.storeVoice(localId, payload.metadata, verifiedAudio)
        }
    } }
}
