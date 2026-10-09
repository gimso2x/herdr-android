package com.gimso2x.herdr.access

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface TokenStore {
    /** Returns the stored token, or null when none is stored or it cannot be read. */
    fun get(): ServiceToken?

    /** Returns false when the token could not be persisted. */
    fun put(token: ServiceToken): Boolean

    fun clear()
}

class MemoryTokenStore(private var token: ServiceToken? = null) : TokenStore {
    override fun get(): ServiceToken? = token

    override fun put(token: ServiceToken): Boolean {
        this.token = token
        return true
    }

    override fun clear() {
        token = null
    }
}

/** Service token in EncryptedSharedPreferences (Android Keystore AES256_GCM master key). */
class EncryptedTokenStore(context: Context) : TokenStore {
    private val prefs = try {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        null
    }

    fun isAvailable(): Boolean = prefs != null

    override fun get(): ServiceToken? {
        val stored = prefs ?: return null
        return try {
            val id = stored.getString(KEY_CLIENT_ID, null)?.trim().orEmpty()
            val secret = stored.getString(KEY_CLIENT_SECRET, null)?.trim().orEmpty()
            if (id.isEmpty() || secret.isEmpty()) null else ServiceToken(id, secret)
        } catch (_: Exception) {
            null
        }
    }

    override fun put(token: ServiceToken): Boolean {
        val stored = prefs ?: return false
        return try {
            stored.edit()
                .putString(KEY_CLIENT_ID, token.clientId)
                .putString(KEY_CLIENT_SECRET, token.clientSecret)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    override fun clear() {
        try {
            prefs?.edit()?.clear()?.commit()
        } catch (_: Exception) {
            // Nothing readable is left to leak if the store itself is broken.
        }
    }

    private companion object {
        const val PREFS_NAME = "herdr_access"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
    }
}
