package com.glancemap.glancemapwearos.core.service.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.domain.sensors.HEADING_SENSOR_STRING_TYPE
import com.glancemap.glancemapwearos.domain.sensors.HEADING_SENSOR_TYPE
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private val diagnosticsExporterTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
private const val CONNECTED_PHONE_NODES_TIMEOUT_MS = 1_500L
private const val HISTORICAL_EXIT_TRACE_MAX_LINES = 80
private const val HISTORICAL_EXIT_TRACE_MAX_CHARS_PER_LINE = 240
private const val HISTORICAL_EXIT_LOCK_OWNER_MAX_LINES = 36

internal data class LocationPermissionSnapshot(
    val hasFinePermission: Boolean,
    val hasCoarsePermission: Boolean,
) {
    val mode: String
        get() =
            when {
                hasFinePermission -> "precise"
                hasCoarsePermission -> "approximate_only"
                else -> "none"
            }
}

internal data class GpsCapabilitySnapshot(
    val locationManagerAvailable: Boolean,
    val systemLocationEnabled: Boolean?,
    val watchGpsHardwareFeature: Boolean?,
    val gpsProviderPresent: Boolean,
    val gpsProviderEnabled: Boolean?,
    val networkProviderPresent: Boolean,
    val networkProviderEnabled: Boolean?,
    val passiveProviderPresent: Boolean,
    val passiveProviderEnabled: Boolean?,
    val allProviders: List<String>,
    val connectedPhoneNodeCount: Int?,
    val connectedPhoneNodeCaptureError: String?,
    val activeNetworkTransports: List<String>,
    val activeNetworkValidated: Boolean?,
    val activeNetworkInternetCapable: Boolean?,
)

internal data class SensorInventorySnapshot(
    val headingPublicApiSupported: Boolean,
    val headingAvailable: Boolean,
    val rotationVectorAvailable: Boolean,
    val magnetometerAvailable: Boolean,
    val accelerometerAvailable: Boolean,
    val headingSensor: Sensor?,
    val rotationVectorSensor: Sensor?,
    val magnetometerSensor: Sensor?,
    val accelerometerSensor: Sensor?,
    val allSensors: List<Sensor>,
)

internal data class MemorySnapshot(
    val runtimeMaxHeapBytes: Long,
    val runtimeTotalHeapBytes: Long,
    val runtimeFreeHeapBytes: Long,
    val runtimeUsedHeapBytes: Long,
    val nativeHeapSizeBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val nativeHeapFreeBytes: Long,
    val totalPssKb: Int?,
    val dalvikPssKb: Int?,
    val nativePssKb: Int?,
    val otherPssKb: Int?,
    val totalPrivateDirtyKb: Int?,
    val totalSharedDirtyKb: Int?,
    val availMemBytes: Long?,
    val totalMemBytes: Long?,
    val thresholdBytes: Long?,
    val lowMemory: Boolean?,
    val memoryClassMb: Int?,
    val largeMemoryClassMb: Int?,
    val largeHeapRequested: Boolean,
)

internal data class HistoricalExitReasonsSnapshot(
    val apiSupported: Boolean,
    val entries: List<HistoricalExitReason>,
    val captureError: String? = null,
)

internal data class HistoricalExitReason(
    val timestampMs: Long,
    val reason: String,
    val subReason: Int,
    val importance: Int,
    val status: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
    val tracePreviewLines: List<String> = emptyList(),
    val traceInsightLines: List<String> = emptyList(),
)

internal fun captureSensorInventory(context: Context): SensorInventorySnapshot {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val headingPublicApiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val headingSensor = resolveHeadingSensor(sensorManager)
    val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val allSensors =
        runCatching { sensorManager.getSensorList(Sensor.TYPE_ALL) }
            .getOrDefault(emptyList())
            .sortedWith(compareBy<Sensor> { it.type }.thenBy { it.name ?: "" })
    return SensorInventorySnapshot(
        headingPublicApiSupported = headingPublicApiSupported,
        headingAvailable = headingSensor != null,
        rotationVectorAvailable = rotationVectorSensor != null,
        magnetometerAvailable = magnetometerSensor != null,
        accelerometerAvailable = accelerometerSensor != null,
        headingSensor = headingSensor,
        rotationVectorSensor = rotationVectorSensor,
        magnetometerSensor = magnetometerSensor,
        accelerometerSensor = accelerometerSensor,
        allSensors = allSensors,
    )
}

internal fun resolveHeadingSensor(sensorManager: SensorManager): Sensor? {
    sensorManager.getDefaultSensor(HEADING_SENSOR_TYPE)?.let { return it }
    runCatching {
        sensorManager.getDefaultSensor(HEADING_SENSOR_TYPE, true)
    }.getOrNull()?.let { return it }

    val allSensors =
        runCatching { sensorManager.getSensorList(Sensor.TYPE_ALL) }
            .getOrDefault(emptyList())
    return allSensors.firstOrNull { sensor ->
        sensor.type == HEADING_SENSOR_TYPE || sensor.stringType == HEADING_SENSOR_STRING_TYPE
    }
}

