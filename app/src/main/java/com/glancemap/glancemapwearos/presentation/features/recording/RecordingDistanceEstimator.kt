package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.trailcore.recording.RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_FULL_SESSION as SHARED_SCOPE_FULL
import com.glancemap.trailcore.recording.RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_POST_RECOVERY_PARTIAL as PARTIAL_SCOPE
import com.glancemap.trailcore.recording.RecordingDistanceComparison as SharedRecordingDistanceComparison
import com.glancemap.trailcore.recording.RecordingDistanceDiagnostics as SharedRecordingDistanceDiagnostics
import com.glancemap.trailcore.recording.RecordingDistanceEstimate as SharedRecordingDistanceEstimate
import com.glancemap.trailcore.recording.RecordingDistanceInput as SharedRecordingDistanceInput
import com.glancemap.trailcore.recording.RecordingWatchGpsDistanceGeometry as SharedRecordingWatchGpsDistanceGeometry
import com.glancemap.trailcore.recording.RecordingWatchGpsDistanceSegment as SharedRecordingWatchGpsDistanceSegment
import com.glancemap.trailcore.recording.buildRecordingDistanceComparison as sharedBuildRecordingDistanceComparison
import com.glancemap.trailcore.recording.estimateRecordingDistanceDelta as sharedEstimateRecordingDistanceDelta
import com.glancemap.trailcore.recording.resolveRecordingContinuityRecoveryGapMillis as sharedResolveRecoveryGap
import com.glancemap.trailcore.recording.shouldStartRecordingGpsGapSegment as sharedShouldStartRecordingGpsGapSegment

internal typealias RecordingDistanceComparison = SharedRecordingDistanceComparison
internal typealias RecordingDistanceDiagnostics = SharedRecordingDistanceDiagnostics
internal typealias RecordingDistanceEstimate = SharedRecordingDistanceEstimate
internal typealias RecordingDistanceInput = SharedRecordingDistanceInput
internal typealias RecordingWatchGpsDistanceGeometry = SharedRecordingWatchGpsDistanceGeometry
internal typealias RecordingWatchGpsDistanceSegment = SharedRecordingWatchGpsDistanceSegment

internal const val RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_FULL_SESSION = SHARED_SCOPE_FULL
internal const val RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_POST_RECOVERY_PARTIAL = PARTIAL_SCOPE

internal fun resolveRecordingContinuityRecoveryGapMillis(
    deliveryGapMillis: Long,
    committedPointGapMillis: Long,
    thresholdMillis: Long,
): Long? = sharedResolveRecoveryGap(deliveryGapMillis, committedPointGapMillis, thresholdMillis)

internal fun shouldStartRecordingGpsGapSegment(
    previous: RecordedTracePoint?,
    current: RecordedTracePoint,
    continuityGapMillis: Long?,
    activityProfile: String,
): Boolean = sharedShouldStartRecordingGpsGapSegment(previous, current, continuityGapMillis, activityProfile)

internal fun buildRecordingDistanceComparison(
    activityDistanceMeters: Double,
    diagnostics: RecordingDistanceDiagnostics,
    canonicalPoints: List<RecordedTracePoint>,
): RecordingDistanceComparison =
    sharedBuildRecordingDistanceComparison(
        activityDistanceMeters,
        diagnostics,
        canonicalPoints,
    )

internal fun estimateRecordingDistanceDelta(input: RecordingDistanceInput): RecordingDistanceEstimate =
    sharedEstimateRecordingDistanceDelta(
        input,
    )
