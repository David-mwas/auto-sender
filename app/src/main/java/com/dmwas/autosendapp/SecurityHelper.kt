package com.dmwas.autosendapp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityHelper {
    private const val PREFS_NAME = "secure_prefs"

    fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun savePin(context: Context, pin: String) {
        getSecurePrefs(context).edit().putString("mpesa_pin", pin).apply()
    }

    fun getPin(context: Context): String? {
        return getSecurePrefs(context).getString("mpesa_pin", null)
    }
}
