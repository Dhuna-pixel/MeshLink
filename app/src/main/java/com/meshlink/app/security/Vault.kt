package com.meshlink.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.*
import com.meshlink.app.data.preferences.PreferenceStorage
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** Per-record randomized authenticated encryption. The store and record name bind ciphertext to its purpose. */
class Vault(context: Context, private val name: String) : PreferenceStorage {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun cipher() = Cipher.getInstance("AES/GCM/NoPadding")
    private val localKey by lazy { getOrCreateKey() }
    private fun getOrCreateKey() = synchronized(Vault::class.java) {
        val alias = "MeshLink.v2.local"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT).setKeySize(256)
                .setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    fun encrypt(key: String, value: String): String {
        val c = cipher(); c.init(Cipher.ENCRYPT_MODE, localKey); c.updateAAD((name + "/" + key).toByteArray())
        return (c.iv + c.doFinal(value.toByteArray())).b64()
    }
    override fun put(key: String, value: String) {
        val record = encrypt(key,value)
        check(prefs.edit().putString(key, record).commit()) { "Storage write failed" }
    }
    override fun get(key: String): String? {
        return prefs.getString(key,null)?.let { decrypt(key,it) }
    }
    fun decrypt(key: String, record: String): String {
        val bytes = record.bytes64()
        val c = cipher(); c.init(Cipher.DECRYPT_MODE, localKey, GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        c.updateAAD((name + "/" + key).toByteArray())
        return c.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }
}
