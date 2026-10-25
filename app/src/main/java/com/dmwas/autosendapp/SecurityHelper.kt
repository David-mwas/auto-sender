package com.dmwas.autosendapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

object SecurityHelper {
    private const val TAG = "SecurityHelper"
    private const val PREFS_NAME = "secure_prefs"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun resetCorruptedKeystoreAndPrefs(context: Context) {
        try {
            context.applicationContext.deleteSharedPreferences(PREFS_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deleteSharedPreferences", e)
        }
        try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear fallback prefs", e)
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete AndroidKeyStore master key alias", e)
        }
    }

    @Synchronized
    fun getSecurePrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }

        val prefs = try {
            createEncryptedPrefs(context)
        } catch (e: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences corrupted or key invalidated; resetting", e)
            resetCorruptedKeystoreAndPrefs(context)
            try {
                createEncryptedPrefs(context)
            } catch (retryEx: Throwable) {
                Log.e(TAG, "Fallback to standard private SharedPreferences", retryEx)
                context.applicationContext.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
        cachedPrefs = prefs
        return prefs
    }

    fun savePin(context: Context, pin: String) {
        try {
            getSecurePrefs(context).edit().putString("mpesa_pin", pin).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "savePin failed; resetting secure prefs and retrying", e)
            cachedPrefs = null
            resetCorruptedKeystoreAndPrefs(context)
            getSecurePrefs(context).edit().putString("mpesa_pin", pin).apply()
        }
    }

    fun getPin(context: Context): String? {
        return try {
            getSecurePrefs(context).getString("mpesa_pin", null)
        } catch (e: Throwable) {
            Log.w(TAG, "getPin failed; resetting secure prefs", e)
            cachedPrefs = null
            resetCorruptedKeystoreAndPrefs(context)
            null
        }
    }
}
