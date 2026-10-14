package com.glancemap.trailcore.recording

const val RECORDING_ELEVATION_SOURCE_DEM_MISSING = "DEM_MISSING"

data class RecordingAbsoluteElevation(
    val elevationMeters: Double?,
    val source: String,
)

/** Explicit DEM mode preserves missing coverage; only AUTO falls back from DEM to GPS. */
fun resolveRecordingAbsoluteElevation(
    source: String,
    demElevationMeters: Double?,
    gpsElevationMeters: Double?,
): RecordingAbsoluteElevation {
    val selected = RecordingSettings.normalizeElevationSource(source)
    val elevation =
        when (selected) {
            RecordingSettings.RECORDING_ELEVATION_SOURCE_DEM -> demElevationMeters
            RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO -> demElevationMeters ?: gpsElevationMeters
            RecordingSettings.RECORDING_SOURCE_DISABLED -> null
            else -> gpsElevationMeters
        }
    val resolvedSource =
        when {
            selected == RecordingSettings.RECORDING_ELEVATION_SOURCE_DEM && demElevationMeters != null -> selected
            selected == RecordingSettings.RECORDING_ELEVATION_SOURCE_DEM -> RECORDING_ELEVATION_SOURCE_DEM_MISSING
            selected == RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO && demElevationMeters != null ->
                RecordingSettings.RECORDING_ELEVATION_SOURCE_DEM
            selected == RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO && gpsElevationMeters != null ->
                RecordingSettings.RECORDING_ELEVATION_SOURCE_GPS
            else -> selected
        }
    return RecordingAbsoluteElevation(elevation, resolvedSource)
}
