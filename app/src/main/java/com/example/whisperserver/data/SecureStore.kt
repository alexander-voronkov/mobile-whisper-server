package com.example.whisperserver.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key/value store for sensitive strings (the optional server API key
 * and HuggingFace token). Backed by [EncryptedSharedPreferences], whose keys are
 * held in the Android Keystore.
 *
 * Falls back to plain SharedPreferences only if the Keystore-backed store cannot
 * be initialised (e.g. a corrupted keyset), so the app keeps working; failures
 * are logged.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().apply {
            if (value.isBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, value)
        }.apply()

    var hfToken: String
        get() = prefs.getString(KEY_HF_TOKEN, "").orEmpty()
        set(value) = prefs.edit().apply {
            if (value.isBlank()) remove(KEY_HF_TOKEN) else putString(KEY_HF_TOKEN, value)
        }.apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    val hasHfToken: Boolean get() = hfToken.isNotBlank()

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open encrypted store, falling back to plaintext", t)
            context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "SecureStore"
        private const val ENCRYPTED_FILE = "whisper_secrets"
        private const val FALLBACK_FILE = "whisper_secrets_plain"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_HF_TOKEN = "hf_token"
    }
}
