package com.glancemap.glancemapwearos.presentation.features.recording

internal data class RecordingSmartTrackTelemetrySnapshot(
    val motionEvaluatedFixCount: Int,
    val acceptedReportedSpeedCount: Int,
    val acceptedSensorCount: Int,
    val acceptedConfirmedSlowCount: Int,
    val acceptedDelayedDeliveryRecoveryCount: Int,
    val suppressedStationaryCount: Int,
    val suppressedStepStillnessCount: Int,
    val heldSlowCount: Int,
    val segmentStartBypassCount: Int,
    val stepMotionEvidenceCount: Int,
    val cadenceMotionEvidenceCount: Int,
    val speedAboveThresholdCount: Int,
    val credibleSpeedCount: Int,
    val noMotionSensorDataCount: Int,
    val stationaryRadiusSampleCount: Int,
    val stationaryRadiusAverageMeters: Double?,
    val stationaryRadiusMaxMeters: Double?,
    val nonAcceptedDisplacementSampleCount: Int,
    val nonAcceptedDisplacementAverageMeters: Double?,
    val nonAcceptedDisplacementMaxMeters: Double?,
    val poorAccuracyRejectedCount: Int,
    val nonMonotonicRejectedCount: Int,
    val implausibleJumpHeldCount: Int,
    val confirmedSustainedMovementCount: Int,
    val adaptiveAccuracyFixCount: Int,
    val accuracyPolicy: RecordingAccuracyPolicySnapshot?,
)

/**
 * In-memory aggregate telemetry for tuning SmartTrack. It deliberately performs no logging
 * per fix; the snapshot is appended to the existing recording summary events.
 */
internal class RecordingSmartTrackTelemetry {
    private var motionEvaluatedFixCount = 0
    private var acceptedReportedSpeedCount = 0
    private var acceptedSensorCount = 0
    private var acceptedConfirmedSlowCount = 0
    private var acceptedDelayedDeliveryRecoveryCount = 0
    private var suppressedStationaryCount = 0
    private var suppressedStepStillnessCount = 0
    private var heldSlowCount = 0
    private var segmentStartBypassCount = 0
    private var stepMotionEvidenceCount = 0
    private var cadenceMotionEvidenceCount = 0
    private var speedAboveThresholdCount = 0
    private var credibleSpeedCount = 0
    private var noMotionSensorDataCount = 0
    private var stationaryRadiusSampleCount = 0
    private var stationaryRadiusSumMeters = 0.0
    private var stationaryRadiusMaxMeters = 0.0
    private var nonAcceptedDisplacementSampleCount = 0
    private var nonAcceptedDisplacementSumMeters = 0.0
    private var nonAcceptedDisplacementMaxMeters = 0.0
    private var poorAccuracyRejectedCount = 0
    private var nonMonotonicRejectedCount = 0
    private var implausibleJumpHeldCount = 0
    private var confirmedSustainedMovementCount = 0
    private var adaptiveAccuracyFixCount = 0
    private var accuracyPolicy: RecordingAccuracyPolicySnapshot? = null

    fun reset() {
        motionEvaluatedFixCount = 0
        acceptedReportedSpeedCount = 0
        acceptedSensorCount = 0
        acceptedConfirmedSlowCount = 0
        acceptedDelayedDeliveryRecoveryCount = 0
        suppressedStationaryCount = 0
        suppressedStepStillnessCount = 0
        heldSlowCount = 0
        segmentStartBypassCount = 0
        stepMotionEvidenceCount = 0
        cadenceMotionEvidenceCount = 0
        speedAboveThresholdCount = 0
        credibleSpeedCount = 0
        noMotionSensorDataCount = 0
        stationaryRadiusSampleCount = 0
        stationaryRadiusSumMeters = 0.0
        stationaryRadiusMaxMeters = 0.0
        nonAcceptedDisplacementSampleCount = 0
        nonAcceptedDisplacementSumMeters = 0.0
        nonAcceptedDisplacementMaxMeters = 0.0
        poorAccuracyRejectedCount = 0
        nonMonotonicRejectedCount = 0
        implausibleJumpHeldCount = 0
        confirmedSustainedMovementCount = 0
        adaptiveAccuracyFixCount = 0
        accuracyPolicy = null
    }

    fun observeMotion(
        result: RecordingMotionResult,
        bypassedForSegmentStart: Boolean,
    ) {
        motionEvaluatedFixCount += 1
        observeEvidence(result.evidence)

        if (bypassedForSegmentStart && !result.accepted) {
            segmentStartBypassCount += 1
            return
        }
        observeMotionReason(result.reason)
        if (!result.accepted) observeNonAcceptedDisplacement(result.displacementMeters)
    }

