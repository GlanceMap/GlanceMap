package com.glancemap.glancemapwearos.presentation.features.recording

import android.location.Location
import android.os.Build
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.core.service.location.model.gnssSatellitesUsed
import com.glancemap.glancemapwearos.core.service.location.model.gnssUsedCn0AvgDbHz
import org.json.JSONObject

/** Captured once at recording start; legacy drafts leave this unknown. */
data class RecordingCaptureMetadata(
    val appVersion: String? = null,
    val appBuild: Int? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val androidApiLevel: Int? = null,
    val androidBuild: String? = null,
    val locationSourceModeAtStart: String? = null,
    val sampleIntervalSecondsAtStart: Int? = null,
    val effectiveLocationIntervalMillisAtStart: Long? = null,
    val elevationSourceAtStart: String? = null,
    val powerSaveModeAtStart: Boolean? = null,
) {
    internal fun extensionValues(): Map<String, String> =
        mapOf(
            "recordingAppVersion" to appVersion,
            "recordingAppBuild" to appBuild?.takeIf { it > 0 }?.toString(),
            "recordingDeviceManufacturer" to deviceManufacturer,
            "recordingDeviceModel" to deviceModel,
            "recordingAndroidVersion" to androidVersion,
            "recordingAndroidApiLevel" to androidApiLevel?.takeIf { it > 0 }?.toString(),
            "recordingAndroidBuild" to androidBuild,
            "recordingLocationSourceModeAtStart" to locationSourceModeAtStart,
            "recordingSampleIntervalSecondsAtStart" to sampleIntervalSecondsAtStart?.takeIf { it > 0 }?.toString(),
            "recordingEffectiveLocationIntervalMillisAtStart" to
                effectiveLocationIntervalMillisAtStart?.takeIf { it > 0L }?.toString(),
            "recordingElevationSourceAtStart" to elevationSourceAtStart,
            "recordingPowerSaveModeAtStart" to powerSaveModeAtStart?.toString(),
        ).mapNotNull { (key, value) ->
            value?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }?.let { key to it }
        }.toMap()
}

internal fun captureRecordingMetadata(
    locationSourceMode: String?,
    sampleIntervalSeconds: Int,
    effectiveLocationIntervalMillis: Long,
    elevationSource: String,
    powerSaveMode: Boolean?,
): RecordingCaptureMetadata =
    RecordingCaptureMetadata(
        appVersion = BuildConfig.VERSION_NAME,
        appBuild = BuildConfig.VERSION_CODE,
        deviceManufacturer = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE,
        androidApiLevel = Build.VERSION.SDK_INT,
        androidBuild = Build.DISPLAY,
        locationSourceModeAtStart = locationSourceMode,
        sampleIntervalSecondsAtStart = sampleIntervalSeconds,
        effectiveLocationIntervalMillisAtStart = effectiveLocationIntervalMillis,
        elevationSourceAtStart = elevationSource,
        powerSaveModeAtStart = powerSaveMode,
    )

internal fun RecordingCaptureMetadata.toJson(): JSONObject = JSONObject(extensionValues())

internal fun JSONObject.toRecordingCaptureMetadata(): RecordingCaptureMetadata =
    RecordingCaptureMetadata(
        appVersion = metadataString("recordingAppVersion"),
        appBuild = metadataString("recordingAppBuild")?.toIntOrNull(),
        deviceManufacturer = metadataString("recordingDeviceManufacturer"),
        deviceModel = metadataString("recordingDeviceModel"),
        androidVersion = metadataString("recordingAndroidVersion"),
        androidApiLevel = metadataString("recordingAndroidApiLevel")?.toIntOrNull(),
        androidBuild = metadataString("recordingAndroidBuild"),
        locationSourceModeAtStart = metadataString("recordingLocationSourceModeAtStart"),
        sampleIntervalSecondsAtStart = metadataString("recordingSampleIntervalSecondsAtStart")?.toIntOrNull(),
        effectiveLocationIntervalMillisAtStart =
            metadataString("recordingEffectiveLocationIntervalMillisAtStart")?.toLongOrNull(),
        elevationSourceAtStart = metadataString("recordingElevationSourceAtStart"),
        powerSaveModeAtStart = metadataString("recordingPowerSaveModeAtStart")?.toBooleanStrictOrNull(),
    )

/** Accuracy belongs to the Android location measurement, before track/elevation processing. */
data class RecordedLocationQuality(
    val provider: String? = null,
    val sourceMode: String? = null,
    val verticalAccuracyMeters: Float? = null,
    val speedAccuracyMps: Float? = null,
    val ageMillis: Long? = null,
    /** Satellites the GNSS chip used for this direct watch-GPS fix; unknown for fused fixes. */
    val gnssSatellitesUsed: Int? = null,
    /** Mean C/N0 of those satellites, dB-Hz. */
    val gnssUsedCn0AvgDbHz: Float? = null,
)

internal fun Location.recordingLocationQuality(
    sourceMode: String?,
    nowElapsedMillis: Long,
): RecordedLocationQuality =
    RecordedLocationQuality(
        provider = provider,
        sourceMode = sourceMode,
        verticalAccuracyMeters = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() && it.isFinite() && it >= 0f },
        speedAccuracyMps = speedAccuracyMetersPerSecond.takeIf { hasSpeedAccuracy() && it.isFinite() && it >= 0f },
        ageMillis =
            (nowElapsedMillis - elapsedRealtimeNanos / 1_000_000L)
                .takeIf { elapsedRealtimeNanos > 0L && it >= 0L },
        gnssSatellitesUsed = gnssSatellitesUsed(),
        gnssUsedCn0AvgDbHz = gnssUsedCn0AvgDbHz(),
    )

internal fun RecordedLocationQuality.toJson(): JSONObject =
    JSONObject()
        .put("provider", provider ?: JSONObject.NULL)
        .put("sourceMode", sourceMode ?: JSONObject.NULL)
        .put("verticalAccuracyMeters", verticalAccuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: JSONObject.NULL)
        .put("speedAccuracyMps", speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f } ?: JSONObject.NULL)
        .put("ageMillis", ageMillis?.takeIf { it >= 0L } ?: JSONObject.NULL)
        .put("gnssSatellitesUsed", gnssSatellitesUsed?.takeIf { it > 0 } ?: JSONObject.NULL)
        .put("gnssUsedCn0AvgDbHz", gnssUsedCn0AvgDbHz?.takeIf { it.isFinite() && it > 0f } ?: JSONObject.NULL)

internal fun JSONObject.toRecordedLocationQuality(): RecordedLocationQuality =
    RecordedLocationQuality(
        provider = metadataString("provider"),
        sourceMode = metadataString("sourceMode"),
        verticalAccuracyMeters = optDouble("verticalAccuracyMeters").toFloat().takeIf { it.isFinite() && it >= 0f },
        speedAccuracyMps = optDouble("speedAccuracyMps").toFloat().takeIf { it.isFinite() && it >= 0f },
        ageMillis = optLong("ageMillis", -1L).takeIf { it >= 0L },
        gnssSatellitesUsed = optInt("gnssSatellitesUsed", -1).takeIf { it > 0 },
        gnssUsedCn0AvgDbHz = optDouble("gnssUsedCn0AvgDbHz").toFloat().takeIf { it.isFinite() && it > 0f },
    )

private fun JSONObject.metadataString(key: String): String? =
    if (isNull(key)) {
        null
    } else {
        optString(key).takeIf { it.isNotBlank() }
    }