internal fun captureLocationPermissionSnapshot(context: Context): LocationPermissionSnapshot {
    val hasFinePermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    val hasCoarsePermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    return LocationPermissionSnapshot(
        hasFinePermission = hasFinePermission,
        hasCoarsePermission = hasCoarsePermission,
    )
}

internal fun captureGpsCapabilitySnapshot(context: Context): GpsCapabilitySnapshot {
    val locationManager = context.getSystemService(LocationManager::class.java)
    val providers =
        locationManager
            ?.let { manager -> runCatching { manager.allProviders }.getOrDefault(emptyList()) }
            .orEmpty()
            .sorted()
    val connectedPhoneNodes = captureConnectedPhoneNodeCount(context)
    val activeNetwork = captureActiveNetworkSnapshot(context)
    return GpsCapabilitySnapshot(
        locationManagerAvailable = locationManager != null,
        systemLocationEnabled = locationManager?.let { manager -> runCatching { manager.isLocationEnabled }.getOrNull() },
        watchGpsHardwareFeature =
            runCatching {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
            }.getOrNull(),
        gpsProviderPresent = providers.contains(LocationManager.GPS_PROVIDER),
        gpsProviderEnabled = providerEnabled(locationManager, LocationManager.GPS_PROVIDER),
        networkProviderPresent = providers.contains(LocationManager.NETWORK_PROVIDER),
        networkProviderEnabled = providerEnabled(locationManager, LocationManager.NETWORK_PROVIDER),
        passiveProviderPresent = providers.contains(LocationManager.PASSIVE_PROVIDER),
        passiveProviderEnabled = providerEnabled(locationManager, LocationManager.PASSIVE_PROVIDER),
        allProviders = providers,
        connectedPhoneNodeCount = connectedPhoneNodes.count,
        connectedPhoneNodeCaptureError = connectedPhoneNodes.error,
        activeNetworkTransports = activeNetwork.transports,
        activeNetworkValidated = activeNetwork.validated,
        activeNetworkInternetCapable = activeNetwork.internetCapable,
    )
}

private data class ActiveNetworkSnapshot(
    val transports: List<String>,
    val validated: Boolean?,
    val internetCapable: Boolean?,
)

private fun captureActiveNetworkSnapshot(context: Context): ActiveNetworkSnapshot {
    val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
            ?: return ActiveNetworkSnapshot(emptyList(), null, null)
    val capabilities =
        runCatching {
            connectivityManager.activeNetwork?.let { network ->
                connectivityManager.getNetworkCapabilities(network)
            }
        }.getOrNull() ?: return ActiveNetworkSnapshot(emptyList(), null, null)
    return ActiveNetworkSnapshot(
        transports = activeNetworkTransportLabels(capabilities),
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        internetCapable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    )
}

private fun activeNetworkTransportLabels(capabilities: NetworkCapabilities): List<String> =
    buildList {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (isEmpty()) add("other")
    }

private fun providerEnabled(
    locationManager: LocationManager?,
    provider: String,
): Boolean? =
    locationManager?.let { manager ->
        runCatching { manager.isProviderEnabled(provider) }.getOrNull()
    }

private data class ConnectedPhoneNodesSnapshot(
    val count: Int?,
    val error: String?,
)

private fun captureConnectedPhoneNodeCount(context: Context): ConnectedPhoneNodesSnapshot =
    try {
        val nodes =
            Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                CONNECTED_PHONE_NODES_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        ConnectedPhoneNodesSnapshot(
            count = nodes.size,
            error = null,
        )
    } catch (error: Exception) {
        ConnectedPhoneNodesSnapshot(
            count = null,
            error = error.javaClass.simpleName,
        )
    }

