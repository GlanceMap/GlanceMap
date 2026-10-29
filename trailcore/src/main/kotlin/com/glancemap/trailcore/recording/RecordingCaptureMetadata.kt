package com.glancemap.trailcore.recording

/** Initial recording configuration; absent fields remain unknown for older drafts. */
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
    fun extensionValues(): Map<String, String> =
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
                effectiveLocationIntervalMillisAtStart?.takeIf { it > 0 }?.toString(),
            "recordingElevationSourceAtStart" to elevationSourceAtStart,
            "recordingPowerSaveModeAtStart" to powerSaveModeAtStart?.toString(),
        ).mapNotNull { (key, value) ->
            value?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }?.let { key to it }
        }.toMap()
}
