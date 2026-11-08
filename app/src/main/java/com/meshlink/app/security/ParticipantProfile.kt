package com.meshlink.app.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.PrivateKey
import java.security.Signature

@Serializable
data class ParticipantProfile(val card: PeerCard, val about: String, val updated: Long) {
    private fun bytes() = ("MeshLink/Profile/1\n" + Json.encodeToString(this)).toByteArray()

    fun sign(key: PrivateKey): SignedProfile {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(bytes())
        return SignedProfile(this, signature.sign().b64())
    }

    fun verify(signature: String) {
        require(about.length <= 160 && updated in 1..(System.currentTimeMillis() + 30_000))
        PeerCard.parse(card.export())
        val cert = card.cert()
        require(card.certificate == cert.encoded.b64() && card.encryption == card.wrappingKey().encoded.b64())
        cert.checkValidity()
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(cert.publicKey)
        verifier.update(bytes())
        require(verifier.verify(signature.bytes64())) { "Invalid profile signature" }
    }
}

@Serializable
data class SignedProfile(val profile: ParticipantProfile, val signature: String) {
    fun export() = Json.encodeToString(this)
    companion object {
        fun parse(text: String): SignedProfile {
            require(text.length in 1..8000)
            return Json.decodeFromString<SignedProfile>(text)
        }
    }
}

fun PeerCard.sameKeys(other: PeerCard) =
    id == other.id && certificate == other.certificate && encryption == other.encryption
