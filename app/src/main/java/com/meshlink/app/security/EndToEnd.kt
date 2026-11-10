package com.meshlink.app.security

import com.meshlink.app.domain.models.MessagePacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.*
import java.security.spec.MGF1ParameterSpec
import javax.crypto.*
import javax.crypto.spec.*
import kotlin.io.encoding.*

@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.b64() = Base64.encode(this)
@OptIn(ExperimentalEncodingApi::class)
internal fun String.bytes64() = Base64.decode(this)

@Serializable
private data class Envelope(val version: Int, val wrapped: String, val iv: String, val body: String, val signature: String)

object EndToEnd {
    private val random = SecureRandom()
    const val LIFETIME = 300_000L
    private fun metadata(p: MessagePacket) = Json.encodeToString(listOf("MeshLink/E2E/2", p.messageId,
        p.sourceId, p.sourceName, p.destinationId, p.destinationName, p.timestamp.toString(),
        (p.ttl + p.hopCount).toString(), p.ackFor.orEmpty(), Json.encodeToString(p.returnPath))).toByteArray()

    private fun rsa(mode: Int, key: Key) = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
        // Explicit MGF1 digest for Android Keystore interoperability.
        init(mode, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
    }
    private fun signatureInput(header: ByteArray, e: Envelope) =
        Json.encodeToString(listOf(header.b64(), e.wrapped, e.iv, e.body) + if (e.version == 3) listOf("group/3") else emptyList()).toByteArray()

    fun seal(p: MessagePacket, signing: PrivateKey, recipient: PublicKey): MessagePacket {
        require(p.control == null && p.payload.toByteArray().size <= 2000)
        val plain = p.group?.let {
            require(p.ackFor == null)
            it.verify(p.sourceId, p.destinationId)
            require(it.chat.member(p.destinationId)?.wrappingKey() == recipient)
            Json.encodeToString(GroupBody(it, p.payload)).toByteArray()
        } ?: p.payload.toByteArray()
        require(plain.size <= 16_000)
        val header = metadata(p)
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        val envelope = Envelope(if (p.group == null) 2 else 3, rsa(Cipher.ENCRYPT_MODE, recipient).doFinal(aes.encoded).b64(), iv.b64(),
            cipher.doFinal(plain).b64(), "")
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(signing); update(signatureInput(header, envelope)); sign()
        }
        return p.copy(payload = Json.encodeToString(envelope.copy(signature = sig.b64())), group = null)
    }

    fun open(p: MessagePacket, recipient: PrivateKey, sender: PublicKey, now: Long): MessagePacket {
        require(p.control == null && p.group == null && p.payload.length <= 24_000 && p.timestamp in (now - LIFETIME)..(now + 30_000))
        val e = Json.decodeFromString<Envelope>(p.payload)
        require(e.version in 2..3)
        val header = metadata(p)
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(sender); update(signatureInput(header, e)); verify(e.signature.bytes64())
        }) { "Invalid endpoint signature" }
        val key = rsa(Cipher.DECRYPT_MODE, recipient).doFinal(e.wrapped.bytes64())
        require(key.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, e.iv.bytes64()))
        cipher.updateAAD(header)
        val plain = cipher.doFinal(e.body.bytes64())
        if (e.version == 3) {
            require(p.ackFor == null && plain.size <= 16_000)
            val body = Json.decodeFromString<GroupBody>(plain.toString(Charsets.UTF_8))
            body.context.verify(p.sourceId, p.destinationId)
            require(body.context.chat.member(p.sourceId)?.cert()?.publicKey == sender)
            require(body.text.toByteArray().size <= 2000)
            return p.copy(payload = body.text, group = body.context, voice = null, voiceAudio = null)
        }
        require(plain.size <= 2000)
        return p.copy(payload = plain.toString(Charsets.UTF_8), voice = null, voiceAudio = null)
    }
}