internal fun captureMemorySnapshot(context: Context): MemorySnapshot {
    val runtime = Runtime.getRuntime()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val processMemoryInfo =
        runCatching {
            activityManager?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull()
        }.getOrNull()
    val systemMemoryInfo =
        runCatching {
            ActivityManager.MemoryInfo().also { info ->
                activityManager?.getMemoryInfo(info)
            }
        }.getOrNull()

    val runtimeTotalHeapBytes = runtime.totalMemory()
    val runtimeFreeHeapBytes = runtime.freeMemory()
    return MemorySnapshot(
        runtimeMaxHeapBytes = runtime.maxMemory(),
        runtimeTotalHeapBytes = runtimeTotalHeapBytes,
        runtimeFreeHeapBytes = runtimeFreeHeapBytes,
        runtimeUsedHeapBytes = (runtimeTotalHeapBytes - runtimeFreeHeapBytes).coerceAtLeast(0L),
        nativeHeapSizeBytes = Debug.getNativeHeapSize(),
        nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
        nativeHeapFreeBytes = Debug.getNativeHeapFreeSize(),
        totalPssKb = processMemoryInfo?.totalPss,
        dalvikPssKb = processMemoryInfo?.dalvikPss,
        nativePssKb = processMemoryInfo?.nativePss,
        otherPssKb = processMemoryInfo?.otherPss,
        totalPrivateDirtyKb = processMemoryInfo?.totalPrivateDirty,
        totalSharedDirtyKb = processMemoryInfo?.totalSharedDirty,
        availMemBytes = systemMemoryInfo?.availMem,
        totalMemBytes = systemMemoryInfo?.totalMem,
        thresholdBytes = systemMemoryInfo?.threshold,
        lowMemory = systemMemoryInfo?.lowMemory,
        memoryClassMb = activityManager?.memoryClass,
        largeMemoryClassMb = activityManager?.largeMemoryClass,
        largeHeapRequested = (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0,
    )
}

internal fun captureHistoricalProcessExitReasons(context: Context): HistoricalExitReasonsSnapshot {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return HistoricalExitReasonsSnapshot(
            apiSupported = false,
            entries = emptyList(),
        )
    }
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return HistoricalExitReasonsSnapshot(
                apiSupported = true,
                entries = emptyList(),
                captureError = "activity_manager_unavailable",
            )

    return runCatching {
        val entries =
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 6)
                .orEmpty()
                .map { info ->
                    val reason = formatExitReason(info.reason)
                    val traceSnapshot =
                        if (reason == "ANR" || reason.contains("CRASH")) {
                            readHistoricalExitTraceSnapshot(info)
                        } else {
                            HistoricalExitTraceSnapshot()
                        }
                    HistoricalExitReason(
                        timestampMs = info.timestamp,
                        reason = reason,
                        subReason = -1,
                        importance = info.importance,
                        status = info.status,
                        pssKb = info.pss,
                        rssKb = info.rss,
                        description =
                            info.description
                                ?.replace(Regex("\\s+"), " ")
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() },
                        tracePreviewLines = traceSnapshot.previewLines,
                        traceInsightLines = traceSnapshot.insightLines,
                    )
                }
        HistoricalExitReasonsSnapshot(
            apiSupported = true,
            entries = entries,
        )
    }.getOrElse { error ->
        HistoricalExitReasonsSnapshot(
            apiSupported = true,
            entries = emptyList(),
            captureError = error.javaClass.simpleName,
        )
    }
}

private data class HistoricalExitTraceSnapshot(
    val previewLines: List<String> = emptyList(),
    val insightLines: List<String> = emptyList(),
)

private fun readHistoricalExitTraceSnapshot(info: ApplicationExitInfo): HistoricalExitTraceSnapshot =
    runCatching {
        val traceLines =
            info.traceInputStream
                ?.bufferedReader()
                ?.useLines { lines ->
                    lines
                        .map { line ->
                            line
                                .trimEnd()
                                .take(HISTORICAL_EXIT_TRACE_MAX_CHARS_PER_LINE)
                        }.toList()
                }.orEmpty()
        HistoricalExitTraceSnapshot(
            previewLines = traceLines.take(HISTORICAL_EXIT_TRACE_MAX_LINES),
            insightLines = buildHistoricalExitTraceInsights(traceLines),
        )
    }.getOrDefault(HistoricalExitTraceSnapshot())

