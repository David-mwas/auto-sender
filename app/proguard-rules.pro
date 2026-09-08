# AutoSender ProGuard Rules

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ── Gson / JSON models ────────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.dmwas.autosendapp.TransactionLog { *; }
-keep class com.dmwas.autosendapp.Contact { *; }

# ── Encrypted SharedPreferences / security-crypto ────────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── Biometric ─────────────────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
