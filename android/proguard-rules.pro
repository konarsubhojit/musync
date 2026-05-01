# ProGuard rules for MuSync

# ==================== AndroidYouTubePlayer ====================
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-keep interface com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-keepclassmembers class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }

# ==================== Socket.IO / Engine.IO ====================
-keep class io.socket.** { *; }
-keep interface io.socket.** { *; }
-keepclassmembers class io.socket.** { *; }
-keep class io.socket.client.** { *; }
-keep class io.socket.engineio.client.** { *; }
-keep class io.socket.emitter.** { *; }
-keep class io.socket.parser.** { *; }

# OkHttp (used by Socket.IO transport)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Okio
-keep class okio.** { *; }
-dontwarn okio.**

# ==================== Kotlin & Coroutines ====================
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# ==================== Hilt ====================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# ==================== Gson / JSON ====================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# ==================== General Android ====================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
