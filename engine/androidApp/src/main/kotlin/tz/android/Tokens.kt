package tz.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import tz.shared.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The session token, encrypted with an AES key that never leaves the
 * Android Keystore; only the ciphertext is in the app's private storage.
 * A token saved in plain text by an older version is moved over once.
 */
class KeystoreTokens(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private companion object {
        const val ALIAS = "tz-session"
        const val KEY = "token.enc"
        const val PLAIN = "token"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    override fun load(): String? {
        prefs.getString(PLAIN, null)?.let { old -> save(old); return old }
        val stored = prefs.getString(KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            // The key is gone (app data restored on another phone): sign in again.
            prefs.edit().remove(KEY).apply()
            null
        }
    }

    override fun save(token: String?) {
        val edit = prefs.edit().remove(PLAIN)
        if (token == null) edit.remove(KEY) else {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val iv = cipher.iv
                val data = iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
                edit.putString(KEY, Base64.encodeToString(data, Base64.NO_WRAP))
            } catch (e: Exception) {
                // No Keystore (a broken device): keep nothing rather than the token in plain text.
                edit.remove(KEY)
            }
        }
        edit.apply()
    }
}
