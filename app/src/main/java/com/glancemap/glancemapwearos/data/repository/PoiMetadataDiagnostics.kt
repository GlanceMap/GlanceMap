package com.glancemap.glancemapwearos.data.repository

import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import java.io.File

// CPU time is measured only around synchronous work on the same IO thread.
internal inline fun <T> tracePoiMetadata(
    stage: String,
    file: File? = null,
    block: () -> T,
): T {
    if (!DebugTelemetry.isFullDiagnosticsCaptureEnabled()) return block()
    val elapsed = SystemClock.elapsedRealtime()
    val uptime = SystemClock.uptimeMillis()
    val cpu = SystemClock.currentThreadTimeMillis()
    try {
        return block()
    } finally {
        val elapsedMs = SystemClock.elapsedRealtime() - elapsed
        val uptimeMs = SystemClock.uptimeMillis() - uptime
        val cpuMs = SystemClock.currentThreadTimeMillis() - cpu
        DebugTelemetry.log("POI") {
            "event=metadata_stage stage=$stage fileId=${file?.absolutePath?.hashCode()?.toUInt()?.toString(16)} " +
                "elapsedMs=$elapsedMs uptimeMs=$uptimeMs cpuMs=$cpuMs"
        }
    }
}

internal suspend fun <T> PoiFileMetadataCache<T>.getOrLoadTraced(
    file: File,
    stage: String,
    categoryIds: Set<Int> = emptySet(),
    load: suspend () -> T,
): T {
    if (!DebugTelemetry.isFullDiagnosticsCaptureEnabled()) return getOrLoad(file, categoryIds, load = load)
    val elapsed = SystemClock.elapsedRealtime()
    val uptime = SystemClock.uptimeMillis()
    var source: PoiMetadataCacheSource? = null
    try {
        return getOrLoad(file, categoryIds, onSource = { source = it }, load = load)
    } finally {
        val elapsedMs = SystemClock.elapsedRealtime() - elapsed
        val uptimeMs = SystemClock.uptimeMillis() - uptime
        DebugTelemetry.log("POI") {
            "event=metadata_cache stage=$stage fileId=${file.absolutePath.hashCode().toUInt().toString(16)} " +
                "bytes=${file.length()} selectionSize=${categoryIds.size} source=$source " +
                "elapsedMs=$elapsedMs uptimeMs=$uptimeMs"
        }
    }
}
