# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for meaningful stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- OkHttp ---
# OkHttp 4.x bundles its own consumer rules, but the platform check and optional
# dependencies benefit from explicit keeps to prevent warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- OkHttp Logging Interceptor ---
-keep class okhttp3.logging.** { *; }

# --- AndroidX Security (EncryptedSharedPreferences) ---
# Tink uses reflection internally
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- Project-specific ---
# Keep WebSocket listener callbacks (called reflectively by OkHttp)
-keep class com.example.nfcapp.WebSocketManager { *; }