    private fun observeEvidence(evidence: RecordingMotionEvidence) {
        if (evidence.stepsAdvanced) stepMotionEvidenceCount += 1
        if (evidence.cadenceShowsMotion) cadenceMotionEvidenceCount += 1
        if (evidence.speedAboveThreshold) speedAboveThresholdCount += 1
        if (evidence.reportedSpeedCredible) credibleSpeedCount += 1
        if (!evidence.stepDataAvailable && !evidence.cadenceDataAvailable) {
            noMotionSensorDataCount += 1
        }
        evidence.stationaryRadiusMeters?.let { radius ->
            stationaryRadiusSampleCount += 1
            stationaryRadiusSumMeters += radius
            stationaryRadiusMaxMeters = maxOf(stationaryRadiusMaxMeters, radius)
        }
    }

    private fun observeMotionReason(reason: RecordingMotionReason) {
        when (reason) {
            RecordingMotionReason.REPORTED_MOTION -> acceptedReportedSpeedCount += 1
            RecordingMotionReason.SENSOR_MOTION -> acceptedSensorCount += 1
            RecordingMotionReason.CONFIRMED_SLOW_PROGRESS -> acceptedConfirmedSlowCount += 1
            RecordingMotionReason.DELAYED_DELIVERY_RECOVERY -> acceptedDelayedDeliveryRecoveryCount += 1
            RecordingMotionReason.STATIONARY_JITTER -> suppressedStationaryCount += 1
            RecordingMotionReason.STEP_STILLNESS -> suppressedStepStillnessCount += 1
            RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS -> heldSlowCount += 1
            RecordingMotionReason.FIRST_POINT -> Unit
        }
    }

    private fun observeNonAcceptedDisplacement(displacementMeters: Double) {
        nonAcceptedDisplacementSampleCount += 1
        nonAcceptedDisplacementSumMeters += displacementMeters
        nonAcceptedDisplacementMaxMeters = maxOf(nonAcceptedDisplacementMaxMeters, displacementMeters)
    }

    fun observeQuality(
        result: RecordingFixQualityResult,
        policy: RecordingAccuracyPolicySnapshot?,
    ) {
        when (result.reason) {
            RecordingFixQualityReason.POOR_ACCURACY -> poorAccuracyRejectedCount += 1
            RecordingFixQualityReason.NON_MONOTONIC -> nonMonotonicRejectedCount += 1
            RecordingFixQualityReason.IMPLAUSIBLE_JUMP -> implausibleJumpHeldCount += 1
            RecordingFixQualityReason.CONFIRMED_SUSTAINED_MOVEMENT -> confirmedSustainedMovementCount += 1
            else -> Unit
        }
        if (result.reason != RecordingFixQualityReason.NON_MONOTONIC) {
            policy?.let {
                accuracyPolicy = it
                if (it.adaptiveLimitActive) adaptiveAccuracyFixCount += 1
            }
        }
    }

    fun snapshot(): RecordingSmartTrackTelemetrySnapshot =
        RecordingSmartTrackTelemetrySnapshot(
            motionEvaluatedFixCount = motionEvaluatedFixCount,
            acceptedReportedSpeedCount = acceptedReportedSpeedCount,
            acceptedSensorCount = acceptedSensorCount,
            acceptedConfirmedSlowCount = acceptedConfirmedSlowCount,
            acceptedDelayedDeliveryRecoveryCount = acceptedDelayedDeliveryRecoveryCount,
            suppressedStationaryCount = suppressedStationaryCount,
            suppressedStepStillnessCount = suppressedStepStillnessCount,
            heldSlowCount = heldSlowCount,
            segmentStartBypassCount = segmentStartBypassCount,
            stepMotionEvidenceCount = stepMotionEvidenceCount,
            cadenceMotionEvidenceCount = cadenceMotionEvidenceCount,
            speedAboveThresholdCount = speedAboveThresholdCount,
            credibleSpeedCount = credibleSpeedCount,
            noMotionSensorDataCount = noMotionSensorDataCount,
            stationaryRadiusSampleCount = stationaryRadiusSampleCount,
            stationaryRadiusAverageMeters = stationaryRadiusSumMeters.averageOrNull(stationaryRadiusSampleCount),
            stationaryRadiusMaxMeters = stationaryRadiusMaxMeters.takeIf { stationaryRadiusSampleCount > 0 },
            nonAcceptedDisplacementSampleCount = nonAcceptedDisplacementSampleCount,
            nonAcceptedDisplacementAverageMeters =
                nonAcceptedDisplacementSumMeters.averageOrNull(nonAcceptedDisplacementSampleCount),
            nonAcceptedDisplacementMaxMeters =
                nonAcceptedDisplacementMaxMeters.takeIf { nonAcceptedDisplacementSampleCount > 0 },
            poorAccuracyRejectedCount = poorAccuracyRejectedCount,
            nonMonotonicRejectedCount = nonMonotonicRejectedCount,
            implausibleJumpHeldCount = implausibleJumpHeldCount,
            confirmedSustainedMovementCount = confirmedSustainedMovementCount,
            adaptiveAccuracyFixCount = adaptiveAccuracyFixCount,
            accuracyPolicy = accuracyPolicy,
        )
}

private fun Double.averageOrNull(sampleCount: Int): Double? = takeIf { sampleCount > 0 }?.div(sampleCount.toDouble())
