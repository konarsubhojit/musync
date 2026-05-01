# ProGuard rules for MuSync

# ==================== AndroidYouTubePlayer ====================
# Required: library uses reflection to invoke JS callbacks and load the player bridge
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }

# ==================== Socket.IO / Engine.IO ====================
# Required: Socket.IO uses reflection for event-emitter callbacks and JSON parsing
-keep class io.socket.** { *; }

# ==================== OkHttp (Socket.IO WebSocket transport) ====================
# OkHttp ships its own consumer rules; only suppress warnings for platform-only APIs
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ==================== Okio ====================
-dontwarn okio.**

# ==================== Kotlin & Coroutines ====================
# Rules are provided by the Kotlin Gradle plugin and kotlinx-coroutines consumer proguard file;
# only suppress warnings for internal reflection helpers
-dontwarn kotlin.reflect.jvm.internal.**

# ==================== Hilt ====================
# Hilt provides its own consumer rules; only keep annotated entry-point classes
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ==================== Gson / JSON ====================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# ==================== General Android ====================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
