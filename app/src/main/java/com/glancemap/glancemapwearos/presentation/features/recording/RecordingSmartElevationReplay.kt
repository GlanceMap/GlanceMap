package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository

/** Deterministic JVM replay adapter; it delegates to the production smart-elevation core. */
internal data class RecordingSmartElevationReplayFrame(
    val elapsedRealtimeMillis: Long,
    val pressureSamples: List<RecordingPressureSample> = emptyList(),
    val demElevationMeters: Double? = null,
    val demAxisLen: Int? = null,
    val gpsElevationMeters: Double? = null,
    val gpsVerticalAccuracyMeters: Float? = null,
    val horizontalAccuracyMeters: Float? = null,
    val absoluteElevationSource: String = SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO,
    val startsNewSegment: Boolean = false,
    val activityProfile: String = SettingsRepository.ACTIVITY_PROFILE_HIKE,
)

internal class RecordingSmartElevationReplay(
    private val filter: RecordingHybridElevationFilter = RecordingHybridElevationFilter(),
) {
    fun apply(frame: RecordingSmartElevationReplayFrame): RecordingHybridElevationResult {
        frame.pressureSamples.forEach(filter::observePressure)
        return filter.update(
            RecordingHybridElevationInput(
                demElevationMeters = frame.demElevationMeters,
                demAxisLen = frame.demAxisLen,
                gpsElevationMeters = frame.gpsElevationMeters,
                gpsVerticalAccuracyMeters = frame.gpsVerticalAccuracyMeters,
                horizontalAccuracyMeters = frame.horizontalAccuracyMeters,
                absoluteElevationMeters = resolvedReplayElevation(frame),
                absoluteElevationSource = frame.absoluteElevationSource,
                elapsedRealtimeMillis = frame.elapsedRealtimeMillis,
                enabled = true,
                startsNewSegment = frame.startsNewSegment,
                activityProfile = frame.activityProfile,
            ),
        )
    }

    fun diagnostics(): RecordingSmartElevationDiagnostics = filter.diagnostics()

    fun reset() = filter.reset()
}

private fun resolvedReplayElevation(frame: RecordingSmartElevationReplayFrame): Double? =
    when (frame.absoluteElevationSource) {
        SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM -> frame.demElevationMeters
        SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS -> frame.gpsElevationMeters
        SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO ->
            frame.demElevationMeters ?: frame.gpsElevationMeters
        else -> null
    }
