# ✅ Better crash reports and generic type information used by Gson persistence.
-keepattributes SourceFile,LineNumberTable,Signature
-renamesourcefileattribute SourceFile

# ✅ Keep your Service entry points
-keep class com.glancemap.glancemapcompanionapp.transfer.service.FileTransferService { *; }
-keep class com.glancemap.glancemapcompanionapp.transfer.service.FileTransferService$LocalBinder { *; }

# ==========================================================
# 🚀 KTOR (CIO) SAFETY (Release Builds)
# ==========================================================

# ❌ REMOVED (was causing "Invalid flag :14")
# -keepresourcefiles META-INF/services/*

# ✅ Keep Ktor engine + CIO implementation
-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.cio.** { *; }

# ==========================================================
# 🧵 Coroutines
# ==========================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.android.AndroidExceptionPreHandler { <init>(); }

# Suppress warnings (optional)
-dontwarn io.netty.**
-dontwarn java.lang.instrument.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# REC drafts use Gson reflection and must remain readable across minified releases.
-keep class com.glancemap.glancemapcompanionapp.map.PhoneRecordingDraft { *; }
-keep class com.glancemap.glancemapcompanionapp.map.PhoneRecordingSettings { *; }
-keep class com.glancemap.trailcore.recording.RecordingPoint { *; }
-keep class com.glancemap.trailcore.geo.GeoPoint { *; }
