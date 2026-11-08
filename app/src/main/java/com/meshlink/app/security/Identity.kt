package com.meshlink.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.net.Socket
import javax.net.ssl.*

@Serializable
data class PeerCard(val version: Int, val id: String, val name: String, val certificate: String, val encryption: String) {
    fun cert() = CertificateFactory.getInstance("X.509").generateCertificate(certificate.bytes64().inputStream()) as X509Certificate
    fun wrappingKey() = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encryption.bytes64()))
    fun fingerprint() = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(this).toByteArray())
        .joinToString("") { "%02X".format(it) }.chunked(8).joinToString(" ")
    fun export() = Json.encodeToString(this)
    companion object {
        fun parse(text: String): PeerCard {
            require(text.length in 1..6000)
            return Json.decodeFromString<PeerCard>(text.trim()).also {
                require(it.version == 2 && it.id.length in 1..80 && it.name.length <= 64)
                require(it.cert().publicKey.algorithm == "EC" && (it.wrappingKey() as RSAPublicKey).modulus.bitLength() >= 2048)
            }
        }
    }
}

class Identity(context: Context, namespace: String = "MeshLink.v2") {
    private val SIGN = "$namespace.sign"
    private val WRAP = "$namespace.wrap"
    private val storage = Vault(context, "$namespace.pins")
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized private fun ensure(alias: String) {
        if (store.containsAlias(alias)) return
        val sign = alias == SIGN
        val builder = KeyGenParameterSpec.Builder(alias, if (sign) PURPOSE_SIGN or PURPOSE_VERIFY else PURPOSE_DECRYPT)
            .setDigests(DIGEST_SHA256, DIGEST_SHA384, DIGEST_SHA512)
        // Conscrypt signs its already-hashed TLS transcript through NONEwithECDSA.
        if (sign) builder.setDigests(DIGEST_NONE, DIGEST_SHA256, DIGEST_SHA384, DIGEST_SHA512).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        else builder.setKeySize(2048).setEncryptionPaddings(ENCRYPTION_PADDING_RSA_OAEP)
        KeyPairGenerator.getInstance(if (sign) "EC" else "RSA", "AndroidKeyStore").apply {
            initialize(builder.build()); generateKeyPair()
        }
    }
    fun own(id: String, name: String): PeerCard {
        ensure(SIGN); ensure(WRAP)
        return PeerCard(2, id, name, store.getCertificate(SIGN).encoded.b64(), store.getCertificate(WRAP).publicKey.encoded.b64())
    }
    private var localProfile: SignedProfile? = null
    @Synchronized fun profile(node: com.meshlink.app.domain.models.Node): SignedProfile {
        localProfile?.let { if (it.profile.card.id == node.nodeId && it.profile.card.name == node.nodeName && it.profile.about == node.about) return it }
        return ParticipantProfile(own(node.nodeId,node.nodeName),node.about,System.currentTimeMillis()).sign(signing()).also { localProfile = it }
    }
    fun signing() = store.getKey(SIGN, null) as PrivateKey
    fun decrypting() = store.getKey(WRAP, null) as PrivateKey
    @Volatile private var cached: List<PeerCard>? = null
    @Synchronized fun peers(): List<PeerCard> = cached ?: Json.decodeFromString<List<PeerCard>>(storage.get("pins") ?: "[]").also { cached = it }
    @Volatile private var cachedRejected: List<PeerCard>? = null
    private var directoryCache: Map<String,SignedProfile>? = null
    @Synchronized fun profiles(): Map<String,SignedProfile> = directoryCache
        ?: Json.decodeFromString<Map<String,SignedProfile>>(storage.get("profiles") ?: "{}").also { directoryCache = it }

    private var blockedCache: Set<String>? = null
    @Synchronized fun blocked(): Set<String> = blockedCache
        ?: Json.decodeFromString<Set<String>>(storage.get("blocked") ?: "[]").also { blockedCache = it }

    @Synchronized fun setBlocked(id: String, block: Boolean) {
        val next = if (block) blocked() + id else blocked() - id
        storage.put("blocked", Json.encodeToString(next))
        blockedCache = next
    }

    fun participant(id: String): PeerCard? = if (id in blocked()) null else linkCard(id)
    @Synchronized fun linkCard(id: String): PeerCard? {
        val card = profiles()[id]?.profile?.card ?: peer(id) ?: return null
        if (rejectedCards().any { it.sameKeys(card) } && peer(id)?.sameKeys(card) != true) return null
        return card
    }

