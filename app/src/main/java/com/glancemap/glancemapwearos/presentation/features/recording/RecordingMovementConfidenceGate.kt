package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.trailcore.geo.GeoPoint
import org.mapsforge.core.model.LatLong
import com.glancemap.trailcore.recording.RecordingDelayedDeliveryEvidence as SharedRecordingDelayedDeliveryEvidence
import com.glancemap.trailcore.recording.RecordingMotionEvidence as SharedRecordingMotionEvidence
import com.glancemap.trailcore.recording.RecordingMotionReason as SharedRecordingMotionReason
import com.glancemap.trailcore.recording.RecordingMotionResult as SharedRecordingMotionResult
import com.glancemap.trailcore.recording.RecordingMotionSample as SharedRecordingMotionSample
import com.glancemap.trailcore.recording.RecordingMotionStatus as SharedRecordingMotionStatus
import com.glancemap.trailcore.recording.RecordingMovementConfidenceGate as SharedRecordingMovementConfidenceGate

internal typealias RecordingMotionSample = SharedRecordingMotionSample
internal typealias RecordingDelayedDeliveryEvidence = SharedRecordingDelayedDeliveryEvidence
internal typealias RecordingMotionEvidence = SharedRecordingMotionEvidence
internal typealias RecordingMotionReason = SharedRecordingMotionReason
internal typealias RecordingMotionResult = SharedRecordingMotionResult
internal typealias RecordingMotionStatus = SharedRecordingMotionStatus
internal typealias RecordingMovementConfidenceGate = SharedRecordingMovementConfidenceGate

@Suppress("LongParameterList")
fun RecordingMotionSample(
    latLong: LatLong,
    elapsedRealtimeMillis: Long,
    accuracyMeters: Float?,
    speedMps: Float?,
    speedAccuracyMps: Float?,
    stepCount: Int?,
    cadenceSpm: Int?,
    trustReportedSpeedWithoutAccuracy: Boolean = false,
    delayedDelivery: RecordingDelayedDeliveryEvidence? = null,
): RecordingMotionSample =
    SharedRecordingMotionSample(
        location = GeoPoint(latLong.latitude, latLong.longitude),
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        speedAccuracyMps = speedAccuracyMps,
        stepCount = stepCount,
        cadenceSpm = cadenceSpm,
        trustReportedSpeedWithoutAccuracy = trustReportedSpeedWithoutAccuracy,
        delayedDelivery = delayedDelivery,
    )
