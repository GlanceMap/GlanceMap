package com.glancemap.trailcore.recording

import com.glancemap.trailcore.geo.GeoPoint
import kotlin.math.abs

const val RECORDING_ACCURACY_INTERPRETATION_RAW = "raw"
const val RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS = "suspect_constant_watch_gps"

/** Canonical recording sample; renderer and platform coordinate types stay in app adapters. */
data class RecordingPoint(
    val location: GeoPoint,
    val elevationMeters: Double?,
    val timeMillis: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val effectiveAccuracyMeters: Float? = accuracyMeters,
    val accuracyInterpretation: String = RECORDING_ACCURACY_INTERPRETATION_RAW,
    val elevationSource: String? = null,
    val heartRateBpm: Int? = null,
    val stepCount: Int? = null,
    val cadenceSpm: Int? = null,
    val powerWatts: Int? = null,
    val barometricPressureHpa: Double? = null,
    val startsNewSegment: Boolean = false,
    val segmentStartReason: String? = null,
    /** Internal fixed-lag provenance; it is not written to GPX. */
    val trajectoryFinalized: Boolean = false,
)

object RecordingSegmentStartReason {
    const val MANUAL_PAUSE = "MANUAL_PAUSE"
    const val AUTO_PAUSE = "AUTO_PAUSE"
    const val GPS_GAP = "GPS_GAP"
    const val SOURCE_RELOCATION = "SOURCE_RELOCATION"
    const val SESSION_RECOVERY = "SESSION_RECOVERY"
}

data class RecordingAccuracyProvenance(
    val effectiveAccuracyMeters: Float?,
    val interpretation: String,
)

/** The platform adapter supplies eligibility only after confirming the source/device policy. */
fun recordingAccuracyProvenance(
    rawAccuracyMeters: Float?,
    watchGpsAccuracyFloorEligible: Boolean,
): RecordingAccuracyProvenance {
    val validAccuracy = rawAccuracyMeters?.takeIf { it.isFinite() && it >= 0f }
    val effectiveAccuracy =
        if (
            watchGpsAccuracyFloorEligible &&
            validAccuracy != null &&
            abs(validAccuracy - RecordingSettings.RECORDING_WATCH_GPS_ACCURACY_FLOOR_M) <=
            RecordingSettings.RECORDING_WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M
        ) {
            RecordingSettings.RECORDING_WATCH_GPS_EFFECTIVE_ACCURACY_M
        } else {
            rawAccuracyMeters
        }
    val corrected = validAccuracy != null && effectiveAccuracy != rawAccuracyMeters
    return RecordingAccuracyProvenance(
        effectiveAccuracyMeters = effectiveAccuracy,
        interpretation =
            if (watchGpsAccuracyFloorEligible && corrected) {
                RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS
            } else {
                RECORDING_ACCURACY_INTERPRETATION_RAW
            },
    )
}

data class RecordingExternalDistanceSessionState(
    val baseMeters: Double,
    val sessionMeters: Double,
)

fun advanceRecordingExternalDistanceSession(
    totalDistanceMeters: Double,
    baseMeters: Double?,
    previousSessionMeters: Double?,
): RecordingExternalDistanceSessionState? {
    if (!totalDistanceMeters.isFinite() || totalDistanceMeters < 0.0) return null
    val previousSession = previousSessionMeters?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val proposedBase = baseMeters?.takeIf { it.isFinite() } ?: (totalDistanceMeters - previousSession)
    val proposedSession = (totalDistanceMeters - proposedBase).coerceAtLeast(0.0)
    return if (proposedSession + DEFAULT_EXTERNAL_DISTANCE_RESET_TOLERANCE_METERS < previousSession) {
        RecordingExternalDistanceSessionState(
            baseMeters = totalDistanceMeters - previousSession,
            sessionMeters = previousSession,
        )
    } else {
        RecordingExternalDistanceSessionState(
            baseMeters = proposedBase,
            sessionMeters = maxOf(previousSession, proposedSession),
        )
    }
}

private const val DEFAULT_EXTERNAL_DISTANCE_RESET_TOLERANCE_METERS = 0.5