    fun authenticateHello(hello: com.meshlink.app.domain.models.BleHandshake, cert: X509Certificate,
        local: PeerCard, expectedId: String?): PeerCard {
        require(hello.protocolVersion == 2 && hello.nodeId != local.id && (expectedId == null || hello.nodeId == expectedId))
        val pin = hello.profile?.let {
            val signed = SignedProfile.parse(it)
            require(signed.profile.card.id == hello.nodeId && signed.profile.card.cert() == cert)
            learn(signed, local)
        } ?: requireNotNull(pinned(cert))
        require(pin.id == hello.nodeId && linkCard(pin.id)?.sameKeys(pin) == true)
        return pin
    }

    @Synchronized fun learn(signed: SignedProfile, local: PeerCard): PeerCard {
        val card = signed.profile.card
        require(card.id != local.id && card.certificate != local.certificate && card.encryption != local.encryption)
        val existing = profiles()[card.id]
        if (signed == existing) return requireNotNull(linkCard(card.id))
        signed.profile.verify(signed.signature)
        val verified = peer(card.id)
        require(rejectedCards().none { it.sameKeys(card) } || verified?.sameKeys(card) == true)
        val known = peers() + profiles().values.map { it.profile.card }
        require(known.all {
            if (it.id == card.id) it.sameKeys(card)
            else it.certificate != card.certificate && it.encryption != card.encryption
        }) { "Conflicting participant identity" }
        if (existing != null && signed.profile.updated <= existing.profile.updated) return existing.profile.card
        require(existing != null || profiles().size < 512)
        val next = profiles() + (card.id to signed)
        storage.put("profiles", Json.encodeToString(next))
        directoryCache = next
        return card
    }

    @Synchronized fun rejectedCards(): List<PeerCard> = cachedRejected
        ?: Json.decodeFromString<List<PeerCard>>(storage.get("rejected") ?: "[]").also { cachedRejected = it }

    @Synchronized fun reject(card: PeerCard) {
        require(card != peer(card.id)) { "Remove trust before rejecting a verified card" }
        val cards = (rejectedCards().filterNot { it.id == card.id } + card).takeLast(128)
        storage.put("rejected", Json.encodeToString(cards))
        cachedRejected = cards
    }

    @Synchronized fun trust(card: PeerCard, local: PeerCard) {
        require(card.id != local.id && card.certificate != local.certificate && card.encryption != local.encryption)
        require(profiles().values.none { val known = it.profile.card; !known.sameKeys(card) && (known.id == card.id || known.certificate == card.certificate || known.encryption == card.encryption) })
        require(peers().none { it.id != card.id && (it.certificate == card.certificate || it.encryption == card.encryption) })
        require(peers().none { it.id == card.id && !it.sameKeys(card) }) { "Remove the previous pin before replacing it" }
        val next = peers().filterNot { it.id == card.id } + card
        require(next.size <= 128)
        storage.put("pins", Json.encodeToString(next)); cached = next
    }
    @Synchronized fun forget(id: String) {
        val next = peers().filterNot { it.id == id }
        storage.put("pins", Json.encodeToString(next)); cached = next
    }
    fun peer(id: String) = peers().firstOrNull { it.id == id }
    fun pinned(cert: X509Certificate) = peers().firstOrNull { it.certificate == cert.encoded.b64() }
    fun engine(client: Boolean, discoverParticipants: Boolean = false): SSLEngine {
        ensure(SIGN)
        val cert = store.getCertificate(SIGN) as X509Certificate
        val manager = object : X509ExtendedKeyManager() {
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = signing()
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(SIGN)
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(SIGN)
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = SIGN
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = SIGN
            override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = SIGN
            override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = SIGN
        }
        val verifier = object : X509TrustManager {
            private fun check(chain: Array<out X509Certificate>) {
                if (chain.size != 1 || chain[0].publicKey.algorithm != "EC" || (!discoverParticipants && pinned(chain[0]) == null)) throw java.security.cert.CertificateException("Unverified peer")
                chain[0].checkValidity()
            }
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, type: String) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>, type: String) = check(chain)
        }
        return SSLContext.getInstance("TLSv1.2").apply { init(arrayOf(manager), arrayOf(verifier), SecureRandom()) }
            .createSSLEngine().apply {
                useClientMode = client
                if (!client) needClientAuth = true
                enabledProtocols = arrayOf("TLSv1.2")
                enabledCipherSuites = arrayOf("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")
            }
    }
}
