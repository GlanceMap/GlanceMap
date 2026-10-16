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

# Phone guidance drafts are Gson persisted; keep every reflected field and nested model stable.
-keep class com.glancemap.glancemapcompanionapp.map.PhoneGuidanceRouteDraft { *; }
-keep class com.glancemap.glancemapcompanionapp.map.PhoneGuidanceProgressDraft { *; }
-keep class com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig { *; }
-keep class com.glancemap.trailcore.profile.TrailPoint { *; }
-keep class com.glancemap.trailcore.profile.TrailGuidanceHint { *; }
-keep enum com.glancemap.trailcore.profile.TrailGuidanceHintSource { *; }


# Library folder and activity metadata must survive minified app updates.
-keep class com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganization { *; }
-keep class com.glancemap.glancemapcompanionapp.library.PhoneLibraryFolder { *; }
-keep class com.glancemap.glancemapcompanionapp.library.PhoneLibraryEntryOrganization { *; }
-keep enum com.glancemap.glancemapcompanionapp.library.PhoneLibraryActivity { *; }

# Hike planning metadata and exact route snapshots use Gson across minified releases.
-keep class com.glancemap.glancemapcompanionapp.hike.PhoneHikeRoute { *; }
-keep class com.glancemap.glancemapcompanionapp.hike.PhoneHikeCheckpoint { *; }
-keep class com.glancemap.glancemapcompanionapp.hike.PhoneHikePlan { *; }
-keep class com.glancemap.glancemapcompanionapp.hike.PhoneHikeWorkspaceData { *; }

# Adventure plans use Gson reflection; preserve both storage readers and nested metadata.
-keep class com.glancemap.glancemapcompanionapp.routes.MissionPlanRepository$MissionPlanIndex { *; }
-keep class com.glancemap.glancemapcompanionapp.routes.SavedMissionPlanIndex { *; }
-keep class com.glancemap.glancemapcompanionapp.map.MissionPlanStorageIndex { *; }
-keep class com.glancemap.glancemapcompanionapp.routes.MissionPlanDay { *; }
-keep class com.glancemap.glancemapcompanionapp.routes.MissionAdventure { *; }
-keep enum com.glancemap.glancemapcompanionapp.routes.MissionMeal { *; }
-keep enum com.glancemap.glancemapcompanionapp.routes.MissionOvernight { *; }
-keep enum com.glancemap.glancemapcompanionapp.routes.MissionBooking { *; }
-keep class com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation { *; }
