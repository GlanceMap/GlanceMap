package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_EFFECTIVE_ACCURACY_M
import com.glancemap.trailcore.geo.GeoPoint
import org.mapsforge.core.model.LatLong
import com.glancemap.glancemapwearos.core.service.location.config.isKnownWatchGpsAccuracyFloor as watchAccuracyFloor
import com.glancemap.trailcore.recording.RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR as FIX_ACCURACY_ALLOWANCE_FACTOR
import com.glancemap.trailcore.recording.RECORDING_FIX_BASE_ALLOWANCE_M as SHARED_RECORDING_FIX_BASE_ALLOWANCE_M
import com.glancemap.trailcore.recording.RECORDING_FIX_FALLBACK_ACCURACY_M as SHARED_RECORDING_FIX_FALLBACK_ACCURACY_M
import com.glancemap.trailcore.recording.RECORDING_FIX_MAX_RELATIVE_SPEED_ACCURACY as MAX_RELATIVE_SPEED_ACCURACY
import com.glancemap.trailcore.recording.RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR as MAX_REPORTED_SPEED_FACTOR
import com.glancemap.trailcore.recording.RECORDING_FIX_MAX_SPEED_UNCERTAINTY_ALLOWANCE_MPS as MAX_SPEED_UNCERTAINTY_MPS
import com.glancemap.trailcore.recording.RECORDING_FIX_MAX_TRUSTED_SPEED_ACCURACY_MPS as MAX_TRUSTED_SPEED_ACCURACY_MPS
import com.glancemap.trailcore.recording.RECORDING_TRACK_FILTER_VERSION as SHARED_RECORDING_TRACK_FILTER_VERSION
import com.glancemap.trailcore.recording.RecordingAccuracyPolicySnapshot as SharedRecordingAccuracyPolicySnapshot
import com.glancemap.trailcore.recording.RecordingAccuracyProvenance as SharedRecordingAccuracyProvenance
import com.glancemap.trailcore.recording.RecordingFixQualityGate as SharedRecordingFixQualityGate
import com.glancemap.trailcore.recording.RecordingFixQualityReason as SharedRecordingFixQualityReason
import com.glancemap.trailcore.recording.RecordingFixQualityResult as SharedRecordingFixQualityResult
import com.glancemap.trailcore.recording.RecordingFixQualityStatus as SharedRecordingFixQualityStatus
import com.glancemap.trailcore.recording.RecordingFixSample as SharedRecordingFixSample
import com.glancemap.trailcore.recording.RecordingFixSample as SharedRecordingFixSampleConstructor
import com.glancemap.trailcore.recording.recordingAccuracyProvenance as sharedAccuracyProvenance
import com.glancemap.trailcore.recording.recordingFixProfileAccuracyLimitMeters as limit
import com.glancemap.trailcore.recording.recordingTransitionMaximumSpeedMps as sharedRecordingTransitionMaximumSpeedMps
import com.glancemap.trailcore.recording.recordingTransitionUncertaintyAllowance as sharedTransitionUncertaintyAllowance

internal const val RECORDING_TRACK_FILTER_VERSION = SHARED_RECORDING_TRACK_FILTER_VERSION
internal const val RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M = WATCH_GPS_EFFECTIVE_ACCURACY_M
internal const val RECORDING_FIX_FALLBACK_ACCURACY_M = SHARED_RECORDING_FIX_FALLBACK_ACCURACY_M
internal const val RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR = FIX_ACCURACY_ALLOWANCE_FACTOR
internal const val RECORDING_FIX_BASE_ALLOWANCE_M = SHARED_RECORDING_FIX_BASE_ALLOWANCE_M
internal const val RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR = MAX_REPORTED_SPEED_FACTOR
internal const val RECORDING_FIX_MAX_TRUSTED_SPEED_ACCURACY_MPS = MAX_TRUSTED_SPEED_ACCURACY_MPS
internal const val RECORDING_FIX_MAX_RELATIVE_SPEED_ACCURACY = MAX_RELATIVE_SPEED_ACCURACY
internal const val RECORDING_FIX_MAX_SPEED_UNCERTAINTY_ALLOWANCE_MPS =
    MAX_SPEED_UNCERTAINTY_MPS

internal typealias RecordingAccuracyProvenance = SharedRecordingAccuracyProvenance
internal typealias RecordingFixSample = SharedRecordingFixSample
internal typealias RecordingFixQualityStatus = SharedRecordingFixQualityStatus
internal typealias RecordingFixQualityReason = SharedRecordingFixQualityReason
internal typealias RecordingFixQualityResult = SharedRecordingFixQualityResult
internal typealias RecordingAccuracyPolicySnapshot = SharedRecordingAccuracyPolicySnapshot
internal typealias RecordingFixQualityGate = SharedRecordingFixQualityGate

@Suppress("LongParameterList")
fun RecordingFixSample(
    latLong: LatLong,
    timeMillis: Long,
    elapsedRealtimeMillis: Long,
    accuracyMeters: Float?,
    speedMps: Float?,
    speedAccuracyMps: Float?,
): RecordingFixSample =
    SharedRecordingFixSampleConstructor(
        location = GeoPoint(latLong.latitude, latLong.longitude),
        timeMillis = timeMillis,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        speedAccuracyMps = speedAccuracyMps,
    )

/** A correction is enabled only after the watch adapter confirms the active qualified source. */
internal fun resolveRecordingFilterAccuracyMeters(
    rawAccuracyMeters: Float?,
    knownWatchGpsAccuracyFloorActive: Boolean,
): Float? = sharedAccuracyProvenance(rawAccuracyMeters, knownWatchGpsAccuracyFloorActive).effectiveAccuracyMeters

internal fun resolveRecordingAccuracyProvenance(
    rawAccuracyMeters: Float?,
    knownWatchGpsAccuracyFloorActive: Boolean,
): RecordingAccuracyProvenance = sharedAccuracyProvenance(rawAccuracyMeters, knownWatchGpsAccuracyFloorActive)

internal fun isKnownWatchGpsAccuracyFloor(accuracyMeters: Float?) = watchAccuracyFloor(accuracyMeters)

internal fun recordingTransitionMaximumSpeedMps(
    candidate: RecordingFixSample,
    activityProfile: String,
): Double = sharedRecordingTransitionMaximumSpeedMps(candidate, activityProfile)

internal fun recordingTransitionUncertaintyAllowance(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
): Double = sharedTransitionUncertaintyAllowance(previous, candidate)

internal fun recordingFixProfileAccuracyLimitMeters(activityProfile: String): Float = limit(activityProfile)
