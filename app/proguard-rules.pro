# ✅ Crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ✅ Keep the Listener Service (entry point)
-keep class com.glancemap.glancemapwearos.core.service.DataLayerListenerService { *; }

# ✅ Coroutines (Standard safety)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ✅ Optional: keep repository/atomic writer if you hit release-only issues
# -keep class com.glancemap.glancemapwearos.data.repository.** { *; }
# -keep class com.glancemap.glancemapwearos.data.repository.internal.AtomicStreamWriter { *; }

# ✅ OkHttp/Okio (only needed if you actually keep OkHttp dependency)
-dontwarn okhttp3.**
-dontwarn okio.**
