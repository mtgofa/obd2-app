# Keep OkHttp (uses reflection for platform features)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# --- Wasla release rules ---
# Keep the app's own classes intact (no reflection surprises, easier crash reports)
-keep class com.wasla.router.** { *; }

# Tink / androidx.security (EncryptedSharedPreferences)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# Kotlin coroutines debug agent is not shipped
-dontwarn kotlinx.coroutines.debug.**
