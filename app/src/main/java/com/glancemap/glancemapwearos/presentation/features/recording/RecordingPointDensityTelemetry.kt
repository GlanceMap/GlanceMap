package com.glancemap.glancemapwearos.presentation.features.recording

import java.util.Locale

/**
 * Diagnostic-only accounting for the path from delivered locations to stored GPX points.
 *
 * The configured capture rate intentionally includes stationary time, so it is kept separate
 * from the movement-conditioned count below. Nothing in this class changes recording decisions.
 */
internal class RecordingPointDensityTelemetry {
    private var callbackReceivedCount = 0
    private var usableCallbackCount = 0
    private var smartTrackDecisionCount = 0
    private var storedPointCount = 0
    private var movingExpectedStoredSampleCount = 0
    private var movingStoredSampleCount = 0

    private var movingGapCount = 0
    private var movingGapMaxMs = 0L
    private var movingGapEndpointDistanceMaxM = 0.0
    private var stationaryGapCount = 0
    private var stationaryGapMaxMs = 0L
    private var slowMovementGapCount = 0
    private var slowMovementGapMaxMs = 0L
    private var unknownCallbackGapCount = 0
    private var unknownCallbackGapMaxMs = 0L

    private var lastCallbackElapsedMs = Long.MIN_VALUE
    private var maxCallbackGapSinceStoredPoint = 0L
    private var callbacksMissingSinceStoredPoint = false
    private var credibleMovementSinceStoredPoint = false
    private var slowMovementSinceStoredPoint = false
    private var stationarySinceStoredPoint = false
    private var latestMotionReason = "na"

    fun reset() {
        callbackReceivedCount = 0
        usableCallbackCount = 0
        smartTrackDecisionCount = 0
        storedPointCount = 0
        movingExpectedStoredSampleCount = 0
        movingStoredSampleCount = 0
        movingGapCount = 0
        movingGapMaxMs = 0L
        movingGapEndpointDistanceMaxM = 0.0
        stationaryGapCount = 0
        stationaryGapMaxMs = 0L
        slowMovementGapCount = 0
        slowMovementGapMaxMs = 0L
        unknownCallbackGapCount = 0
        unknownCallbackGapMaxMs = 0L
        lastCallbackElapsedMs = Long.MIN_VALUE
        maxCallbackGapSinceStoredPoint = 0L
        resetPendingGapEvidence()
    }

    fun observeCallbackReceived(
        callbackElapsedMs: Long,
        significantGapMs: Long,
    ): Long {
        callbackReceivedCount += 1
        val previousCallbackElapsedMs = lastCallbackElapsedMs
        val callbackGapMs =
            if (previousCallbackElapsedMs != Long.MIN_VALUE && callbackElapsedMs > previousCallbackElapsedMs) {
                callbackElapsedMs - previousCallbackElapsedMs
            } else {
                0L
            }
        if (
            callbackGapMs >= significantGapMs
        ) {
            callbacksMissingSinceStoredPoint = true
        }
        maxCallbackGapSinceStoredPoint = maxOf(maxCallbackGapSinceStoredPoint, callbackGapMs)
        lastCallbackElapsedMs = callbackElapsedMs
        return callbackGapMs
    }

    fun observeUsableCallback() {
        usableCallbackCount += 1
    }

    fun observeSmartTrackDecision(result: RecordingMotionResult) {
        smartTrackDecisionCount += 1
        latestMotionReason = result.reason.name.lowercase(Locale.ROOT)
        when (result.reason) {
            RecordingMotionReason.REPORTED_MOTION,
            RecordingMotionReason.SENSOR_MOTION,
            RecordingMotionReason.CONFIRMED_SLOW_PROGRESS,
            RecordingMotionReason.DELAYED_DELIVERY_RECOVERY,
            -> {
                credibleMovementSinceStoredPoint = true
                movingExpectedStoredSampleCount += 1
            }

            RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS -> slowMovementSinceStoredPoint = true

            RecordingMotionReason.STATIONARY_JITTER,
            RecordingMotionReason.STEP_STILLNESS,
            -> stationarySinceStoredPoint = true

            RecordingMotionReason.FIRST_POINT -> Unit
        }
    }

