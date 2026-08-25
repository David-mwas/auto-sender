package com.dmwas.autosendapp

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton that tracks user activity time for auto-lock functionality.
 * Call [recordActivity] on any user interaction, and [isLocked] to check
 * whether the inactivity timeout has been exceeded.
 */
object LockManager {

    private val lastActivityTime = AtomicLong(System.currentTimeMillis())

    // Timeout preference key
    private const val PREFS_NAME = "lock_prefs"
    private const val KEY_TIMEOUT_MS = "auto_lock_timeout_ms"

    // Preset timeout options (ms). -1 = never
    val TIMEOUT_OPTIONS = listOf(
        "1 min"  to 60_000L,
        "5 min"  to 300_000L,
        "10 min" to 600_000L,
        "30 min" to 1_800_000L,
        "Never"  to -1L
    )
    val DEFAULT_TIMEOUT_MS = 300_000L // 5 minutes

    fun recordActivity() {
        lastActivityTime.set(System.currentTimeMillis())
    }

    /** Returns true if inactivity has exceeded the stored timeout. */
    fun isLocked(context: Context): Boolean {
        val timeoutMs = getTimeoutMs(context)
        if (timeoutMs == -1L) return false
        return (System.currentTimeMillis() - lastActivityTime.get()) >= timeoutMs
    }

    fun getTimeoutMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
    }

    fun saveTimeoutMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_TIMEOUT_MS, ms).apply()
    }

    /** Call this when user manually locks (or on app unlock) to reset the timer. */
    fun resetTimer() {
        lastActivityTime.set(System.currentTimeMillis())
    }
}
