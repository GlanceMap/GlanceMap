package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.formatting.DurationFormatter
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import org.mapsforge.core.model.LatLong
import kotlin.math.roundToInt

data class GpxFileState(
    val name: String,
    val path: String,
    val title: String?,
    val distance: Double,
    val elevationGain: Double,
    val elevationLoss: Double = 0.0,
    val estimatedDurationSec: Double?,
    val isActive: Boolean = false,
    val isActivity: Boolean = false,
    val activityProfile: String? = null,
    val activityDurationSec: Double? = null,
    val activitySummary: RecordingDashboardSnapshot? = null,
) {
    val displayTitle: String
        get() = name

    fun formattedDistance(isMetric: Boolean): Pair<String, String> = UnitFormatter.formatDistance(distance, isMetric)

    fun formattedElevation(isMetric: Boolean): Pair<String, String> = UnitFormatter.formatElevation(elevationGain, isMetric)

    fun formattedElevationLoss(isMetric: Boolean): Pair<String, String> = UnitFormatter.formatElevation(elevationLoss, isMetric)

    fun formattedEtaShort(): String = DurationFormatter.formatDurationShort(estimatedDurationSec)

    fun formattedActivityDurationShort(): String = DurationFormatter.formatDurationShort(activityDurationSec)

    fun formattedActivityDurationClock(): String {
        val seconds = activityDurationSec
        if (seconds == null || !seconds.isFinite() || seconds <= 0.0) return "00:00:00"
        val totalSeconds = seconds.roundToInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return "${hours.twoDigits()}:${minutes.twoDigits()}:${secs.twoDigits()}"
    }
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

data class GpxExportUiState(
    val filePath: String? = null,
    val isSending: Boolean = false,
    val message: String? = null,
)

data class TrackPoint(
    val latLong: LatLong,
    val elevation: Double?,
    val startsNewSegment: Boolean = false,
    val hasTimestamp: Boolean = false,
    val timeMillis: Long? = null,
    val accuracyMeters: Float? = null,
    val effectiveAccuracyMeters: Float? = null,
    val accuracyInterpretation: String? = null,
    val speedMps: Float? = null,
    val heartRateBpm: Int? = null,
    val stepCount: Int? = null,
    val cadenceSpm: Int? = null,
    val powerWatts: Int? = null,
    val barometricPressureHpa: Double? = null,
    val guidanceHint: GpxGuidanceHint? = null,
)

data class GpxGuidanceHint(
    val commandCode: String?,
    val message: String?,
    val source: GpxGuidanceHintSource,
)

enum class GpxGuidanceHintSource {
    BROUTER,
    GPX_SYMBOL,
}

data class GpxTrackDetails(
    val id: String,
    val points: List<LatLong>,
    val trackPoints: List<TrackPoint> =
        points.map { TrackPoint(latLong = it, elevation = null) },
    val title: String?,
    val distance: Double,
    val elevationGain: Double,
    val startPoint: LatLong?,
    val endPoint: LatLong?,
)

data class TrackPosition(
    val trackId: String,
    val segmentIndex: Int,
    val t: Double,
)

data class GpxInspectionAStats(
    val distanceFromStart: Double,
    val elevationGainFromStart: Double,
    val elevationLossFromStart: Double,
    val durationFromStartSec: Double?,
    val distanceToEnd: Double,
    val elevationGainToEnd: Double,
    val elevationLossToEnd: Double,
    val durationToEndSec: Double?,
)

data class GpxInspectionLeg(
    val distance: Double,
    val elevationGain: Double,
    val elevationLoss: Double,
    val durationSec: Double?,
)

data class ElevationSample(
    val distance: Double,
    val elevation: Double,
    val cumulativeAscent: Double,
    val cumulativeDescent: Double,
    val cumulativeDurationSec: Double?,
)

data class GpxElevationProfileUiState(
    val trackPath: String,
    val trackTitle: String,
    val totalDistance: Double,
    val totalAscent: Double,
    val totalDescent: Double,
    val totalDurationSec: Double?,
    val samples: List<ElevationSample>,
    val minElevation: Double?,
    val maxElevation: Double?,
)

sealed interface GpxInspectionUiState {
    val trackTitle: String?
}

data class InspectionAUiState(
    override val trackTitle: String?,
    val a: GpxInspectionAStats,
) : GpxInspectionUiState

data class InspectionABUiState(
    override val trackTitle: String?,
    val sToA: GpxInspectionLeg,
    val aToB: GpxInspectionLeg,
    val bToE: GpxInspectionLeg,
) : GpxInspectionUiState