    fun observeStoredPoint(
        acceptedPointGapMs: Long,
        endpointDistanceMeters: Double?,
        significantGapMs: Long,
        storedAfterMotion: Boolean,
    ): RecordingPointDensityGap? {
        storedPointCount += 1
        if (storedAfterMotion) movingStoredSampleCount += 1
        if (acceptedPointGapMs < significantGapMs) {
            resetPendingGapEvidence()
            return null
        }

        val classification =
            when {
                callbacksMissingSinceStoredPoint -> RecordingPointDensityGapClass.UNKNOWN_CALLBACK_GAP
                credibleMovementSinceStoredPoint -> RecordingPointDensityGapClass.CREDIBLE_SUSTAINED_MOVEMENT
                slowMovementSinceStoredPoint -> RecordingPointDensityGapClass.SLOW_MOVEMENT
                stationarySinceStoredPoint -> RecordingPointDensityGapClass.STATIONARY
                else -> RecordingPointDensityGapClass.UNKNOWN_CALLBACK_GAP
            }
        when (classification) {
            RecordingPointDensityGapClass.CREDIBLE_SUSTAINED_MOVEMENT -> {
                movingGapCount += 1
                movingGapMaxMs = maxOf(movingGapMaxMs, acceptedPointGapMs)
                endpointDistanceMeters?.let { distance ->
                    movingGapEndpointDistanceMaxM = maxOf(movingGapEndpointDistanceMaxM, distance)
                }
            }

            RecordingPointDensityGapClass.STATIONARY -> {
                stationaryGapCount += 1
                stationaryGapMaxMs = maxOf(stationaryGapMaxMs, acceptedPointGapMs)
            }

            RecordingPointDensityGapClass.SLOW_MOVEMENT -> {
                slowMovementGapCount += 1
                slowMovementGapMaxMs = maxOf(slowMovementGapMaxMs, acceptedPointGapMs)
            }

            RecordingPointDensityGapClass.UNKNOWN_CALLBACK_GAP -> {
                unknownCallbackGapCount += 1
                unknownCallbackGapMaxMs = maxOf(unknownCallbackGapMaxMs, acceptedPointGapMs)
            }
        }
        return RecordingPointDensityGap(
            classification = classification,
            acceptedPointGapMs = acceptedPointGapMs,
            latestSmartTrackReason = latestMotionReason,
            credibleMovementExisted = credibleMovementSinceStoredPoint,
            callbacksWereMissing = callbacksMissingSinceStoredPoint,
            maxLiveCallbackGapMs = maxCallbackGapSinceStoredPoint,
        ).also { resetPendingGapEvidence() }
    }

    fun snapshot(): RecordingPointDensityTelemetrySnapshot =
        RecordingPointDensityTelemetrySnapshot(
            callbackReceivedCount = callbackReceivedCount,
            usableCallbackCount = usableCallbackCount,
            smartTrackDecisionCount = smartTrackDecisionCount,
            storedPointCount = storedPointCount,
            movingExpectedStoredSampleCount = movingExpectedStoredSampleCount,
            movingStoredSampleCount = movingStoredSampleCount,
            movingStoredSampleCaptureRatePercent =
                if (movingExpectedStoredSampleCount > 0) {
                    (movingStoredSampleCount * 100 / movingExpectedStoredSampleCount).coerceAtMost(100)
                } else {
                    null
                },
            movingGapCount = movingGapCount,
            movingGapMaxMs = movingGapMaxMs,
            movingGapEndpointDistanceMaxM = movingGapEndpointDistanceMaxM.takeIf { movingGapCount > 0 },
            stationaryGapCount = stationaryGapCount,
            stationaryGapMaxMs = stationaryGapMaxMs,
            slowMovementGapCount = slowMovementGapCount,
            slowMovementGapMaxMs = slowMovementGapMaxMs,
            unknownCallbackGapCount = unknownCallbackGapCount,
            unknownCallbackGapMaxMs = unknownCallbackGapMaxMs,
        )

    private fun resetPendingGapEvidence() {
        callbacksMissingSinceStoredPoint = false
        maxCallbackGapSinceStoredPoint = 0L
        credibleMovementSinceStoredPoint = false
        slowMovementSinceStoredPoint = false
        stationarySinceStoredPoint = false
        latestMotionReason = "na"
    }
}

internal data class RecordingPointDensityTelemetrySnapshot(
    val callbackReceivedCount: Int,
    val usableCallbackCount: Int,
    val smartTrackDecisionCount: Int,
    val storedPointCount: Int,
    val movingExpectedStoredSampleCount: Int,
    val movingStoredSampleCount: Int,
    val movingStoredSampleCaptureRatePercent: Int?,
    val movingGapCount: Int,
    val movingGapMaxMs: Long,
    val movingGapEndpointDistanceMaxM: Double?,
    val stationaryGapCount: Int,
    val stationaryGapMaxMs: Long,
    val slowMovementGapCount: Int,
    val slowMovementGapMaxMs: Long,
    val unknownCallbackGapCount: Int,
    val unknownCallbackGapMaxMs: Long,
)

internal enum class RecordingPointDensityGapClass(
    val telemetryValue: String,
) {
    STATIONARY("stationary"),
    SLOW_MOVEMENT("slow_movement"),
    CREDIBLE_SUSTAINED_MOVEMENT("credible_sustained_movement"),
    UNKNOWN_CALLBACK_GAP("unknown_callback_gap"),
}

internal data class RecordingPointDensityGap(
    val classification: RecordingPointDensityGapClass,
    val acceptedPointGapMs: Long,
    val latestSmartTrackReason: String,
    val credibleMovementExisted: Boolean,
    val callbacksWereMissing: Boolean,
    val maxLiveCallbackGapMs: Long,
)
