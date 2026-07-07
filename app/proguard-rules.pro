# Add project specific ProGuard rules here.

# Keep JNI entry points used by the native whisper bridge.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.example.whisperserver.native.** { *; }

# OkHttp / Okio (used by the model downloader).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Kotlin coroutines.
-dontwarn kotlinx.coroutines.**

# Keep DataStore generated classes.
-keep class androidx.datastore.*.** { *; }