private fun buildHistoricalExitTraceInsights(traceLines: List<String>): List<String> {
    if (traceLines.isEmpty()) return emptyList()
    val insights = mutableListOf<String>()
    val blockedLine =
        traceLines.firstOrNull { line ->
            "held by thread" in line ||
                "waiting to lock" in line ||
                "blocked on" in line
        }
    blockedLine?.let { insights += "mainOrFirstBlockedLine=$it" }

    val ownerTid =
        blockedLine
            ?.let { Regex("""held by thread\s+(\d+)""").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
    if (ownerTid != null) {
        insights += "lockOwnerThreadId=$ownerTid"
        val ownerStartIndex =
            traceLines.indexOfFirst { line ->
                "tid=$ownerTid " in line || line.endsWith("tid=$ownerTid")
            }
        if (ownerStartIndex >= 0) {
            val ownerLines =
                traceLines
                    .drop(ownerStartIndex)
                    .takeWhileIndexed { index, line -> index == 0 || line.isNotBlank() }
                    .take(HISTORICAL_EXIT_LOCK_OWNER_MAX_LINES)
            insights += "lockOwnerPreviewLineCount=${ownerLines.size}"
            ownerLines.forEachIndexed { index, line ->
                insights += "lockOwner[$index]=$line"
            }
        } else {
            insights += "lockOwnerPreviewLineCount=0"
        }
    }
    return insights
}

private inline fun <T> Iterable<T>.takeWhileIndexed(predicate: (Int, T) -> Boolean): List<T> {
    val result = mutableListOf<T>()
    forEachIndexed { index, item ->
        if (!predicate(index, item)) return result
        result += item
    }
    return result
}

internal fun formatExitReason(reason: Int): String =
    when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

internal fun formatBytesToMb(bytes: Long): String = TelemetryFormatters.bytesToMb(bytes)

internal fun formatNullableBytesToMb(bytes: Long?): String = bytes?.let { formatBytesToMb(it) } ?: "na"

internal fun formatNullableBoolean(value: Boolean?): String = value?.toString() ?: "na"

internal fun formatStringList(values: List<String>): String =
    if (values.isEmpty()) {
        "none"
    } else {
        values.joinToString(separator = ",")
    }

internal fun formatAgeMs(
    nowMs: Long,
    pastMs: Long?,
): String {
    if (pastMs == null || pastMs <= 0L) return "na"
    return (nowMs - pastMs).coerceAtLeast(0L).toString()
}

internal fun formatSensorDescriptor(sensor: Sensor?): String {
    if (sensor == null) return "none"
    val wakeUp = runCatching { sensor.isWakeUpSensor }.getOrDefault(false)
    val reportingMode = runCatching { sensor.reportingMode }.getOrDefault(-1)
    val maxRange = runCatching { sensor.maximumRange }.getOrDefault(Float.NaN)
    val resolution = runCatching { sensor.resolution }.getOrDefault(Float.NaN)
    val powerMa = runCatching { sensor.power }.getOrDefault(Float.NaN)
    return buildString {
        append("type=").append(sensor.type)
        append(",stringType=").append(sensor.stringType ?: "na")
        append(",name=").append(sensor.name ?: "na")
        append(",vendor=").append(sensor.vendor ?: "na")
        append(",version=").append(sensor.version)
        append(",wakeUp=").append(wakeUp)
        append(",minDelayUs=").append(sensor.minDelay)
        append(",maxDelayUs=").append(sensor.maxDelay)
        append(",reportingMode=").append(reportingMode)
        append(",powerMa=").append(if (powerMa.isFinite()) TelemetryFormatters.decimal(powerMa, 3) else "na")
        append(",maxRange=").append(if (maxRange.isFinite()) TelemetryFormatters.decimal(maxRange, 3) else "na")
        append(",resolution=").append(if (resolution.isFinite()) TelemetryFormatters.decimal(resolution, 3) else "na")
    }
}

internal fun captureSessionIdText(sessionId: Long): String {
    if (sessionId <= 0L) return "na"
    return sessionId.toString()
}

internal fun formatCaptureTime(epochMs: Long?): String {
    if (epochMs == null) return "na"
    return diagnosticsExporterTimestampFormatter.format(Instant.ofEpochMilli(epochMs))
}

internal fun formatCaptureEndTime(
    endedAtMs: Long?,
    active: Boolean,
): String {
    if (endedAtMs != null) {
        return diagnosticsExporterTimestampFormatter.format(Instant.ofEpochMilli(endedAtMs))
    }
    return if (active) "capture_active" else "na"
}

internal fun formatCaptureDurationMs(
    startedAtMs: Long?,
    endedAtMs: Long?,
    active: Boolean,
): String {
    val durationMs =
        captureDurationMs(
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            active = active,
        )
    return durationMs?.toString() ?: "na"
}

internal fun captureDurationMs(
    startedAtMs: Long?,
    endedAtMs: Long?,
    active: Boolean,
): Long? {
    if (startedAtMs == null) return null
    val endMs = endedAtMs ?: if (active) System.currentTimeMillis() else null
    if (endMs == null) return null
    return (endMs - startedAtMs).coerceAtLeast(0L)
}

internal fun formatBufferedSpanMs(
    firstBufferedAtMs: Long?,
    lastBufferedAtMs: Long?,
): String {
    val spanMs = bufferedSpanMs(firstBufferedAtMs = firstBufferedAtMs, lastBufferedAtMs = lastBufferedAtMs)
    return spanMs?.toString() ?: "na"
}

internal fun bufferedSpanMs(
    firstBufferedAtMs: Long?,
    lastBufferedAtMs: Long?,
): Long? {
    if (firstBufferedAtMs == null || lastBufferedAtMs == null) return null
    return (lastBufferedAtMs - firstBufferedAtMs).coerceAtLeast(0L)
}

internal fun formatInstallTime(epochMs: Long): String {
    if (epochMs <= 0L) return "na"
    return diagnosticsExporterTimestampFormatter.format(Instant.ofEpochMilli(epochMs))
}
