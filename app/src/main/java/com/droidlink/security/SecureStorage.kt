package com.droidlink.security

import android.content.Context
import android.util.Base64

/** Minimal encrypted key/value contract for credentials. */
interface SecureKeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/**
 * SharedPreferences-backed store; every value is AES-GCM encrypted with a Keystore key.
 * Plain-text secrets never touch disk. Undecryptable entries (e.g. key invalidated) are dropped.
 */
class PrefsSecureKeyValue(context: Context, private val cipher: SecretCipher) : SecureKeyValue {
    private val prefs = context.applicationContext.getSharedPreferences("droidlink_secure", Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            prefs.edit().remove(key).apply()
            null
        }
    }

    override fun put(key: String, value: String) {
        val enc = Base64.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(key, enc).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

object SecureKeys {
    const val DEVICE_ID = "device_id"
    const val DEVICE_SECRET = "device_secret"
    const val SERVER_URL = "server_url"
    const val DEVICE_NAME = "device_name"
}
