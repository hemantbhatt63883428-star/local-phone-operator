package com.agentbubble.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Adapted from Local Phone Operator: device-bound AES-GCM keys, separated per provider with AAD. */
object SecretVault {
    private const val ALIAS = "agentbubble_provider_keys_v1"
    internal var keyProvider: () -> SecretKey = {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun encrypt(provider: String, text: String): String {
        if (text.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyProvider()); c.updateAAD(provider.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(provider: String, blob: String): String {
        if (blob.isEmpty()) return ""
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        c.updateAAD(provider.toByteArray(Charsets.UTF_8))
        return String(c.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
