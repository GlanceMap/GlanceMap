package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.AcceptedFixSummaries
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.AcceptedFixSummary
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.CompassTelemetryInsights
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.FixGapBuckets
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.GnssInsights
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.ObservedFixQualitySummary
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.RecordingPointDensityInsights
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.RecordingSmartTrackInsights
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.RecordingTrackFilterInsights
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.TelemetryInsights
import java.io.BufferedWriter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val telemetryLineTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

private enum class LocationRequestMode {
    BURST,
    STATIONARY_BOUND,
    STATIONARY_BACKGROUND,
    OTHERWISE,
}

private data class ModeSample(
    val atEpochMs: Long,
    val mode: LocationRequestMode,
)

private enum class RequestBackendMode {
    AUTO_FUSED,
    PASSIVE_EXTERNAL,
    WATCH_GPS,
}

private data class BackendSample(
    val atEpochMs: Long,
    val backend: RequestBackendMode,
)

internal data class TelemetryWindow(
    val lines: List<String>,
    val firstAtMs: Long?,
    val lastAtMs: Long?,
)

private data class ModeDurations(
    val burstMs: Long,
    val stationaryBoundMs: Long,
    val stationaryBackgroundMs: Long,
    val otherwiseMs: Long,
    val coverageMs: Long,
)

private data class BackendDurations(
    val autoFusedMs: Long,
    val passiveExternalMs: Long,
    val watchGpsMs: Long,
    val coverageMs: Long,
    val switchCount: Int,
)

private class RecordingSmartTrackInsightsAccumulator {
    private val counts = mutableMapOf<String, Int>()
    private val values = mutableMapOf<String, String>()
    private var adaptiveAccuracyLimitActive: Boolean? = null

    fun observe(line: String) {
        if ("smartTrack" !in line) return
        SMART_TRACK_COUNT_TOKENS.forEach { token ->
            parseIntToken(line, "$token=")
                ?.takeIf { it >= 0 }
                ?.let { value -> counts[token] = maxOf(counts[token] ?: value, value) }
        }
        SMART_TRACK_VALUE_TOKENS.forEach { token ->
            extractTokenValue(line, "$token=")
                ?.takeUnless { it.isBlank() || it == "na" }
                ?.let { values[token] = it }
        }
        parseBooleanToken(line, "smartTrackAdaptiveAccuracyLimitActive=")?.let {
            adaptiveAccuracyLimitActive = it
        }
    }

    fun snapshot(): RecordingSmartTrackInsights =
        RecordingSmartTrackInsights(
            motionEvaluatedFixCount = count("smartTrackMotionEvaluatedFixCount"),
            acceptedReportedSpeedCount = count("smartTrackAcceptedReportedSpeedCount"),
            acceptedSensorCount = count("smartTrackAcceptedSensorCount"),
            acceptedConfirmedSlowCount = count("smartTrackAcceptedConfirmedSlowCount"),
            suppressedStationaryCount = count("smartTrackSuppressedStationaryCount"),
            suppressedStepStillnessCount = count("smartTrackSuppressedStepStillnessCount"),
            heldSlowCount = count("smartTrackHeldSlowCount"),
            segmentStartBypassCount = count("smartTrackSegmentStartBypassCount"),
            stepMotionEvidenceCount = count("smartTrackStepMotionEvidenceCount"),
            cadenceMotionEvidenceCount = count("smartTrackCadenceMotionEvidenceCount"),
            speedAboveThresholdCount = count("smartTrackSpeedAboveThresholdCount"),
            credibleSpeedCount = count("smartTrackCredibleSpeedCount"),
            noMotionSensorDataCount = count("smartTrackNoMotionSensorDataCount"),
            stationaryRadiusSampleCount = count("smartTrackStationaryRadiusSampleCount"),
            stationaryRadiusAvgMeters = value("smartTrackStationaryRadiusAvgMeters"),
            stationaryRadiusMaxMeters = value("smartTrackStationaryRadiusMaxMeters"),
            nonAcceptedDisplacementSampleCount = count("smartTrackNonAcceptedDisplacementSampleCount"),
            nonAcceptedDisplacementAvgMeters = value("smartTrackNonAcceptedDisplacementAvgMeters"),
            nonAcceptedDisplacementMaxMeters = value("smartTrackNonAcceptedDisplacementMaxMeters"),
            poorAccuracyRejectedCount = count("smartTrackPoorAccuracyRejectedCount"),
            nonMonotonicRejectedCount = count("smartTrackNonMonotonicRejectedCount"),
            implausibleJumpHeldCount = count("smartTrackImplausibleJumpHeldCount"),
            confirmedSustainedMovementCount = count("smartTrackConfirmedSustainedMovementCount"),
            adaptiveAccuracyFixCount = count("smartTrackAdaptiveAccuracyFixCount"),
            accuracyBaselineSampleCount = count("smartTrackAccuracyBaselineSampleCount"),
            accuracyBaselineMedianMeters = value("smartTrackAccuracyBaselineMedianMeters"),
            accuracyProfileLimitMeters = value("smartTrackAccuracyProfileLimitMeters"),
            accuracyResolvedLimitMeters = value("smartTrackAccuracyResolvedLimitMeters"),
            adaptiveAccuracyLimitActive = adaptiveAccuracyLimitActive,
        )

    private fun count(token: String): Int? = counts[token]

    private fun value(token: String): String? = values[token]

    private companion object {
        val SMART_TRACK_COUNT_TOKENS =
            listOf(
                "smartTrackMotionEvaluatedFixCount",
                "smartTrackAcceptedReportedSpeedCount",
                "smartTrackAcceptedSensorCount",
                "smartTrackAcceptedConfirmedSlowCount",
                "smartTrackSuppressedStationaryCount",
                "smartTrackSuppressedStepStillnessCount",
                "smartTrackHeldSlowCount",
                "smartTrackSegmentStartBypassCount",
                "smartTrackStepMotionEvidenceCount",
                "smartTrackCadenceMotionEvidenceCount",
                "smartTrackSpeedAboveThresholdCount",
                "smartTrackCredibleSpeedCount",
                "smartTrackNoMotionSensorDataCount",
                "smartTrackStationaryRadiusSampleCount",
                "smartTrackNonAcceptedDisplacementSampleCount",
                "smartTrackPoorAccuracyRejectedCount",
                "smartTrackNonMonotonicRejectedCount",
                "smartTrackImplausibleJumpHeldCount",
                "smartTrackConfirmedSustainedMovementCount",
                "smartTrackAdaptiveAccuracyFixCount",
                "smartTrackAccuracyBaselineSampleCount",
            )
        val SMART_TRACK_VALUE_TOKENS =
            listOf(
                "smartTrackStationaryRadiusAvgMeters",
                "smartTrackStationaryRadiusMaxMeters",
                "smartTrackNonAcceptedDisplacementAvgMeters",
                "smartTrackNonAcceptedDisplacementMaxMeters",
                "smartTrackAccuracyBaselineMedianMeters",
                "smartTrackAccuracyProfileLimitMeters",
                "smartTrackAccuracyResolvedLimitMeters",
            )
    }
}

private class RecordingPointDensityInsightsAccumulator {
    private val counts = mutableMapOf<String, Int>()
    private val durations = mutableMapOf<String, Long>()
    private var movingGapEndpointDistanceMaxM: String? = null

    fun observe(line: String) {
        if (
            "locationCallbackReceivedCount=" !in line &&
            "movingGapCount=" !in line &&
            "point_density_gap" !in line
        ) {
            return
        }
        COUNT_TOKENS.forEach { token ->
            parseIntToken(line, "$token=")
                ?.takeIf { it >= 0 }
                ?.let { value -> counts[token] = maxOf(counts[token] ?: value, value) }
        }
        DURATION_TOKENS.forEach { token ->
            parseLongToken(line, "$token=")
                ?.takeIf { it >= 0L }
                ?.let { value -> durations[token] = maxOf(durations[token] ?: value, value) }
        }
        extractTokenValue(line, "movingGapEndpointDistanceMaxM=")
            ?.takeUnless { it.isBlank() || it == "na" }
            ?.let { movingGapEndpointDistanceMaxM = it }
    }

    fun snapshot(): RecordingPointDensityInsights =
        RecordingPointDensityInsights(
            callbackReceivedCount = count("locationCallbackReceivedCount"),
            usableCallbackCount = count("usableLocationCallbackCount"),
            smartTrackDecisionCount = count("smartTrackDecisionCount"),
            storedPointCount = count("storedPointCount"),
            movingExpectedStoredSampleCount = count("movingExpectedStoredSampleCount"),
            movingStoredSampleCount = count("movingStoredSampleCount"),
            movingStoredSampleCaptureRatePercent = count("movingStoredSampleCaptureRatePercent"),
            movingGapCount = count("movingGapCount"),
            movingGapMaxMs = duration("movingGapMaxMs"),
            movingGapEndpointDistanceMaxM = movingGapEndpointDistanceMaxM,
            stationaryGapCount = count("stationaryGapCount"),
            stationaryGapMaxMs = duration("stationaryGapMaxMs"),
            slowMovementGapCount = count("slowMovementGapCount"),
            slowMovementGapMaxMs = duration("slowMovementGapMaxMs"),
            unknownCallbackGapCount = count("unknownCallbackGapCount"),
            unknownCallbackGapMaxMs = duration("unknownCallbackGapMaxMs"),
        )

    private fun count(token: String): Int? = counts[token]

    private fun duration(token: String): Long? = durations[token]

    private companion object {
        val COUNT_TOKENS =
            listOf(
                "locationCallbackReceivedCount",
                "usableLocationCallbackCount",
                "smartTrackDecisionCount",
                "storedPointCount",
                "movingExpectedStoredSampleCount",
                "movingStoredSampleCount",
                "movingStoredSampleCaptureRatePercent",
                "movingGapCount",
                "stationaryGapCount",
                "slowMovementGapCount",
                "unknownCallbackGapCount",
            )
        val DURATION_TOKENS =
            listOf(
                "movingGapMaxMs",
                "stationaryGapMaxMs",
                "slowMovementGapMaxMs",
                "unknownCallbackGapMaxMs",
            )
    }
}

internal fun deriveTelemetryInsights(
    lines: List<String>,
    captureWindowEndEpochMs: Long?,
): TelemetryInsights {
    if (lines.isEmpty()) return TelemetryInsights()

    var burstStartCount = 0
    var burstEndCount = 0
    var availabilityTrueCount = 0
    var availabilityFalseCount = 0
    var availabilityInferredFromFixCount = 0
    var screenResumeCount = 0
    var screenPauseCount = 0
    var ambientEnterCount = 0
    var ambientExitCount = 0
    var trackingEnabledTrueCount = 0
    var trackingEnabledFalseCount = 0
    var trackingDisabledByScreenPauseCount = 0
    var requestAppliedCount = 0
    var requestModeBurstCount = 0
    var requestModeStationaryBoundCount = 0
    var requestModeStationaryBackgroundCount = 0
    var requestModeOtherwiseCount = 0
    var lastObservedBound: Boolean? = null
    var lastObservedTrackingEnabled: Boolean? = null
    var lastObservedKeepOpen: Boolean? = null
    var startupBogusSampleIgnoredCount = 0
    var staleFixDropCount = 0
    var sourceMismatchDropCount = 0
    var immediateRequestGuardSkipCount = 0
    var immediateRequestDeferredWakeBurstCount = 0
    var gpsFreshTrueCount = 0
    var gpsFreshFalseCount = 0
    var passiveExternalSignalSampleCount = 0
    var passiveExternalFreshSampleCount = 0
    var passiveExternalStaleSampleCount = 0
    var passiveExternalAcceptedSampleCount = 0
    var passiveExternalRejectedSampleCount = 0
    var passiveExternalLastAgeMs: Long? = null
    var passiveExternalMinAgeMs: Long? = null
    var passiveExternalMaxAgeMs: Long? = null
    var passiveExternalLastMaxAgeMs: Long? = null
    var passiveExternalLastAccuracyM: Float? = null
    var passiveExternalLastProvider: String? = null
    var watchGpsDegradedEnteredCount = 0
    var watchGpsDegradedClearedCount = 0
    var watchGpsDegradedSampleCount = 0
    var watchGpsDegradedLastObserved: Boolean? = null
    var watchGpsSelfHealSkippedCount = 0
    var watchGpsSelfHealRestartCount = 0
    var watchGpsSelfHealMaxSearchAgeMs: Long? = null
    var batchEventCount = 0
    var batchOriginAutoFusedCount = 0
    var batchOriginPassiveExternalCount = 0
    var batchOriginWatchGpsCount = 0
    var batchFallbackCount = 0
    var batchDuplicateCandidatesDroppedTotal = 0
    var batchRawCandidatesTotal = 0
    var batchNormalizedCandidatesTotal = 0
    var batchAcceptedCandidatesTotal = 0
    var batchRawCandidatesMax = 0
    var batchNormalizedCandidatesMax = 0
    var callbackAcceptedFixCount = 0
    var immediateAcceptedFixCount = 0
    var acceptedFixOriginAutoFusedCount = 0
    var acceptedFixOriginPassiveExternalCount = 0
    var acceptedFixOriginWatchGpsCount = 0
    var requestBackendAutoFusedCount = 0
    var requestBackendPassiveExternalCount = 0
    var requestBackendWatchGpsCount = 0
    var failoverAutoToWatchAccuracyCount = 0
    var failoverAutoToWatchNoFixCount = 0
    var failoverWatchToAutoCount = 0
    var failoverClearedTrackingDisabledCount = 0
    var failoverClearedOtherCount = 0
    var fixProviderGpsCount = 0
    var fixProviderFusedCount = 0
    var screenOnFixGapSampleCount = 0
    var screenOnFixGapSumMs = 0L
    var screenOnFixGapMaxMs = 0L
    var interactiveFixGapSampleCount = 0
    var interactiveFixGapSumMs = 0L
    var interactiveFixGapMaxMs = 0L
    var nonInteractiveFixGapSampleCount = 0
    var nonInteractiveFixGapSumMs = 0L
    var nonInteractiveFixGapMaxMs = 0L
    var unknownScreenFixGapSampleCount = 0
    var unknownScreenFixGapSumMs = 0L
    var unknownScreenFixGapMaxMs = 0L
    var delayedFixGapCount = 0
    var turnByTurnSampleCount = 0
    var turnByTurnActiveSampleCount = 0
    var turnByTurnPausedSampleCount = 0
    var turnByTurnOffRouteSampleCount = 0
    var turnByTurnGuideBackActiveSampleCount = 0
    var turnByTurnGuideBackPromptSampleCount = 0
    var turnByTurnStartHereDecisionCount = 0
    var turnByTurnReverseDecisionCount = 0
    var turnByTurnGeometryInstructionSampleCount = 0
    var turnByTurnBrouterHintInstructionSampleCount = 0
    var turnByTurnTurnHapticCount = 0
    var turnByTurnOffRouteHapticCount = 0
    var turnByTurnTurnAlertFiredCount = 0
    var turnByTurnTurnAlertFilteredCount = 0
    var turnByTurnTurnAlertOffRouteCount = 0
    var turnByTurnTurnAlertMissedWindowCount = 0
    var turnByTurnMaxDistanceToRouteMeters: Int? = null
    var recordingStartCount = 0
    var recordingRecoveredCount = 0
    var recordingPauseCount = 0
    var recordingResumeCount = 0
    var recordingPointSampleCount = 0
    var recordingSaveStartCount = 0
    var recordingSaveSuccessCount = 0
    var recordingSaveFailureCount = 0
    var recordingDiscardCount = 0
    var recordingDraftFailureCount = 0
    var recordingDraftClearFailureCount = 0
    var recordingLastUiAction: String? = null
    var recordingLastEndReason: String? = null
    var recordingLastPointCount: Int? = null
    var recordingMaxPointCount: Int? = null
    var recordingLastDistanceMeters: Int? = null
    var recordingMaxDistanceMeters: Int? = null
    var recordingGpsDistanceMeters: Int? = null
    var recordingDisplayDistanceMeters: Int? = null
    var recordingPodSessionDistanceMeters: Int? = null
    var recordingPodIntegratedDistanceMeters: Int? = null
    var recordingSavedGpxWrittenPoints: Int? = null
    var recordingSavedGpxParsedPoints: Int? = null
    var recordingSavedGpxSummaryPoints: Int? = null
    var recordingSavedGpxSummaryDistanceMeters: Int? = null
    var recordingLastDurationMs: Long? = null
    var recordingMaxDurationMs: Long? = null
    var recordingLastPausedMs: Long? = null
    var recordingMaxPausedMs: Long? = null
    var recordingGpsActiveDurationMs: Long? = null
    var recordingExpectedPointCount: Int? = null
    var recordingAveragePointIntervalMs: Long? = null
    var recordingPointCaptureRatePercent: Int? = null
    var recordingGapCount: Int? = null
    var recordingGapEventCount = 0
    var recordingGapEndpointDistanceSampleCount = 0
    var recordingGapEndpointDistanceSumMeters = 0.0
    var recordingGapEndpointDistanceMaxMeters = 0f
    var recordingMaxGapMs: Long? = null
    var recordingLastPointAgeMs: Long? = null
    var recordingForcedAcceptCount: Int? = null
    var recordingGapRecoveryAcceptCount: Int? = null
    var recordingTrackSmoothingMode: String? = null
    var recordingTrackFilterVersion: Int? = null
    var recordingQualityHeldFixCount: Int? = null
    var recordingQualityRejectedFixCount: Int? = null
    var recordingQualityRelocationCount: Int? = null
    var recordingSmoothedPointCount: Int? = null
    var recordingSmoothedAdjustmentMeters: String? = null
    var recordingMaxSmoothedAdjustmentMeters: String? = null
    val recordingSmartTrack = RecordingSmartTrackInsightsAccumulator()
    val recordingPointDensity = RecordingPointDensityInsightsAccumulator()
    var recordingLastSkippedIntervalElapsedMs: Long? = null
    var recordingMaxSkippedIntervalElapsedMs: Long? = null
    var recordingLastLiveProvider: String? = null
    var recordingLastLiveAccuracyMeters: Int? = null
    var recordingSkippedIntervalCount: Int? = null
    var recordingSkippedPausedCount: Int? = null
    var recordingSkippedUnusableCount: Int? = null
    var recordingElevationSource: String? = null
    var recordingDemHitCount: Int? = null
    var recordingDemMissCount: Int? = null
    var recordingGpsElevationUsedCount: Int? = null
    var recordingAccuracySampleCount: Int? = null
    var recordingAccuracyAvgMeters: Int? = null
    var recordingAccuracyMinMeters: Int? = null
    var recordingAccuracyMaxMeters: Int? = null
    var recordingElevationGainMeters: Int? = null
    var recordingElevationLossMeters: Int? = null
    var recordingLiveHeartRateBpm: Int? = null
    var recordingAverageHeartRateBpm: Int? = null
    var recordingLiveStepCount: Int? = null
    var recordingLiveCadenceSpm: Int? = null
    var recordingLiveExternalSpeedMps: String? = null
    var recordingLiveExternalRawDistanceUnits: Long? = null
    var recordingLiveExternalDistanceMeters: Int? = null
    var recordingLiveExternalSessionDistanceMeters: Int? = null
    var recordingLiveExternalIntegratedDistanceMeters: Int? = null
    var recordingLivePressureHpa: Int? = null
    var recordingHeartRateAgeMs: Long? = null
    var recordingStepCountAgeMs: Long? = null
    var recordingCadenceAgeMs: Long? = null
    var recordingExternalSpeedAgeMs: Long? = null
    var recordingExternalDistanceAgeMs: Long? = null
    var recordingPressureAgeMs: Long? = null
    var recordingHeartRateSensorEventCount: Int? = null
    var recordingStepSensorEventCount: Int? = null
    var recordingCadenceSensorEventCount: Int? = null
    var recordingPressureSensorEventCount: Int? = null
    var recordingSensorRegisterCount = 0
    var recordingSensorRequested: String? = null
    var recordingSensorRegistered: String? = null
    var recordingSensorAvailable: String? = null
    var recordingBodySensorsGranted: Boolean? = null
    var recordingActivityRecognitionGranted: Boolean? = null
    var externalSensorScanStartCount = 0
    var externalSensorScanStopCount = 0
    var externalSensorScanFailedCount = 0
    var externalSensorLastScanDeviceCount: Int? = null
    var externalSensorLastScanSummary: String? = null
    var externalSensorLastScanFailureReason: String? = null
    var externalHeartRateBridgeStartCount = 0
    var externalHeartRateBridgeStopCount = 0
    var externalHeartRateConnectRequestedCount = 0
    var externalHeartRateConnectSkippedCount = 0
    var externalHeartRateLastConnectSkippedReason: String? = null
    var externalHeartRateConnectedCount = 0
    var externalHeartRateDisconnectedCount = 0
    var externalHeartRateReconnectScheduledCount = 0
    var externalHeartRateReconnectAttemptCount = 0
    var externalHeartRateNotifyRequestedCount = 0
    var externalHeartRateNotifyFailedCount = 0
    var externalHeartRateServiceFailureCount = 0
    var externalHeartRateMeasurementMissingCount = 0
    var externalHeartRateSampleCount = 0
    var externalHeartRateLastBpm: Int? = null
    var externalHeartRateMinBpm: Int? = null
    var externalHeartRateMaxBpm: Int? = null
    var externalHeartRateLastSampleAtMs: Long? = null
    var externalRunPodBridgeStartCount = 0
    var externalRunPodBridgeStopCount = 0
    var externalRunPodConnectRequestedCount = 0
    var externalRunPodConnectSkippedCount = 0
    var externalRunPodLastConnectSkippedReason: String? = null
    var externalRunPodConnectedCount = 0
    var externalRunPodDisconnectedCount = 0
    var externalRunPodNotifyRequestedCount = 0
    var externalRunPodNotifyFailedCount = 0
    var externalRunPodServiceFailureCount = 0
    var externalRunPodMeasurementMissingCount = 0
    var externalRunPodSampleCount = 0
    var externalRunPodLastCadenceSpm: Int? = null
    var externalRunPodLastPowerWatts: Int? = null
    var externalRunPodLastBatteryPercent: Int? = null
    var externalRunPodLastSpeedMps: String? = null
    var externalRunPodLastRawDistanceUnits: Long? = null
    var externalRunPodLastDistanceMeters: Int? = null
    var externalRunPodLastSampleAtMs: Long? = null
    var recordingCalorieModel: String? = null
    var recordingCaloriesGrossKcal: Int? = null
    var recordingCaloriesActiveKcal: Int? = null
    var recordingCaloriesRestingKcal: Int? = null
    var recordingPandolfBaseGrossKcal: Int? = null
    var recordingPandolfBaseActiveKcal: Int? = null
    var recordingPandolfBaseRestingKcal: Int? = null
    var recordingCalorieCompareModel: String? = null
    var recordingLcdaGrossKcal: Int? = null
    var recordingLcdaActiveKcal: Int? = null
    var recordingLcdaRestingKcal: Int? = null
    var recordingLastSavedByteSize: Int? = null
    var locationServiceStartFailureCount = 0
    var locationServiceStartFallbackFailureCount = 0
    var locationStartForegroundFailureCount = 0
    var thermalStatusEventCount = 0
    var thermalMaxStatus: Int? = null
    var thermalLastStatusLabel: String? = null
    var screenActive = false
    var pendingScreenPauseTrackingDisable = false
    var lastScreenFixAtMs: Long? = null
    val modeSamples = mutableListOf<ModeSample>()
    val backendSamples = mutableListOf<BackendSample>()
    val requestStopSamples = mutableListOf<Long>()

    lines.forEach { line ->
        recordingSmartTrack.observe(line)
        recordingPointDensity.observe(line)
        val lineEpochMs = parseTelemetryLineEpochMs(line)
        val requestMode = parseRequestMode(line)
        if (requestMode != null) {
            requestAppliedCount += 1
            when (requestMode) {
                LocationRequestMode.BURST -> requestModeBurstCount += 1
                LocationRequestMode.STATIONARY_BOUND -> requestModeStationaryBoundCount += 1
                LocationRequestMode.STATIONARY_BACKGROUND -> requestModeStationaryBackgroundCount += 1
                LocationRequestMode.OTHERWISE -> requestModeOtherwiseCount += 1
            }
            lastObservedBound = parseBooleanToken(line, "bound=") ?: lastObservedBound
            lastObservedTrackingEnabled = parseBooleanToken(line, "trackingEnabled=")
                ?: lastObservedTrackingEnabled
            lastObservedKeepOpen = parseBooleanToken(line, "keepOpen=") ?: lastObservedKeepOpen
            val backendMode = parseBackendMode(extractTokenValue(line, "backend="))
            when (backendMode) {
                RequestBackendMode.AUTO_FUSED -> requestBackendAutoFusedCount += 1
                RequestBackendMode.PASSIVE_EXTERNAL -> requestBackendPassiveExternalCount += 1
                RequestBackendMode.WATCH_GPS -> requestBackendWatchGpsCount += 1
                null -> Unit
            }

            lineEpochMs?.let { ts ->
                modeSamples += ModeSample(atEpochMs = ts, mode = requestMode)
                if (backendMode != null) {
                    backendSamples += BackendSample(atEpochMs = ts, backend = backendMode)
                }
            }
        }
        if (isRequestStopLine(line)) {
            lineEpochMs?.let { requestStopSamples += it }
            lastObservedBound = parseBooleanToken(line, "bound=") ?: lastObservedBound
            lastObservedTrackingEnabled =
                parseBooleanToken(line, "trackingEnabled=")
                    ?: parseLegacyTrackingEnabled(line)
                    ?: lastObservedTrackingEnabled
            lastObservedKeepOpen = parseBooleanToken(line, "keepOpen=") ?: lastObservedKeepOpen
        }

        if ("locationBatch:" in line) {
            batchEventCount += 1
            val rawCandidates = parseIntToken(line, "raw=") ?: 0
            val normalizedCandidates = parseIntToken(line, "normalized=") ?: 0
            val acceptedCandidates = parseIntToken(line, "accepted=") ?: 0
            val fallback = parseBooleanToken(line, "fallback=") ?: false
            val duplicateCandidatesDropped = parseIntToken(line, "duplicatesDropped=") ?: 0
            if (fallback) {
                batchFallbackCount += 1
            }
            when (extractTokenValue(line, "origin=")) {
                "auto_fused" -> batchOriginAutoFusedCount += 1
                "passive_external" -> batchOriginPassiveExternalCount += 1
                "watch_gps" -> batchOriginWatchGpsCount += 1
            }
            batchDuplicateCandidatesDroppedTotal += duplicateCandidatesDropped
            batchRawCandidatesTotal += rawCandidates
            batchNormalizedCandidatesTotal += normalizedCandidates
            batchAcceptedCandidatesTotal += acceptedCandidates
            batchRawCandidatesMax = maxOf(batchRawCandidatesMax, rawCandidates)
            batchNormalizedCandidatesMax = maxOf(batchNormalizedCandidatesMax, normalizedCandidates)
        }

        if ("fixAccepted: source=" in line) {
            availabilityInferredFromFixCount += 1
            when (extractTokenValue(line, "source=")) {
                "callback" -> callbackAcceptedFixCount += 1
                "immediate" -> immediateAcceptedFixCount += 1
            }
            when (extractTokenValue(line, "origin=")) {
                "auto_fused" -> acceptedFixOriginAutoFusedCount += 1
                "passive_external" -> acceptedFixOriginPassiveExternalCount += 1
                "watch_gps" -> acceptedFixOriginWatchGpsCount += 1
            }
            when (extractTokenValue(line, "provider=")?.lowercase()) {
                "gps" -> fixProviderGpsCount += 1
                "fused" -> fixProviderFusedCount += 1
            }
            val acceptedGapMs = parseLongToken(line, "gapMs=")
            if (acceptedGapMs != null) {
                when (extractTokenValue(line, "screenState=")) {
                    "INTERACTIVE" -> {
                        interactiveFixGapSampleCount += 1
                        interactiveFixGapSumMs += acceptedGapMs
                        if (acceptedGapMs > interactiveFixGapMaxMs) interactiveFixGapMaxMs = acceptedGapMs
                    }
                    "SCREEN_OFF",
                    "AMBIENT",
                    -> {
                        nonInteractiveFixGapSampleCount += 1
                        nonInteractiveFixGapSumMs += acceptedGapMs
                        if (acceptedGapMs > nonInteractiveFixGapMaxMs) {
                            nonInteractiveFixGapMaxMs = acceptedGapMs
                        }
                    }
                    else -> {
                        unknownScreenFixGapSampleCount += 1
                        unknownScreenFixGapSumMs += acceptedGapMs
                        if (acceptedGapMs > unknownScreenFixGapMaxMs) {
                            unknownScreenFixGapMaxMs = acceptedGapMs
                        }
                    }
                }
                parseLongToken(line, "expectedIntervalMs=")?.takeIf { it > 0L }?.let { expectedIntervalMs ->
                    val delayedThresholdMs = maxOf(expectedIntervalMs * 2L, expectedIntervalMs + 2_000L)
                    if (acceptedGapMs > delayedThresholdMs) {
                        delayedFixGapCount += 1
                    }
                }
            }
            if (screenActive && lineEpochMs != null) {
                lastScreenFixAtMs?.let { previousFixAtMs ->
                    val gapMs = (lineEpochMs - previousFixAtMs).coerceAtLeast(0L)
                    screenOnFixGapSampleCount += 1
                    screenOnFixGapSumMs += gapMs
                    if (gapMs > screenOnFixGapMaxMs) {
                        screenOnFixGapMaxMs = gapMs
                    }
                }
                lastScreenFixAtMs = lineEpochMs
            }
        }

        if ("gpsSignal: sample" in line) {
            if (extractTokenValue(line, "sourceMode=") == "passive_external") {
                passiveExternalSignalSampleCount += 1
                val ageMs = parseLongToken(line, "ageMs=")
                val maxAgeMs = parseLongToken(line, "maxAgeMs=")
                val accuracyM = parseFloatToken(line, "accuracyM=")
                val provider = extractTokenValue(line, "provider=")?.takeIf { it != "unknown" }
                val fresh = parseBooleanToken(line, "fresh=")
                val accepted = parseBooleanToken(line, "accepted=")

                when (fresh) {
                    true -> passiveExternalFreshSampleCount += 1
                    false -> passiveExternalStaleSampleCount += 1
                    null -> Unit
                }
                when (accepted) {
                    true -> passiveExternalAcceptedSampleCount += 1
                    false -> passiveExternalRejectedSampleCount += 1
                    null -> Unit
                }
                ageMs?.let { age ->
                    passiveExternalLastAgeMs = age
                    passiveExternalMinAgeMs = minOf(passiveExternalMinAgeMs ?: age, age)
                    passiveExternalMaxAgeMs = maxOf(passiveExternalMaxAgeMs ?: age, age)
                }
                passiveExternalLastMaxAgeMs = maxAgeMs ?: passiveExternalLastMaxAgeMs
                passiveExternalLastAccuracyM = accuracyM ?: passiveExternalLastAccuracyM
                passiveExternalLastProvider = provider ?: passiveExternalLastProvider
            }
            when (extractTokenValue(line, "watchGpsDegraded=")) {
                "true" -> {
                    watchGpsDegradedSampleCount += 1
                    watchGpsDegradedLastObserved = true
                }
                "false" -> watchGpsDegradedLastObserved = false
            }
        }

        if ("[TurnByTurn]" in line) {
            when (extractTokenValue(line, "haptic=")) {
                "turn" -> turnByTurnTurnHapticCount += 1
                "off_route" -> turnByTurnOffRouteHapticCount += 1
            }
            when (extractTokenValue(line, "turnAlert=")) {
                "fired" -> turnByTurnTurnAlertFiredCount += 1
                "filtered" -> turnByTurnTurnAlertFilteredCount += 1
                "off_route" -> turnByTurnTurnAlertOffRouteCount += 1
                "missed_window" -> turnByTurnTurnAlertMissedWindowCount += 1
            }
            if (" active=" in line || line.contains("[TurnByTurn] active=")) {
                turnByTurnSampleCount += 1
                if (parseBooleanToken(line, "active=") == true) {
                    turnByTurnActiveSampleCount += 1
                }
                if (parseBooleanToken(line, "paused=") == true) {
                    turnByTurnPausedSampleCount += 1
                }
                if (parseBooleanToken(line, "offRoute=") == true) {
                    turnByTurnOffRouteSampleCount += 1
                }
                if (parseBooleanToken(line, "guideBackActive=") == true) {
                    turnByTurnGuideBackActiveSampleCount += 1
                }
                if (parseBooleanToken(line, "guideBackPrompt=") == true) {
                    turnByTurnGuideBackPromptSampleCount += 1
                }
                when (extractTokenValue(line, "startDecision=")) {
                    "START_HERE" -> turnByTurnStartHereDecisionCount += 1
                    "REVERSE_ROUTE" -> turnByTurnReverseDecisionCount += 1
                }
                when (extractTokenValue(line, "nextSource=")) {
                    "GPX_GEOMETRY" -> turnByTurnGeometryInstructionSampleCount += 1
                    "BROUTER_HINT" -> turnByTurnBrouterHintInstructionSampleCount += 1
                }
                parseFloatToken(line, "distToRouteM=")?.roundToInt()?.let { distance ->
                    turnByTurnMaxDistanceToRouteMeters =
                        maxOf(turnByTurnMaxDistanceToRouteMeters ?: distance, distance)
                }
            }
        }

        if ("[TraceRecording]" in line) {
            when (extractTokenValue(line, "event=")) {
                "start" -> recordingStartCount += 1
                "recovered" -> recordingRecoveredCount += 1
                "pause" -> recordingPauseCount += 1
                "resume" -> recordingResumeCount += 1
                "point" -> recordingPointSampleCount += 1
                "gap" -> {
                    recordingGapEventCount += 1
                    parseFloatToken(line, "gapEndpointDistanceM=")?.takeIf { it >= 0f }?.let { distance ->
                        recordingGapEndpointDistanceSampleCount += 1
                        recordingGapEndpointDistanceSumMeters += distance
                        recordingGapEndpointDistanceMaxMeters =
                            maxOf(recordingGapEndpointDistanceMaxMeters, distance)
                    }
                }
                "gap_recovery_accept" -> {
                    parseIntToken(line, "gapRecoveryAcceptCount=")?.let { count ->
                        recordingGapRecoveryAcceptCount = maxOf(recordingGapRecoveryAcceptCount ?: count, count)
                    }
                }
                "save_start" -> recordingSaveStartCount += 1
                "save_success" -> recordingSaveSuccessCount += 1
                "save_failure" -> recordingSaveFailureCount += 1
                "discard" -> recordingDiscardCount += 1
                "draft_failure" -> recordingDraftFailureCount += 1
                "draft_clear_failure" -> recordingDraftClearFailureCount += 1
            }
            if (extractTokenValue(line, "event=") == "saved_gpx_verified") {
                parseIntToken(line, "writtenPoints=")?.takeIf { it >= 0 }?.let {
                    recordingSavedGpxWrittenPoints = it
                }
                parseIntToken(line, "parsedPoints=")?.takeIf { it >= 0 }?.let {
                    recordingSavedGpxParsedPoints = it
                }
                parseIntToken(line, "summaryPoints=")?.takeIf { it >= 0 }?.let {
                    recordingSavedGpxSummaryPoints = it
                }
                parseIntToken(line, "summaryDistanceMeters=")?.takeIf { it >= 0 }?.let {
                    recordingSavedGpxSummaryDistanceMeters = it
                }
            }
            extractTokenValue(line, "lastUiAction=")?.takeIf { it.isNotBlank() && it != "na" }?.let {
                recordingLastUiAction = it
            }
            extractTokenValue(line, "endReason=")?.takeIf { it.isNotBlank() && it != "na" }?.let {
                recordingLastEndReason = it
            }
            parseIntToken(line, "points=")?.let { points ->
                recordingLastPointCount = points
                recordingMaxPointCount = maxOf(recordingMaxPointCount ?: points, points)
            }
            parseIntToken(line, "distanceMeters=")?.let { distance ->
                recordingLastDistanceMeters = distance
                recordingMaxDistanceMeters = maxOf(recordingMaxDistanceMeters ?: distance, distance)
            }
            parseIntToken(line, "gpsDistanceMeters=")?.takeIf { it >= 0 }?.let {
                recordingGpsDistanceMeters = it
            }
            parseIntToken(line, "displayDistanceMeters=")?.takeIf { it >= 0 }?.let {
                recordingDisplayDistanceMeters = it
            }
            parseIntToken(line, "podSessionDistanceMeters=")?.takeIf { it >= 0 }?.let {
                recordingPodSessionDistanceMeters = it
            }
            parseIntToken(line, "podIntegratedDistanceMeters=")?.takeIf { it >= 0 }?.let {
                recordingPodIntegratedDistanceMeters = it
            }
            parseLongToken(line, "durationMs=")?.let { duration ->
                recordingLastDurationMs = duration
                recordingMaxDurationMs = maxOf(recordingMaxDurationMs ?: duration, duration)
            }
            parseLongToken(line, "pausedMs=")?.let { paused ->
                recordingLastPausedMs = paused
                recordingMaxPausedMs = maxOf(recordingMaxPausedMs ?: paused, paused)
            }
            parseLongToken(line, "gpsActiveDurationMs=")?.let { duration ->
                recordingGpsActiveDurationMs = maxOf(recordingGpsActiveDurationMs ?: duration, duration)
            }
            (parseIntToken(line, "expectedStoredSampleCount=") ?: parseIntToken(line, "expectedPointCount="))?.let { count ->
                recordingExpectedPointCount = maxOf(recordingExpectedPointCount ?: count, count)
            }
            parseLongToken(line, "averagePointIntervalMs=")?.takeIf { it >= 0L }?.let { interval ->
                recordingAveragePointIntervalMs = interval
            }
            (parseIntToken(line, "storedSampleCaptureRatePercent=") ?: parseIntToken(line, "pointCaptureRatePercent="))
                ?.takeIf { it >= 0 }
                ?.let { rate ->
                    recordingPointCaptureRatePercent = rate
                }
            parseIntToken(line, "recordingGapCount=")?.let { count ->
                recordingGapCount = maxOf(recordingGapCount ?: count, count)
            }
            parseLongToken(line, "recordingMaxGapMs=")?.let { gap ->
                recordingMaxGapMs = maxOf(recordingMaxGapMs ?: gap, gap)
            }
            parseLongToken(line, "lastPointAgeMs=")?.takeIf { it >= 0L }?.let { age ->
                recordingLastPointAgeMs = age
            }
            parseIntToken(line, "forcedAcceptCount=")?.let { count ->
                recordingForcedAcceptCount = maxOf(recordingForcedAcceptCount ?: count, count)
            }
            parseIntToken(line, "gapRecoveryAcceptCount=")?.let { count ->
                recordingGapRecoveryAcceptCount = maxOf(recordingGapRecoveryAcceptCount ?: count, count)
            }
            extractTokenValue(line, "trackSmoothingMode=")?.takeIf { it.isNotBlank() }?.let {
                recordingTrackSmoothingMode = it
            }
            parseIntToken(line, "trackFilterVersion=")?.takeIf { it > 0 }?.let {
                recordingTrackFilterVersion = it
            }
            (
                parseIntToken(line, "qualityHeldFixCount=")
                    ?: parseIntToken(line, "qualityHeld=")
                    ?: parseIntToken(line, "held=")
            )?.let { count ->
                recordingQualityHeldFixCount = maxOf(recordingQualityHeldFixCount ?: count, count)
            }
            (
                parseIntToken(line, "qualityRejectedFixCount=")
                    ?: parseIntToken(line, "qualityRejected=")
                    ?: parseIntToken(line, "rejected=")
            )?.let { count ->
                recordingQualityRejectedFixCount = maxOf(recordingQualityRejectedFixCount ?: count, count)
            }
            (parseIntToken(line, "qualityRelocationCount=") ?: parseIntToken(line, "qualityRelocations="))
                ?.let { count ->
                    recordingQualityRelocationCount = maxOf(recordingQualityRelocationCount ?: count, count)
                }
            (parseIntToken(line, "smoothedPointCount=") ?: parseIntToken(line, "smoothedPoints="))
                ?.let { count ->
                    recordingSmoothedPointCount = maxOf(recordingSmoothedPointCount ?: count, count)
                }
            extractTokenValue(line, "smoothedAdjustmentMeters=")?.let {
                recordingSmoothedAdjustmentMeters = it
            }
            extractTokenValue(line, "maxSmoothedAdjustmentMeters=")?.let {
                recordingMaxSmoothedAdjustmentMeters = it
            }
            parseLongToken(line, "lastSkippedIntervalElapsedMs=")?.takeIf { it >= 0L }?.let { elapsed ->
                recordingLastSkippedIntervalElapsedMs = elapsed
            }
            parseLongToken(line, "maxSkippedIntervalElapsedMs=")?.takeIf { it >= 0L }?.let { elapsed ->
                recordingMaxSkippedIntervalElapsedMs = maxOf(recordingMaxSkippedIntervalElapsedMs ?: elapsed, elapsed)
            }
            extractTokenValue(line, "lastLiveProvider=")?.takeIf { it.isNotBlank() && it != "na" }?.let {
                recordingLastLiveProvider = it
            }
            parseIntToken(line, "lastLiveAccuracyMeters=")?.takeIf { it >= 0 }?.let {
                recordingLastLiveAccuracyMeters = it
            }
            parseIntToken(line, "skippedInterval=")?.let { skipped ->
                recordingSkippedIntervalCount = maxOf(recordingSkippedIntervalCount ?: skipped, skipped)
            }
            parseIntToken(line, "skippedPaused=")?.let { skipped ->
                recordingSkippedPausedCount = maxOf(recordingSkippedPausedCount ?: skipped, skipped)
            }
            parseIntToken(line, "skippedUnusable=")?.let { skipped ->
                recordingSkippedUnusableCount = maxOf(recordingSkippedUnusableCount ?: skipped, skipped)
            }
            extractTokenValue(line, "elevationSource=")?.takeIf { it.isNotBlank() }?.let {
                recordingElevationSource = it
            }
            parseIntToken(line, "demHits=")?.let { hits ->
                recordingDemHitCount = maxOf(recordingDemHitCount ?: hits, hits)
            }
            parseIntToken(line, "demMisses=")?.let { misses ->
                recordingDemMissCount = maxOf(recordingDemMissCount ?: misses, misses)
            }
            parseIntToken(line, "gpsElevationUsed=")?.let { count ->
                recordingGpsElevationUsedCount = maxOf(recordingGpsElevationUsedCount ?: count, count)
            }
            parseIntToken(line, "accuracySamples=")?.let { recordingAccuracySampleCount = it }
            parseIntToken(line, "accuracyAvgMeters=")?.takeIf { it >= 0 }?.let {
                recordingAccuracyAvgMeters = it
            }
            parseIntToken(line, "accuracyMinMeters=")?.takeIf { it >= 0 }?.let {
                recordingAccuracyMinMeters = it
            }
            parseIntToken(line, "accuracyMaxMeters=")?.takeIf { it >= 0 }?.let {
                recordingAccuracyMaxMeters = it
            }
            parseIntToken(line, "elevationGainMeters=")?.let { recordingElevationGainMeters = it }
            parseIntToken(line, "elevationLossMeters=")?.let { recordingElevationLossMeters = it }
            parseIntToken(line, "liveHeartRateBpm=")?.takeIf { it >= 0 }?.let {
                recordingLiveHeartRateBpm = it
            }
            parseIntToken(line, "averageHeartRateBpm=")?.takeIf { it >= 0 }?.let {
                recordingAverageHeartRateBpm = it
            }
            parseIntToken(line, "liveStepCount=")?.takeIf { it >= 0 }?.let {
                recordingLiveStepCount = it
            }
            parseIntToken(line, "liveCadenceSpm=")?.takeIf { it >= 0 }?.let {
                recordingLiveCadenceSpm = it
            }
            extractTokenValue(line, "liveExternalSpeedMps=")
                ?.takeIf { value ->
                    value.isNotBlank() && value != "-1.0" && value != "-1"
                }?.let {
                    recordingLiveExternalSpeedMps = it
                }
            parseLongToken(line, "liveExternalRawDistanceUnits=")?.takeIf { it >= 0 }?.let {
                recordingLiveExternalRawDistanceUnits = it
            }
            parseIntToken(line, "liveExternalDistanceMeters=")?.takeIf { it >= 0 }?.let {
                recordingLiveExternalDistanceMeters = it
            }
            parseIntToken(line, "liveExternalSessionDistanceMeters=")?.takeIf { it >= 0 }?.let {
                recordingLiveExternalSessionDistanceMeters = it
            }
            parseIntToken(line, "liveExternalIntegratedDistanceMeters=")?.takeIf { it >= 0 }?.let {
                recordingLiveExternalIntegratedDistanceMeters = it
            }
            parseIntToken(line, "livePressureHpa=")?.takeIf { it >= 0 }?.let {
                recordingLivePressureHpa = it
            }
            parseLongToken(line, "heartRateAgeMs=")?.takeIf { it >= 0L }?.let {
                recordingHeartRateAgeMs = it
            }
            parseLongToken(line, "stepCountAgeMs=")?.takeIf { it >= 0L }?.let {
                recordingStepCountAgeMs = it
            }
            parseLongToken(line, "cadenceAgeMs=")?.takeIf { it >= 0L }?.let {
                recordingCadenceAgeMs = it
            }
            parseLongToken(line, "externalSpeedAgeMs=")?.takeIf { it >= 0L }?.let {
                recordingExternalSpeedAgeMs = it
            }
            parseLongToken(line, "externalDistanceAgeMs=")?.takeIf { it >= 0L }?.let {
                recordingExternalDistanceAgeMs = it
            }
            parseLongToken(line, "pressureAgeMs=")?.takeIf { it >= 0L }?.let {
                recordingPressureAgeMs = it
            }
            parseIntToken(line, "heartRateSensorEvents=")?.let {
                recordingHeartRateSensorEventCount = maxOf(recordingHeartRateSensorEventCount ?: it, it)
            }
            parseIntToken(line, "stepSensorEvents=")?.let {
                recordingStepSensorEventCount = maxOf(recordingStepSensorEventCount ?: it, it)
            }
            parseIntToken(line, "cadenceSensorEvents=")?.let {
                recordingCadenceSensorEventCount = maxOf(recordingCadenceSensorEventCount ?: it, it)
            }
            parseIntToken(line, "pressureSensorEvents=")?.let {
                recordingPressureSensorEventCount = maxOf(recordingPressureSensorEventCount ?: it, it)
            }
            extractTokenValue(line, "calorieModel=")?.takeIf { it.isNotBlank() }?.let {
                recordingCalorieModel = it
            }
            parseIntToken(line, "caloriesGrossKcal=")?.let { recordingCaloriesGrossKcal = it }
            parseIntToken(line, "caloriesActiveKcal=")?.let { recordingCaloriesActiveKcal = it }
            parseIntToken(line, "caloriesRestingKcal=")?.let { recordingCaloriesRestingKcal = it }
            parseIntToken(line, "pandolfBaseGrossKcal=")?.let { recordingPandolfBaseGrossKcal = it }
            parseIntToken(line, "pandolfBaseActiveKcal=")?.let { recordingPandolfBaseActiveKcal = it }
            parseIntToken(line, "pandolfBaseRestingKcal=")?.let { recordingPandolfBaseRestingKcal = it }
            extractTokenValue(line, "calorieCompareModel=")?.takeIf { it.isNotBlank() }?.let {
                recordingCalorieCompareModel = it
            }
            parseIntToken(line, "lcdaGrossKcal=")?.let { recordingLcdaGrossKcal = it }
            parseIntToken(line, "lcdaActiveKcal=")?.let { recordingLcdaActiveKcal = it }
            parseIntToken(line, "lcdaRestingKcal=")?.let { recordingLcdaRestingKcal = it }
            parseIntToken(line, "byteSize=")?.takeIf { it >= 0 }?.let {
                recordingLastSavedByteSize = it
            }
        }

        if ("[TraceRecordingSensors]" in line) {
            when (extractTokenValue(line, "event=")) {
                "register" -> recordingSensorRegisterCount += 1
            }
            extractTokenValue(line, "requested=")?.takeIf { it.isNotBlank() && it != "unknown" }?.let {
                recordingSensorRequested = it
            }
            extractTokenValue(line, "registered=")?.takeIf { it.isNotBlank() && it != "unknown" }?.let {
                recordingSensorRegistered = it
            }
            extractTokenValue(line, "available=")?.takeIf { it.isNotBlank() }?.let {
                recordingSensorAvailable = it
            }
            parseBooleanToken(line, "bodySensorsGranted=")?.let {
                recordingBodySensorsGranted = it
            }
            parseBooleanToken(line, "activityRecognitionGranted=")?.let {
                recordingActivityRecognitionGranted = it
            }
        }

        if ("[ExternalSensors]" in line) {
            when (extractTokenValue(line, "event=")) {
                "scan_started" -> externalSensorScanStartCount += 1
                "scan_summary" -> {
                    if (extractTokenValue(line, "outcome=") == "stopped") {
                        externalSensorScanStopCount += 1
                    }
                    externalSensorLastScanSummary =
                        line.substringAfter("event=scan_summary ", "").takeIf { it.isNotBlank() }
                }
                "scan_failed",
                "scan_start_failed",
                -> externalSensorScanFailedCount += 1
                "scan_unavailable" -> {
                    externalSensorScanFailedCount += 1
                    extractTokenValue(line, "reason=")?.takeIf { it.isNotBlank() }?.let {
                        externalSensorLastScanFailureReason = it
                    }
                }
            }
            parseIntToken(line, "devices=")?.takeIf { it >= 0 }?.let {
                externalSensorLastScanDeviceCount = it
            }
            extractTokenValue(line, "error=")?.takeIf { it.isNotBlank() }?.let {
                externalSensorLastScanFailureReason = it
            }
            parseIntToken(line, "code=")?.let {
                externalSensorLastScanFailureReason = "code_$it"
            }
        }

        if ("[ExternalHeartRate]" in line) {
            when (extractTokenValue(line, "event=")) {
                "bridge_start" -> externalHeartRateBridgeStartCount += 1
                "bridge_stop" -> externalHeartRateBridgeStopCount += 1
                "connect_requested" -> externalHeartRateConnectRequestedCount += 1
                "connect_skipped" -> {
                    externalHeartRateConnectSkippedCount += 1
                    extractTokenValue(line, "reason=")?.takeIf { it.isNotBlank() }?.let {
                        externalHeartRateLastConnectSkippedReason = it
                    }
                }
                "connected" -> externalHeartRateConnectedCount += 1
                "disconnected" -> externalHeartRateDisconnectedCount += 1
                "reconnect_scheduled" -> externalHeartRateReconnectScheduledCount += 1
                "reconnect_attempt" -> externalHeartRateReconnectAttemptCount += 1
                "notify_requested" -> externalHeartRateNotifyRequestedCount += 1
                "notify_failed" -> externalHeartRateNotifyFailedCount += 1
                "services_failed" -> externalHeartRateServiceFailureCount += 1
                "measurement_missing" -> externalHeartRateMeasurementMissingCount += 1
                "sample" -> {
                    externalHeartRateSampleCount =
                        parseIntToken(line, "count=")
                            ?.let { maxOf(externalHeartRateSampleCount, it) }
                            ?: (externalHeartRateSampleCount + 1)
                    parseIntToken(line, "bpm=")?.takeIf { it > 0 }?.let { bpm ->
                        externalHeartRateLastBpm = bpm
                        externalHeartRateMinBpm = minOf(externalHeartRateMinBpm ?: bpm, bpm)
                        externalHeartRateMaxBpm = maxOf(externalHeartRateMaxBpm ?: bpm, bpm)
                    }
                    lineEpochMs?.let { externalHeartRateLastSampleAtMs = it }
                }
            }
        }

        if ("[ExternalRunPod]" in line) {
            when (extractTokenValue(line, "event=")) {
                "bridge_start" -> externalRunPodBridgeStartCount += 1
                "bridge_stop" -> externalRunPodBridgeStopCount += 1
                "connect_requested" -> externalRunPodConnectRequestedCount += 1
                "connect_skipped" -> {
                    externalRunPodConnectSkippedCount += 1
                    extractTokenValue(line, "reason=")?.takeIf { it.isNotBlank() }?.let {
                        externalRunPodLastConnectSkippedReason = it
                    }
                }
                "connected" -> externalRunPodConnectedCount += 1
                "disconnected" -> externalRunPodDisconnectedCount += 1
                "notify_requested" -> externalRunPodNotifyRequestedCount += 1
                "notify_failed" -> externalRunPodNotifyFailedCount += 1
                "services_failed" -> externalRunPodServiceFailureCount += 1
                "measurement_missing" -> externalRunPodMeasurementMissingCount += 1
                "sample" -> {
                    externalRunPodSampleCount =
                        parseIntToken(line, "count=")
                            ?.let { maxOf(externalRunPodSampleCount, it) }
                            ?: (externalRunPodSampleCount + 1)
                    parseIntToken(line, "cadenceSpm=")?.takeIf { it > 0 }?.let {
                        externalRunPodLastCadenceSpm = it
                    }
                    extractTokenValue(line, "speedMps=")?.takeIf { it.isNotBlank() && it != "na" }?.let {
                        externalRunPodLastSpeedMps = it
                    }
                    parseLongToken(line, "rawDistanceUnits=")?.takeIf { it >= 0 }?.let {
                        externalRunPodLastRawDistanceUnits = it
                    }
                    parseFloatToken(line, "distanceMeters=")?.takeIf { it >= 0f }?.let {
                        externalRunPodLastDistanceMeters = it.roundToInt()
                    }
                    lineEpochMs?.let { externalRunPodLastSampleAtMs = it }
                }
                "power_sample" -> {
                    parseIntToken(line, "powerWatts=")?.takeIf { it >= 0 }?.let {
                        externalRunPodLastPowerWatts = it
                    }
                    lineEpochMs?.let { externalRunPodLastSampleAtMs = it }
                }
                "battery" -> {
                    parseIntToken(line, "levelPercent=")?.takeIf { it in 0..100 }?.let {
                        externalRunPodLastBatteryPercent = it
                    }
                }
            }
        }

        if ("serviceStartFailed" in line) {
            locationServiceStartFailureCount += 1
        }
        if ("serviceStartFallbackFailed" in line) {
            locationServiceStartFallbackFailureCount += 1
        }
        if ("startForegroundFailed" in line) {
            locationStartForegroundFailureCount += 1
        }
        if ("[ThermalTelemetry]" in line) {
            thermalStatusEventCount += 1
            parseIntToken(line, "status=")?.let { status ->
                thermalMaxStatus = maxOf(thermalMaxStatus ?: status, status)
            }
            extractTokenValue(line, "label=")?.takeIf { it.isNotBlank() }?.let { label ->
                thermalLastStatusLabel = label
            }
        }

        when {
            "immediateRequest: burstStart" in line -> burstStartCount += 1
            "immediateRequest: burstEnd" in line -> burstEndCount += 1
            "locationAvailability: available=true" in line -> availabilityTrueCount += 1
            "locationAvailability: available=false" in line -> availabilityFalseCount += 1
            "tracking: enabled=true" in line -> {
                trackingEnabledTrueCount += 1
                pendingScreenPauseTrackingDisable = false
            }
            "tracking: enabled=false" in line -> {
                trackingEnabledFalseCount += 1
                if (pendingScreenPauseTrackingDisable) {
                    trackingDisabledByScreenPauseCount += 1
                    pendingScreenPauseTrackingDisable = false
                }
            }
            "sourceFailover: auto_fused->watch_gps reason=accuracy_plateau" in line -> {
                failoverAutoToWatchAccuracyCount += 1
            }
            "sourceFailover: auto_fused->watch_gps reason=no_fix_gap" in line -> {
                failoverAutoToWatchNoFixCount += 1
            }
            "sourceFailover: watch_gps->auto_fused" in line -> {
                failoverWatchToAutoCount += 1
            }
            "sourceFailover: cleared reason=tracking_disabled" in line -> {
                failoverClearedTrackingDisabledCount += 1
            }
            "sourceFailover: cleared reason=" in line -> {
                failoverClearedOtherCount += 1
            }
            "[ScreenTelemetry] event=activity_resume" in line -> {
                screenResumeCount += 1
                screenActive = true
                pendingScreenPauseTrackingDisable = false
                lastScreenFixAtMs = null
            }
            "[ScreenTelemetry] event=activity_pause" in line -> {
                screenPauseCount += 1
                screenActive = false
                pendingScreenPauseTrackingDisable = true
                lastScreenFixAtMs = null
            }
            "[ScreenTelemetry] event=ambient_enter" in line -> ambientEnterCount += 1
            "[ScreenTelemetry] event=ambient_exit" in line -> ambientExitCount += 1
            "startup_bogus_sample ignored" in line -> startupBogusSampleIgnoredCount += 1
            "staleFix: dropped" in line -> staleFixDropCount += 1
            "sourceMismatch: dropped" in line -> sourceMismatchDropCount += 1
            "immediateRequest: skipGuard" in line -> immediateRequestGuardSkipCount += 1
            "immediateRequest: deferWakeBurst" in line -> immediateRequestDeferredWakeBurstCount += 1
            "gpsSignal: sample" in line && "fresh=true" in line -> gpsFreshTrueCount += 1
            "gpsSignal: sample" in line && "fresh=false" in line -> gpsFreshFalseCount += 1
            "watchGpsDegraded: state=entered" in line -> {
                watchGpsDegradedEnteredCount += 1
                watchGpsDegradedLastObserved = true
            }
            "watchGpsDegraded: state=cleared" in line -> {
                watchGpsDegradedClearedCount += 1
                watchGpsDegradedLastObserved = false
            }
            "watchGpsSelfHeal: skipped" in line -> {
                watchGpsSelfHealSkippedCount += 1
                parseLongToken(line, "searchAgeMs=")?.let { searchAgeMs ->
                    watchGpsSelfHealMaxSearchAgeMs =
                        maxOf(watchGpsSelfHealMaxSearchAgeMs ?: searchAgeMs, searchAgeMs)
                }
            }
            "watchGpsSelfHeal: restarting" in line -> {
                watchGpsSelfHealRestartCount += 1
                parseLongToken(line, "searchAgeMs=")?.let { searchAgeMs ->
                    watchGpsSelfHealMaxSearchAgeMs =
                        maxOf(watchGpsSelfHealMaxSearchAgeMs ?: searchAgeMs, searchAgeMs)
                }
            }
        }
    }

    val modeDurations =
        accumulateModeDurations(
            samples = modeSamples,
            requestStopSamples = requestStopSamples,
            captureWindowEndEpochMs = captureWindowEndEpochMs,
        )
    val backendDurations =
        accumulateBackendDurations(
            samples = backendSamples,
            requestStopSamples = requestStopSamples,
            captureWindowEndEpochMs = captureWindowEndEpochMs,
        )
    val recordingObservedPointCount =
        maxOf(
            recordingPointSampleCount,
            recordingMaxPointCount ?: 0,
            recordingSavedGpxWrittenPoints ?: 0,
            recordingSavedGpxParsedPoints ?: 0,
            recordingSavedGpxSummaryPoints ?: 0,
        )

    return TelemetryInsights(
        burstStartCount = burstStartCount,
        burstEndCount = burstEndCount,
        availabilityTrueCount = availabilityTrueCount,
        availabilityFalseCount = availabilityFalseCount,
        availabilityInferredFromFixCount = availabilityInferredFromFixCount,
        screenResumeCount = screenResumeCount,
        screenPauseCount = screenPauseCount,
        ambientEnterCount = ambientEnterCount,
        ambientExitCount = ambientExitCount,
        trackingEnabledTrueCount = trackingEnabledTrueCount,
        trackingEnabledFalseCount = trackingEnabledFalseCount,
        trackingDisabledByScreenPauseCount = trackingDisabledByScreenPauseCount,
        requestAppliedCount = requestAppliedCount,
        requestModeBurstCount = requestModeBurstCount,
        requestModeStationaryBoundCount = requestModeStationaryBoundCount,
        requestModeStationaryBackgroundCount = requestModeStationaryBackgroundCount,
        requestModeOtherwiseCount = requestModeOtherwiseCount,
        requestModeBurstDurationMs = modeDurations.burstMs,
        requestModeStationaryBoundDurationMs = modeDurations.stationaryBoundMs,
        requestModeStationaryBackgroundDurationMs = modeDurations.stationaryBackgroundMs,
        requestModeOtherwiseDurationMs = modeDurations.otherwiseMs,
        requestModeDurationCoverageMs = modeDurations.coverageMs,
        lastObservedBound = lastObservedBound,
        lastObservedTrackingEnabled = lastObservedTrackingEnabled,
        lastObservedKeepOpen = lastObservedKeepOpen,
        startupBogusSampleIgnoredCount = startupBogusSampleIgnoredCount,
        staleFixDropCount = staleFixDropCount,
        sourceMismatchDropCount = sourceMismatchDropCount,
        immediateRequestGuardSkipCount = immediateRequestGuardSkipCount,
        immediateRequestDeferredWakeBurstCount = immediateRequestDeferredWakeBurstCount,
        gpsFreshTrueCount = gpsFreshTrueCount,
        gpsFreshFalseCount = gpsFreshFalseCount,
        passiveExternalSignalSampleCount = passiveExternalSignalSampleCount,
        passiveExternalFreshSampleCount = passiveExternalFreshSampleCount,
        passiveExternalStaleSampleCount = passiveExternalStaleSampleCount,
        passiveExternalAcceptedSampleCount = passiveExternalAcceptedSampleCount,
        passiveExternalRejectedSampleCount = passiveExternalRejectedSampleCount,
        passiveExternalLastAgeMs = passiveExternalLastAgeMs,
        passiveExternalMinAgeMs = passiveExternalMinAgeMs,
        passiveExternalMaxAgeMs = passiveExternalMaxAgeMs,
        passiveExternalLastMaxAgeMs = passiveExternalLastMaxAgeMs,
        passiveExternalLastAccuracyM = passiveExternalLastAccuracyM,
        passiveExternalLastProvider = passiveExternalLastProvider,
        watchGpsDegradedEnteredCount = watchGpsDegradedEnteredCount,
        watchGpsDegradedClearedCount = watchGpsDegradedClearedCount,
        watchGpsDegradedSampleCount = watchGpsDegradedSampleCount,
        watchGpsDegradedLastObserved = watchGpsDegradedLastObserved,
        batchEventCount = batchEventCount,
        batchOriginAutoFusedCount = batchOriginAutoFusedCount,
        batchOriginPassiveExternalCount = batchOriginPassiveExternalCount,
        batchOriginWatchGpsCount = batchOriginWatchGpsCount,
        batchFallbackCount = batchFallbackCount,
        batchDuplicateCandidatesDroppedTotal = batchDuplicateCandidatesDroppedTotal,
        batchRawCandidatesTotal = batchRawCandidatesTotal,
        batchNormalizedCandidatesTotal = batchNormalizedCandidatesTotal,
        batchAcceptedCandidatesTotal = batchAcceptedCandidatesTotal,
        batchRawCandidatesMax = batchRawCandidatesMax,
        batchNormalizedCandidatesMax = batchNormalizedCandidatesMax,
        callbackAcceptedFixCount = callbackAcceptedFixCount,
        immediateAcceptedFixCount = immediateAcceptedFixCount,
        acceptedFixOriginAutoFusedCount = acceptedFixOriginAutoFusedCount,
        acceptedFixOriginPassiveExternalCount = acceptedFixOriginPassiveExternalCount,
        acceptedFixOriginWatchGpsCount = acceptedFixOriginWatchGpsCount,
        requestBackendAutoFusedCount = requestBackendAutoFusedCount,
        requestBackendPassiveExternalCount = requestBackendPassiveExternalCount,
        requestBackendWatchGpsCount = requestBackendWatchGpsCount,
        requestBackendSwitchCount = backendDurations.switchCount,
        requestBackendAutoFusedDurationMs = backendDurations.autoFusedMs,
        requestBackendPassiveExternalDurationMs = backendDurations.passiveExternalMs,
        requestBackendWatchGpsDurationMs = backendDurations.watchGpsMs,
        requestBackendDurationCoverageMs = backendDurations.coverageMs,
        failoverAutoToWatchAccuracyCount = failoverAutoToWatchAccuracyCount,
        failoverAutoToWatchNoFixCount = failoverAutoToWatchNoFixCount,
        failoverWatchToAutoCount = failoverWatchToAutoCount,
        failoverClearedTrackingDisabledCount = failoverClearedTrackingDisabledCount,
        failoverClearedOtherCount = failoverClearedOtherCount,
        fixProviderGpsCount = fixProviderGpsCount,
        fixProviderFusedCount = fixProviderFusedCount,
        screenOnFixGapSampleCount = screenOnFixGapSampleCount,
        screenOnFixGapAvgMs =
            if (screenOnFixGapSampleCount > 0) {
                screenOnFixGapSumMs / screenOnFixGapSampleCount
            } else {
                null
            },
        screenOnFixGapMaxMs = screenOnFixGapMaxMs,
        turnByTurnSampleCount = turnByTurnSampleCount,
        turnByTurnActiveSampleCount = turnByTurnActiveSampleCount,
        turnByTurnPausedSampleCount = turnByTurnPausedSampleCount,
        turnByTurnOffRouteSampleCount = turnByTurnOffRouteSampleCount,
        turnByTurnGuideBackActiveSampleCount = turnByTurnGuideBackActiveSampleCount,
        turnByTurnGuideBackPromptSampleCount = turnByTurnGuideBackPromptSampleCount,
        turnByTurnStartHereDecisionCount = turnByTurnStartHereDecisionCount,
        turnByTurnReverseDecisionCount = turnByTurnReverseDecisionCount,
        turnByTurnGeometryInstructionSampleCount = turnByTurnGeometryInstructionSampleCount,
        turnByTurnBrouterHintInstructionSampleCount = turnByTurnBrouterHintInstructionSampleCount,
        turnByTurnTurnHapticCount = turnByTurnTurnHapticCount,
        turnByTurnOffRouteHapticCount = turnByTurnOffRouteHapticCount,
        turnByTurnMaxDistanceToRouteMeters = turnByTurnMaxDistanceToRouteMeters,
        recordingStartCount = recordingStartCount,
        recordingRecoveredCount = recordingRecoveredCount,
        recordingPauseCount = recordingPauseCount,
        recordingResumeCount = recordingResumeCount,
        recordingPointSampleCount = recordingObservedPointCount,
        recordingSaveStartCount = recordingSaveStartCount,
        recordingSaveSuccessCount = recordingSaveSuccessCount,
        recordingSaveFailureCount = recordingSaveFailureCount,
        recordingDiscardCount = recordingDiscardCount,
        recordingDraftFailureCount = recordingDraftFailureCount,
        recordingDraftClearFailureCount = recordingDraftClearFailureCount,
        recordingLastUiAction = recordingLastUiAction,
        recordingLastEndReason = recordingLastEndReason,
        recordingLastPointCount = recordingLastPointCount,
        recordingMaxPointCount = recordingMaxPointCount,
        recordingLastDistanceMeters = recordingLastDistanceMeters,
        recordingMaxDistanceMeters = recordingMaxDistanceMeters,
        recordingGpsDistanceMeters = recordingGpsDistanceMeters,
        recordingDisplayDistanceMeters = recordingDisplayDistanceMeters,
        recordingPodSessionDistanceMeters = recordingPodSessionDistanceMeters,
        recordingPodIntegratedDistanceMeters = recordingPodIntegratedDistanceMeters,
        recordingSavedGpxWrittenPoints = recordingSavedGpxWrittenPoints,
        recordingSavedGpxParsedPoints = recordingSavedGpxParsedPoints,
        recordingSavedGpxSummaryPoints = recordingSavedGpxSummaryPoints,
        recordingSavedGpxSummaryDistanceMeters = recordingSavedGpxSummaryDistanceMeters,
        recordingLastDurationMs = recordingLastDurationMs,
        recordingMaxDurationMs = recordingMaxDurationMs,
        recordingLastPausedMs = recordingLastPausedMs,
        recordingMaxPausedMs = recordingMaxPausedMs,
        recordingGpsActiveDurationMs = recordingGpsActiveDurationMs,
        recordingExpectedPointCount = recordingExpectedPointCount,
        recordingAveragePointIntervalMs = recordingAveragePointIntervalMs,
        recordingPointCaptureRatePercent = recordingPointCaptureRatePercent,
        recordingGapCount = recordingGapCount,
        recordingGapEventCount = recordingGapEventCount,
        recordingMaxGapMs = recordingMaxGapMs,
        recordingLastPointAgeMs = recordingLastPointAgeMs,
        recordingForcedAcceptCount = recordingForcedAcceptCount,
        recordingGapRecoveryAcceptCount = recordingGapRecoveryAcceptCount,
        recordingLastSkippedIntervalElapsedMs = recordingLastSkippedIntervalElapsedMs,
        recordingMaxSkippedIntervalElapsedMs = recordingMaxSkippedIntervalElapsedMs,
        recordingLastLiveProvider = recordingLastLiveProvider,
        recordingLastLiveAccuracyMeters = recordingLastLiveAccuracyMeters,
        recordingSkippedIntervalCount = recordingSkippedIntervalCount,
        recordingSkippedPausedCount = recordingSkippedPausedCount,
        recordingSkippedUnusableCount = recordingSkippedUnusableCount,
        recordingElevationSource = recordingElevationSource,
        recordingDemHitCount = recordingDemHitCount,
        recordingDemMissCount = recordingDemMissCount,
        recordingGpsElevationUsedCount = recordingGpsElevationUsedCount,
        recordingAccuracySampleCount = recordingAccuracySampleCount,
        recordingAccuracyAvgMeters = recordingAccuracyAvgMeters,
        recordingAccuracyMinMeters = recordingAccuracyMinMeters,
        recordingAccuracyMaxMeters = recordingAccuracyMaxMeters,
        recordingElevationGainMeters = recordingElevationGainMeters,
        recordingElevationLossMeters = recordingElevationLossMeters,
        recordingLiveHeartRateBpm = recordingLiveHeartRateBpm,
        recordingAverageHeartRateBpm = recordingAverageHeartRateBpm,
        recordingLiveStepCount = recordingLiveStepCount,
        recordingLiveCadenceSpm = recordingLiveCadenceSpm,
        recordingLiveExternalSpeedMps = recordingLiveExternalSpeedMps,
        recordingLiveExternalRawDistanceUnits = recordingLiveExternalRawDistanceUnits,
        recordingLiveExternalDistanceMeters = recordingLiveExternalDistanceMeters,
        recordingLiveExternalSessionDistanceMeters = recordingLiveExternalSessionDistanceMeters,
        recordingLiveExternalIntegratedDistanceMeters = recordingLiveExternalIntegratedDistanceMeters,
        recordingLivePressureHpa = recordingLivePressureHpa,
        recordingHeartRateAgeMs = recordingHeartRateAgeMs,
        recordingStepCountAgeMs = recordingStepCountAgeMs,
        recordingCadenceAgeMs = recordingCadenceAgeMs,
        recordingExternalSpeedAgeMs = recordingExternalSpeedAgeMs,
        recordingExternalDistanceAgeMs = recordingExternalDistanceAgeMs,
        recordingPressureAgeMs = recordingPressureAgeMs,
        recordingHeartRateSensorEventCount = recordingHeartRateSensorEventCount,
        recordingStepSensorEventCount = recordingStepSensorEventCount,
        recordingCadenceSensorEventCount = recordingCadenceSensorEventCount,
        recordingPressureSensorEventCount = recordingPressureSensorEventCount,
        recordingSensorRegisterCount = recordingSensorRegisterCount,
        recordingSensorRequested = recordingSensorRequested,
        recordingSensorRegistered = recordingSensorRegistered,
        recordingSensorAvailable = recordingSensorAvailable,
        recordingBodySensorsGranted = recordingBodySensorsGranted,
        recordingActivityRecognitionGranted = recordingActivityRecognitionGranted,
        externalSensorScanStartCount = externalSensorScanStartCount,
        externalSensorScanStopCount = externalSensorScanStopCount,
        externalSensorScanFailedCount = externalSensorScanFailedCount,
        externalSensorLastScanDeviceCount = externalSensorLastScanDeviceCount,
        externalSensorLastScanSummary = externalSensorLastScanSummary,
        externalSensorLastScanFailureReason = externalSensorLastScanFailureReason,
        externalHeartRateBridgeStartCount = externalHeartRateBridgeStartCount,
        externalHeartRateBridgeStopCount = externalHeartRateBridgeStopCount,
        externalHeartRateConnectRequestedCount = externalHeartRateConnectRequestedCount,
        externalHeartRateConnectSkippedCount = externalHeartRateConnectSkippedCount,
        externalHeartRateLastConnectSkippedReason = externalHeartRateLastConnectSkippedReason,
        externalHeartRateConnectedCount = externalHeartRateConnectedCount,
        externalHeartRateDisconnectedCount = externalHeartRateDisconnectedCount,
        externalHeartRateNotifyRequestedCount = externalHeartRateNotifyRequestedCount,
        externalHeartRateNotifyFailedCount = externalHeartRateNotifyFailedCount,
        externalHeartRateServiceFailureCount = externalHeartRateServiceFailureCount,
        externalHeartRateMeasurementMissingCount = externalHeartRateMeasurementMissingCount,
        externalHeartRateSampleCount = externalHeartRateSampleCount,
        externalHeartRateLastBpm = externalHeartRateLastBpm,
        externalHeartRateMinBpm = externalHeartRateMinBpm,
        externalHeartRateMaxBpm = externalHeartRateMaxBpm,
        externalHeartRateLastSampleAgeMs =
            externalHeartRateLastSampleAtMs?.let { sampleAt ->
                val end = captureWindowEndEpochMs ?: sampleAt
                (end - sampleAt).coerceAtLeast(0L)
            },
        externalRunPodBridgeStartCount = externalRunPodBridgeStartCount,
        externalRunPodBridgeStopCount = externalRunPodBridgeStopCount,
        externalRunPodConnectRequestedCount = externalRunPodConnectRequestedCount,
        externalRunPodConnectSkippedCount = externalRunPodConnectSkippedCount,
        externalRunPodLastConnectSkippedReason = externalRunPodLastConnectSkippedReason,
        externalRunPodConnectedCount = externalRunPodConnectedCount,
        externalRunPodDisconnectedCount = externalRunPodDisconnectedCount,
        externalRunPodNotifyRequestedCount = externalRunPodNotifyRequestedCount,
        externalRunPodNotifyFailedCount = externalRunPodNotifyFailedCount,
        externalRunPodServiceFailureCount = externalRunPodServiceFailureCount,
        externalRunPodMeasurementMissingCount = externalRunPodMeasurementMissingCount,
        externalRunPodSampleCount = externalRunPodSampleCount,
        externalRunPodLastCadenceSpm = externalRunPodLastCadenceSpm,
        externalRunPodLastPowerWatts = externalRunPodLastPowerWatts,
        externalRunPodLastBatteryPercent = externalRunPodLastBatteryPercent,
        externalRunPodLastSpeedMps = externalRunPodLastSpeedMps,
        externalRunPodLastRawDistanceUnits = externalRunPodLastRawDistanceUnits,
        externalRunPodLastDistanceMeters = externalRunPodLastDistanceMeters,
        externalRunPodLastSampleAgeMs =
            externalRunPodLastSampleAtMs?.let { sampleAt ->
                val end = captureWindowEndEpochMs ?: sampleAt
                (end - sampleAt).coerceAtLeast(0L)
            },
        recordingCalorieModel = recordingCalorieModel,
        recordingCaloriesGrossKcal = recordingCaloriesGrossKcal,
        recordingCaloriesActiveKcal = recordingCaloriesActiveKcal,
        recordingCaloriesRestingKcal = recordingCaloriesRestingKcal,
        recordingPandolfBaseGrossKcal = recordingPandolfBaseGrossKcal,
        recordingPandolfBaseActiveKcal = recordingPandolfBaseActiveKcal,
        recordingPandolfBaseRestingKcal = recordingPandolfBaseRestingKcal,
        recordingCalorieCompareModel = recordingCalorieCompareModel,
        recordingLcdaGrossKcal = recordingLcdaGrossKcal,
        recordingLcdaActiveKcal = recordingLcdaActiveKcal,
        recordingLcdaRestingKcal = recordingLcdaRestingKcal,
        recordingLastSavedByteSize = recordingLastSavedByteSize,
        locationServiceStartFailureCount = locationServiceStartFailureCount,
        locationServiceStartFallbackFailureCount = locationServiceStartFallbackFailureCount,
        locationStartForegroundFailureCount = locationStartForegroundFailureCount,
        thermalStatusEventCount = thermalStatusEventCount,
        thermalMaxStatus = thermalMaxStatus,
        thermalLastStatusLabel = thermalLastStatusLabel,
    ).also { insights ->
        insights.watchGpsSelfHealSkippedCount = watchGpsSelfHealSkippedCount
        insights.watchGpsSelfHealRestartCount = watchGpsSelfHealRestartCount
        insights.watchGpsSelfHealMaxSearchAgeMs = watchGpsSelfHealMaxSearchAgeMs
        insights.fixGapBuckets =
            FixGapBuckets(
                interactiveSampleCount = interactiveFixGapSampleCount,
                interactiveAvgMs =
                    if (interactiveFixGapSampleCount > 0) {
                        interactiveFixGapSumMs / interactiveFixGapSampleCount
                    } else {
                        null
                    },
                interactiveMaxMs = interactiveFixGapMaxMs,
                nonInteractiveSampleCount = nonInteractiveFixGapSampleCount,
                nonInteractiveAvgMs =
                    if (nonInteractiveFixGapSampleCount > 0) {
                        nonInteractiveFixGapSumMs / nonInteractiveFixGapSampleCount
                    } else {
                        null
                    },
                nonInteractiveMaxMs = nonInteractiveFixGapMaxMs,
                unknownScreenSampleCount = unknownScreenFixGapSampleCount,
                unknownScreenAvgMs =
                    if (unknownScreenFixGapSampleCount > 0) {
                        unknownScreenFixGapSumMs / unknownScreenFixGapSampleCount
                    } else {
                        null
                    },
                unknownScreenMaxMs = unknownScreenFixGapMaxMs,
                delayedCount = delayedFixGapCount,
            )
        insights.externalHeartRateReconnectScheduledCount = externalHeartRateReconnectScheduledCount
        insights.externalHeartRateReconnectAttemptCount = externalHeartRateReconnectAttemptCount
        insights.turnByTurnTurnAlertFiredCount = turnByTurnTurnAlertFiredCount
        insights.turnByTurnTurnAlertFilteredCount = turnByTurnTurnAlertFilteredCount
        insights.turnByTurnTurnAlertOffRouteCount = turnByTurnTurnAlertOffRouteCount
        insights.turnByTurnTurnAlertMissedWindowCount = turnByTurnTurnAlertMissedWindowCount
        insights.recordingGapEndpointDistanceSampleCount = recordingGapEndpointDistanceSampleCount
        insights.recordingGapEndpointDistanceAvgMeters =
            if (recordingGapEndpointDistanceSampleCount > 0) {
                (recordingGapEndpointDistanceSumMeters / recordingGapEndpointDistanceSampleCount).toFloat()
            } else {
                null
            }
        insights.recordingGapEndpointDistanceMaxMeters =
            recordingGapEndpointDistanceMaxMeters.takeIf { recordingGapEndpointDistanceSampleCount > 0 }
        insights.recordingTrackFilter =
            RecordingTrackFilterInsights(
                smoothingMode = recordingTrackSmoothingMode,
                filterVersion = recordingTrackFilterVersion,
                qualityHeldFixCount = recordingQualityHeldFixCount,
                qualityRejectedFixCount = recordingQualityRejectedFixCount,
                qualityRelocationCount = recordingQualityRelocationCount,
                smoothedPointCount = recordingSmoothedPointCount,
                smoothedAdjustmentMeters = recordingSmoothedAdjustmentMeters,
                maxSmoothedAdjustmentMeters = recordingMaxSmoothedAdjustmentMeters,
                smartTrack = recordingSmartTrack.snapshot(),
                pointDensity = recordingPointDensity.snapshot(),
            )
    }
}

internal fun writeRecordingSmartTrackSection(
    writer: BufferedWriter,
    insights: RecordingSmartTrackInsights,
) {
    writer.writeSmartTrackMotionMetrics(insights)
    writer.writeSmartTrackAccuracyMetrics(insights)
}

internal fun writeRecordingPointDensitySection(
    writer: BufferedWriter,
    insights: RecordingPointDensityInsights,
) {
    writer.appendLine("recordingStoredSampleCaptureRateDefinition=configured_cadence_includes_stationary_suppression")
    writer.appendPointDensityMetric("LocationCallbackReceivedCount", insights.callbackReceivedCount)
    writer.appendPointDensityMetric("UsableLocationCallbackCount", insights.usableCallbackCount)
    writer.appendPointDensityMetric("SmartTrackDecisionCount", insights.smartTrackDecisionCount)
    writer.appendPointDensityMetric("StoredPointCount", insights.storedPointCount)
    writer.appendPointDensityMetric("MovingExpectedStoredSampleCount", insights.movingExpectedStoredSampleCount)
    writer.appendPointDensityMetric("MovingStoredSampleCount", insights.movingStoredSampleCount)
    writer.appendPointDensityMetric("MovingStoredSampleCaptureRatePercent", insights.movingStoredSampleCaptureRatePercent)
    writer.appendPointDensityMetric("MovingGapCount", insights.movingGapCount)
    writer.appendPointDensityMetric("MovingGapMaxMs", insights.movingGapMaxMs)
    writer.appendPointDensityMetric("MovingGapEndpointDistanceMaxM", insights.movingGapEndpointDistanceMaxM)
    writer.appendPointDensityMetric("StationaryGapCount", insights.stationaryGapCount)
    writer.appendPointDensityMetric("StationaryGapMaxMs", insights.stationaryGapMaxMs)
    writer.appendPointDensityMetric("SlowMovementGapCount", insights.slowMovementGapCount)
    writer.appendPointDensityMetric("SlowMovementGapMaxMs", insights.slowMovementGapMaxMs)
    writer.appendPointDensityMetric("UnknownCallbackGapCount", insights.unknownCallbackGapCount)
    writer.appendPointDensityMetric("UnknownCallbackGapMaxMs", insights.unknownCallbackGapMaxMs)
}

private fun BufferedWriter.writeSmartTrackMotionMetrics(insights: RecordingSmartTrackInsights) {
    appendSmartTrackMetric("MotionEvaluatedFixCount", insights.motionEvaluatedFixCount)
    appendSmartTrackMetric("AcceptedReportedSpeedCount", insights.acceptedReportedSpeedCount)
    appendSmartTrackMetric("AcceptedSensorCount", insights.acceptedSensorCount)
    appendSmartTrackMetric("AcceptedConfirmedSlowCount", insights.acceptedConfirmedSlowCount)
    appendSmartTrackMetric("SuppressedStationaryCount", insights.suppressedStationaryCount)
    appendSmartTrackMetric("SuppressedStepStillnessCount", insights.suppressedStepStillnessCount)
    appendSmartTrackMetric("HeldSlowCount", insights.heldSlowCount)
    appendSmartTrackMetric("SegmentStartBypassCount", insights.segmentStartBypassCount)
    appendSmartTrackMetric("StepMotionEvidenceCount", insights.stepMotionEvidenceCount)
    appendSmartTrackMetric("CadenceMotionEvidenceCount", insights.cadenceMotionEvidenceCount)
    appendSmartTrackMetric("SpeedAboveThresholdCount", insights.speedAboveThresholdCount)
    appendSmartTrackMetric("CredibleSpeedCount", insights.credibleSpeedCount)
    appendSmartTrackMetric("NoMotionSensorDataCount", insights.noMotionSensorDataCount)
    appendSmartTrackMetric("StationaryRadiusSampleCount", insights.stationaryRadiusSampleCount)
    appendSmartTrackMetric("StationaryRadiusAvgMeters", insights.stationaryRadiusAvgMeters)
    appendSmartTrackMetric("StationaryRadiusMaxMeters", insights.stationaryRadiusMaxMeters)
    appendSmartTrackMetric("NonAcceptedDisplacementSampleCount", insights.nonAcceptedDisplacementSampleCount)
    appendSmartTrackMetric("NonAcceptedDisplacementAvgMeters", insights.nonAcceptedDisplacementAvgMeters)
    appendSmartTrackMetric("NonAcceptedDisplacementMaxMeters", insights.nonAcceptedDisplacementMaxMeters)
}

private fun BufferedWriter.writeSmartTrackAccuracyMetrics(insights: RecordingSmartTrackInsights) {
    appendSmartTrackMetric("PoorAccuracyRejectedCount", insights.poorAccuracyRejectedCount)
    appendSmartTrackMetric("NonMonotonicRejectedCount", insights.nonMonotonicRejectedCount)
    appendSmartTrackMetric("ImplausibleJumpHeldCount", insights.implausibleJumpHeldCount)
    appendSmartTrackMetric("ConfirmedSustainedMovementCount", insights.confirmedSustainedMovementCount)
    appendSmartTrackMetric("AdaptiveAccuracyFixCount", insights.adaptiveAccuracyFixCount)
    appendSmartTrackMetric("AccuracyBaselineSampleCount", insights.accuracyBaselineSampleCount)
    appendSmartTrackMetric("AccuracyBaselineMedianMeters", insights.accuracyBaselineMedianMeters)
    appendSmartTrackMetric("AccuracyProfileLimitMeters", insights.accuracyProfileLimitMeters)
    appendSmartTrackMetric("AccuracyResolvedLimitMeters", insights.accuracyResolvedLimitMeters)
    appendSmartTrackMetric("AdaptiveAccuracyLimitActive", insights.adaptiveAccuracyLimitActive)
}

private fun BufferedWriter.appendSmartTrackMetric(
    name: String,
    value: Any?,
) {
    appendLine("recordingSmartTrack$name=${value ?: "na"}")
}

private fun BufferedWriter.appendPointDensityMetric(
    name: String,
    value: Any?,
) {
    appendLine("recordingPointDensity$name=${value ?: "na"}")
}

internal fun resolveCaptureWindowEndEpochMs(
    captureSession: DebugTelemetry.CaptureSessionSnapshot,
    exportNowEpochMs: Long,
): Long? =
    captureSession.endedAtMs
        ?: if (captureSession.active) exportNowEpochMs else null

internal fun toTelemetryWindow(
    lines: List<String>,
    startEpochMs: Long?,
    endEpochMs: Long?,
): TelemetryWindow {
    if (lines.isEmpty()) {
        return TelemetryWindow(lines = emptyList(), firstAtMs = null, lastAtMs = null)
    }
    if (startEpochMs == null) {
        return TelemetryWindow(
            lines = lines,
            firstAtMs = parseTelemetryLineEpochMs(lines.first()),
            lastAtMs = parseTelemetryLineEpochMs(lines.last()),
        )
    }

    val filtered =
        lines.filter { line ->
            val ts = parseTelemetryLineEpochMs(line) ?: return@filter false
            val afterStart = ts >= startEpochMs
            val beforeEnd = endEpochMs?.let { ts <= it } ?: true
            afterStart && beforeEnd
        }

    val firstAtMs = filtered.firstOrNull()?.let(::parseTelemetryLineEpochMs)
    val lastAtMs = filtered.lastOrNull()?.let(::parseTelemetryLineEpochMs)
    return TelemetryWindow(lines = filtered, firstAtMs = firstAtMs, lastAtMs = lastAtMs)
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun deriveCompassTelemetryInsights(lines: List<String>): CompassTelemetryInsights {
    if (lines.isEmpty()) return CompassTelemetryInsights()

    var managerStartCount = 0
    var managerStopScheduledCount = 0
    var managerStopRequestedCount = 0
    var rotationSettleSessionStartCount = 0
    var rotationSettleHoldCount = 0
    var rotationSettleUnlockCount = 0
    var rotationSettleReleaseCount = 0
    val rotationSettleHoldReasons = linkedMapOf<String, Int>()
    val rotationSettleUnlockReasons = linkedMapOf<String, Int>()
    val rotationSettleReleaseReasons = linkedMapOf<String, Int>()
    var rotationSettleHoldMaxHeadingDeltaDeg: Float? = null
    var rotationSettleWakeHoldDurationMaxMs: Long? = null
    var rotationSettleReleaseHeadingDeltaMaxDeg: Float? = null
    var rotationSettleFirstVisibleReleaseStepMaxDeg: Float? = null
    var headingSampleCount = 0
    var largeJumpPendingCount = 0
    var largeJumpAcceptedCount = 0
    var staleSampleCount = 0
    var largeJumpWithinManagerStart500MsCount = 0
    var sampleAfterStopScheduledCount = 0
    var sampleAfterStopRequestedCount = 0
    var startupSummaryCount = 0
    var startupHeadingSpanMaxDeg: Float? = null
    var startupMaxJumpMaxDeg: Float? = null
    var startupVisibleHeadingJumpMaxDeg: Float? = null
    var startupVisibleMapRotationJumpMaxDeg: Float? = null
    var startupSourceHandoffCount = 0
    var startupSourceHandoffMaxJumpDeg: Float? = null
    var startupStable3Count = 0
    var startupStable5Count = 0
    var fusedFirstUsableCount = 0
    var fusedFirstUsableLatencyMaxMs: Long? = null
    var fusedReadyCount = 0
    var fusedReadyLatencyMaxMs: Long? = null
    var fusedWarmupRelockCount = 0
    var fusedWarmupRelockStepMaxDeg: Float? = null
    var fusedReadyAfterRelockCount = 0
    var fusedFallbackActivationCount = 0
    var continuityStartCount = 0
    var continuityCompleteCount = 0
    var continuityCancelCount = 0
    var continuityInitialOffsetMaxDeg: Float? = null
    var continuityDurationMaxMs: Long? = null
    var headingLooksWrongReportCount = 0
    var fusedPerfEventCount = 0
    var fusedPerfCallbackCount = 0
    var fusedPerfConfirmedCount = 0
    var fusedPerfUnusableCount = 0
    var fusedPerfHeadingPublishCount = 0
    var fusedPerfCallbackHzMax: Float? = null
    var fusedPerfPublishHzMax: Float? = null
    var renderPerfEventCount = 0
    var renderPerfFrameCount = 0
    var renderPerfTargetUpdateCount = 0
    var renderPerfHeadingRenderCount = 0
    var renderPerfRotationAppliedCount = 0
    var renderPerfRotationSkippedCount = 0
    var renderPerfRotationThrottledCount = 0
    var renderPerfMarkerUpdateCount = 0
    var renderPerfRedrawCount = 0
    var renderPerfFrameHzMax: Float? = null
    var renderPerfRenderHzMax: Float? = null
    var lastManagerStartAtMs: Long? = null
    var lastStopScheduledAtMs: Long? = null
    var lastStopRequestedAtMs: Long? = null

    lines.forEach { line ->
        if ("[CompassTelemetry]" !in line) return@forEach

        val lineEpochMs = parseTelemetryLineEpochMs(line)
        val managerStart = "manager start" in line
        val managerStopScheduled = "manager stop scheduled" in line
        val managerStopRequested = "manager stop requested" in line
        val headingSample =
            " heading raw=" in line ||
                "google_fused sample heading=" in line
        val largeJump = "large_jump" in line

        if (managerStart) {
            managerStartCount += 1
            lastManagerStartAtMs = lineEpochMs
            lastStopScheduledAtMs = null
            lastStopRequestedAtMs = null
        }
        if (managerStopScheduled) {
            managerStopScheduledCount += 1
            lastStopScheduledAtMs = lineEpochMs
        }
        if (managerStopRequested) {
            managerStopRequestedCount += 1
            lastStopRequestedAtMs = lineEpochMs
        }
        if ("rotation_settle stage=start" in line) {
            rotationSettleSessionStartCount += 1
        }
        if ("rotation_settle stage=hold" in line) {
            rotationSettleHoldCount += 1
            val reason = extractTokenValue(line, "reason=") ?: "unknown"
            rotationSettleHoldReasons[reason] = (rotationSettleHoldReasons[reason] ?: 0) + 1
            parseFloatToken(line, "headingDeltaDeg=")?.let { value ->
                val magnitude = abs(value)
                rotationSettleHoldMaxHeadingDeltaDeg =
                    maxOf(rotationSettleHoldMaxHeadingDeltaDeg ?: magnitude, magnitude)
            }
        }
        if ("rotation_settle stage=unlock" in line) {
            rotationSettleUnlockCount += 1
            val reason = extractTokenValue(line, "reason=") ?: "unknown"
            rotationSettleUnlockReasons[reason] = (rotationSettleUnlockReasons[reason] ?: 0) + 1
        }
        if ("rotation_settle stage=release" in line) {
            rotationSettleReleaseCount += 1
            val reason = extractTokenValue(line, "wakeReleaseReason=") ?: "unknown"
            rotationSettleReleaseReasons[reason] = (rotationSettleReleaseReasons[reason] ?: 0) + 1
            parseLongToken(line, "wakeHoldDurationMs=")?.let { value ->
                rotationSettleWakeHoldDurationMaxMs =
                    maxOf(rotationSettleWakeHoldDurationMaxMs ?: value, value)
            }
            parseFloatToken(line, "wakeReleaseHeadingDeltaDeg=")?.let { value ->
                val magnitude = abs(value)
                rotationSettleReleaseHeadingDeltaMaxDeg =
                    maxOf(rotationSettleReleaseHeadingDeltaMaxDeg ?: magnitude, magnitude)
            }
            parseFloatToken(line, "firstVisibleReleaseStepDeg=")?.let { value ->
                val magnitude = abs(value)
                rotationSettleFirstVisibleReleaseStepMaxDeg =
                    maxOf(rotationSettleFirstVisibleReleaseStepMaxDeg ?: magnitude, magnitude)
            }
        }
        if (headingSample) {
            headingSampleCount += 1
            if (lastStopScheduledAtMs != null) {
                sampleAfterStopScheduledCount += 1
            }
            if (lastStopRequestedAtMs != null) {
                sampleAfterStopRequestedCount += 1
            }
        }
        if ("large_jump pending" in line) {
            largeJumpPendingCount += 1
        }
        if ("large_jump accepted" in line) {
            largeJumpAcceptedCount += 1
        }
        if ("google_fused sample_stale ageMs=" in line) {
            staleSampleCount += 1
        }
        if ("wake_session stage=startup_summary" in line) {
            startupSummaryCount += 1
            parseFloatToken(line, "headingSpanDeg=")?.let { value ->
                startupHeadingSpanMaxDeg = maxOf(startupHeadingSpanMaxDeg ?: value, value)
            }
            parseFloatToken(line, "maxJumpDeg=")?.let { value ->
                startupMaxJumpMaxDeg = maxOf(startupMaxJumpMaxDeg ?: value, value)
            }
            parseFloatToken(line, "visibleHeadingMaxJumpDeg=")?.let { value ->
                startupVisibleHeadingJumpMaxDeg =
                    maxOf(startupVisibleHeadingJumpMaxDeg ?: value, value)
            }
            parseFloatToken(line, "visibleMapRotationMaxJumpDeg=")?.let { value ->
                startupVisibleMapRotationJumpMaxDeg =
                    maxOf(startupVisibleMapRotationJumpMaxDeg ?: value, value)
            }
            startupSourceHandoffCount += parseIntToken(line, "sourceHandoffs=") ?: 0
            parseFloatToken(line, "sourceHandoffMaxJumpDeg=")?.let { value ->
                startupSourceHandoffMaxJumpDeg =
                    maxOf(startupSourceHandoffMaxJumpDeg ?: value, value)
            }
            if (extractTokenValue(line, "stable3Ms=") != "na") startupStable3Count += 1
            if (extractTokenValue(line, "stable5Ms=") != "na") startupStable5Count += 1
        }
        if ("google_fused first_usable" in line) {
            fusedFirstUsableCount += 1
            parseLongToken(line, "latencyMs=")?.let { value ->
                fusedFirstUsableLatencyMaxMs = maxOf(fusedFirstUsableLatencyMaxMs ?: value, value)
            }
        }
        if ("google_fused state transition=active_fused" in line) {
            fusedReadyCount += 1
            parseLongToken(line, "latencyMs=")?.let { value ->
                fusedReadyLatencyMaxMs = maxOf(fusedReadyLatencyMaxMs ?: value, value)
            }
            if ((parseIntToken(line, "relockResets=") ?: 0) > 0) {
                fusedReadyAfterRelockCount += 1
            }
        }
        if ("google_fused warmup_relock" in line) {
            fusedWarmupRelockCount += 1
            parseFloatToken(line, "stepDeg=")?.let { value ->
                fusedWarmupRelockStepMaxDeg =
                    maxOf(fusedWarmupRelockStepMaxDeg ?: value, value)
            }
        }
        if ("google_fused state transition=active_fallback" in line) {
            fusedFallbackActivationCount += 1
        }
        if ("map_heading_continuity stage=start" in line) {
            continuityStartCount += 1
            parseFloatToken(line, "offsetDeg=")?.let { value ->
                val magnitude = abs(value)
                continuityInitialOffsetMaxDeg =
                    maxOf(continuityInitialOffsetMaxDeg ?: magnitude, magnitude)
            }
        }
        if ("map_heading_continuity stage=complete" in line) {
            continuityCompleteCount += 1
            parseLongToken(line, "durationMs=")?.let { value ->
                continuityDurationMaxMs = maxOf(continuityDurationMaxMs ?: value, value)
            }
        }
        if ("map_heading_continuity stage=cancel" in line) {
            continuityCancelCount += 1
            parseLongToken(line, "durationMs=")?.let { value ->
                continuityDurationMaxMs = maxOf(continuityDurationMaxMs ?: value, value)
            }
        }
        if ("user_report heading_looks_wrong" in line) {
            headingLooksWrongReportCount += 1
        }
        if ("google_fused perf" in line) {
            fusedPerfEventCount += 1
            fusedPerfCallbackCount += parseIntToken(line, "callbacks=") ?: 0
            fusedPerfConfirmedCount += parseIntToken(line, "confirmed=") ?: 0
            fusedPerfUnusableCount += parseIntToken(line, "unusable=") ?: 0
            fusedPerfHeadingPublishCount += parseIntToken(line, "headingPublishes=") ?: 0
            parseFloatToken(line, "callbackHz=")?.let { value ->
                fusedPerfCallbackHzMax = maxOf(fusedPerfCallbackHzMax ?: value, value)
            }
            parseFloatToken(line, "publishHz=")?.let { value ->
                fusedPerfPublishHzMax = maxOf(fusedPerfPublishHzMax ?: value, value)
            }
        }
        if ("compass_render perf" in line) {
            renderPerfEventCount += 1
            renderPerfFrameCount += parseIntToken(line, "frames=") ?: 0
            renderPerfTargetUpdateCount += parseIntToken(line, "targetUpdates=") ?: 0
            renderPerfHeadingRenderCount += parseIntToken(line, "headingRenders=") ?: 0
            renderPerfRotationAppliedCount += parseIntToken(line, "rotationApplied=") ?: 0
            renderPerfRotationSkippedCount += parseIntToken(line, "rotationSkipped=") ?: 0
            renderPerfRotationThrottledCount += parseIntToken(line, "rotationThrottled=") ?: 0
            renderPerfMarkerUpdateCount += parseIntToken(line, "markerUpdates=") ?: 0
            renderPerfRedrawCount += parseIntToken(line, "redraws=") ?: 0
            parseFloatToken(line, "frameHz=")?.let { value ->
                renderPerfFrameHzMax = maxOf(renderPerfFrameHzMax ?: value, value)
            }
            parseFloatToken(line, "renderHz=")?.let { value ->
                renderPerfRenderHzMax = maxOf(renderPerfRenderHzMax ?: value, value)
            }
        }
        if (largeJump && lineEpochMs != null) {
            val startAtMs = lastManagerStartAtMs
            if (startAtMs != null && lineEpochMs - startAtMs in 0L..500L) {
                largeJumpWithinManagerStart500MsCount += 1
            }
        }
    }

    return CompassTelemetryInsights(
        managerStartCount = managerStartCount,
        managerStopScheduledCount = managerStopScheduledCount,
        managerStopRequestedCount = managerStopRequestedCount,
        rotationSettleSessionStartCount = rotationSettleSessionStartCount,
        rotationSettleHoldCount = rotationSettleHoldCount,
        rotationSettleUnlockCount = rotationSettleUnlockCount,
        rotationSettleReleaseCount = rotationSettleReleaseCount,
        rotationSettleHoldReasons =
            rotationSettleHoldReasons.entries
                .joinToString(",") { "${it.key}:${it.value}" }
                .ifBlank { "none" },
        rotationSettleUnlockReasons =
            rotationSettleUnlockReasons.entries
                .joinToString(",") { "${it.key}:${it.value}" }
                .ifBlank { "none" },
        rotationSettleReleaseReasons =
            rotationSettleReleaseReasons.entries
                .joinToString(",") { "${it.key}:${it.value}" }
                .ifBlank { "none" },
        rotationSettleHoldMaxHeadingDeltaDeg = rotationSettleHoldMaxHeadingDeltaDeg,
        rotationSettleWakeHoldDurationMaxMs = rotationSettleWakeHoldDurationMaxMs,
        rotationSettleReleaseHeadingDeltaMaxDeg = rotationSettleReleaseHeadingDeltaMaxDeg,
        rotationSettleFirstVisibleReleaseStepMaxDeg = rotationSettleFirstVisibleReleaseStepMaxDeg,
        headingSampleCount = headingSampleCount,
        headingDiagnosticSampleCount = headingSampleCount,
        largeJumpPendingCount = largeJumpPendingCount,
        largeJumpAcceptedCount = largeJumpAcceptedCount,
        staleSampleCount = staleSampleCount,
        largeJumpWithinManagerStart500MsCount = largeJumpWithinManagerStart500MsCount,
        sampleAfterStopScheduledCount = sampleAfterStopScheduledCount,
        sampleAfterStopRequestedCount = sampleAfterStopRequestedCount,
        startupSummaryCount = startupSummaryCount,
        startupHeadingSpanMaxDeg = startupHeadingSpanMaxDeg,
        startupMaxJumpMaxDeg = startupMaxJumpMaxDeg,
        startupVisibleHeadingJumpMaxDeg = startupVisibleHeadingJumpMaxDeg,
        startupVisibleMapRotationJumpMaxDeg = startupVisibleMapRotationJumpMaxDeg,
        startupSourceHandoffCount = startupSourceHandoffCount,
        startupSourceHandoffMaxJumpDeg = startupSourceHandoffMaxJumpDeg,
        startupStable3Count = startupStable3Count,
        startupStable5Count = startupStable5Count,
        fusedFirstUsableCount = fusedFirstUsableCount,
        fusedFirstUsableLatencyMaxMs = fusedFirstUsableLatencyMaxMs,
        fusedReadyCount = fusedReadyCount,
        fusedReadyLatencyMaxMs = fusedReadyLatencyMaxMs,
        fusedWarmupRelockCount = fusedWarmupRelockCount,
        fusedWarmupRelockStepMaxDeg = fusedWarmupRelockStepMaxDeg,
        fusedReadyAfterRelockCount = fusedReadyAfterRelockCount,
        fusedFallbackActivationCount = fusedFallbackActivationCount,
        continuityStartCount = continuityStartCount,
        continuityCompleteCount = continuityCompleteCount,
        continuityCancelCount = continuityCancelCount,
        continuityInitialOffsetMaxDeg = continuityInitialOffsetMaxDeg,
        continuityDurationMaxMs = continuityDurationMaxMs,
        headingLooksWrongReportCount = headingLooksWrongReportCount,
        fusedPerfEventCount = fusedPerfEventCount,
        fusedPerfCallbackCount = fusedPerfCallbackCount,
        fusedPerfConfirmedCount = fusedPerfConfirmedCount,
        fusedPerfUnusableCount = fusedPerfUnusableCount,
        fusedPerfHeadingPublishCount = fusedPerfHeadingPublishCount,
        fusedPerfCallbackHzMax = fusedPerfCallbackHzMax,
        fusedPerfPublishHzMax = fusedPerfPublishHzMax,
        renderPerfEventCount = renderPerfEventCount,
        renderPerfFrameCount = renderPerfFrameCount,
        renderPerfTargetUpdateCount = renderPerfTargetUpdateCount,
        renderPerfHeadingRenderCount = renderPerfHeadingRenderCount,
        renderPerfRotationAppliedCount = renderPerfRotationAppliedCount,
        renderPerfRotationSkippedCount = renderPerfRotationSkippedCount,
        renderPerfRotationThrottledCount = renderPerfRotationThrottledCount,
        renderPerfMarkerUpdateCount = renderPerfMarkerUpdateCount,
        renderPerfRedrawCount = renderPerfRedrawCount,
        renderPerfFrameHzMax = renderPerfFrameHzMax,
        renderPerfRenderHzMax = renderPerfRenderHzMax,
    )
}

private fun parseRequestMode(line: String): LocationRequestMode? {
    if ("requestUpdates applied:" !in line && "reason=gps_request_applied" !in line) return null

    val mode = extractTokenValue(line, "mode=")?.uppercase()
    if (mode != null) {
        return when (mode) {
            "BURST" -> LocationRequestMode.BURST
            "PASSIVE" -> LocationRequestMode.STATIONARY_BACKGROUND
            "INTERACTIVE" -> LocationRequestMode.OTHERWISE
            else -> null
        }
    }

    val burst = extractTokenValue(line, "burst=")?.toBooleanStrictOrNull() ?: false
    if (burst) return LocationRequestMode.BURST

    val state = extractTokenValue(line, "state=")
    val bound = extractTokenValue(line, "bound=")?.toBooleanStrictOrNull() ?: false
    return if (state == "STATIONARY" && bound) {
        LocationRequestMode.STATIONARY_BOUND
    } else if (state == "STATIONARY") {
        LocationRequestMode.STATIONARY_BACKGROUND
    } else {
        LocationRequestMode.OTHERWISE
    }
}

private fun isRequestStopLine(line: String): Boolean {
    if ("requestUpdates cleared:" in line || "reason=gps_request_cleared" in line) return true
    if ("tracking: enabled=false" in line) return true
    if ("runtimeState:" !in line) return false

    val trackingEnabled = parseBooleanToken(line, "trackingEnabled=")
    if (trackingEnabled == false) return true

    val screenState = extractTokenValue(line, "screenState=")
    val backgroundGpsEnabled = parseBooleanToken(line, "backgroundGpsEnabled=")
    return screenState in setOf("SCREEN_OFF", "AMBIENT") && backgroundGpsEnabled == false
}

private fun parseLegacyTrackingEnabled(line: String): Boolean? =
    when {
        "tracking: enabled=true" in line -> true
        "tracking: enabled=false" in line -> false
        else -> null
    }

private fun parseBackendMode(token: String?): RequestBackendMode? =
    when (token?.lowercase()) {
        "auto_fused" -> RequestBackendMode.AUTO_FUSED
        "passive_external" -> RequestBackendMode.PASSIVE_EXTERNAL
        "watch_gps" -> RequestBackendMode.WATCH_GPS
        else -> null
    }

private fun parseTelemetryLineEpochMs(line: String): Long? {
    val separatorIndex = line.indexOf(" [")
    if (separatorIndex <= 0) return null
    val timestampText = line.substring(0, separatorIndex).trim()
    return runCatching {
        val localDateTime = LocalDateTime.parse(timestampText, telemetryLineTimestampFormatter)
        localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun extractTokenValue(
    line: String,
    key: String,
): String? {
    val index = line.indexOf(key)
    if (index < 0) return null
    val start = index + key.length
    if (start >= line.length) return null
    val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
    return line.substring(start, end).trim()
}

private fun parseBooleanToken(
    line: String,
    key: String,
): Boolean? = extractTokenValue(line, key)?.toBooleanStrictOrNull()

private fun parseIntToken(
    line: String,
    key: String,
): Int? = extractTokenValue(line, key)?.toIntOrNull()

private fun parseFloatToken(
    line: String,
    key: String,
): Float? = extractTokenValue(line, key)?.toFloatOrNull()

private fun parseLongToken(
    line: String,
    key: String,
): Long? = extractTokenValue(line, key)?.toLongOrNull()

internal fun writeAcceptedFixQualitySection(
    writer: BufferedWriter,
    prefix: String,
    summary: AcceptedFixSummary,
    quality: ObservedFixQualitySummary,
) {
    writer.appendLine("${prefix}AcceptedFixCount=${summary.acceptedFixCount}")
    writer.appendLine("${prefix}CallbackFixCount=${summary.callbackFixCount}")
    writer.appendLine("${prefix}ImmediateFixCount=${summary.immediateFixCount}")
    writer.appendLine("${prefix}ProviderGpsCount=${summary.providerGpsCount}")
    writer.appendLine("${prefix}ProviderFusedCount=${summary.providerFusedCount}")
    writer.appendLine("${prefix}ReportedAccuracyMedianM=${TelemetryFormatters.decimalOrNa(summary.reportedAccuracyMedianM, 1)}")
    writer.appendLine("${prefix}ReportedAccuracyP90M=${TelemetryFormatters.decimalOrNa(summary.reportedAccuracyP90M, 1)}")
    writer.appendLine("${prefix}ReportedAccuracyMinM=${TelemetryFormatters.decimalOrNa(summary.reportedAccuracyMinM, 1)}")
    writer.appendLine("${prefix}ReportedAccuracyMaxM=${TelemetryFormatters.decimalOrNa(summary.reportedAccuracyMaxM, 1)}")
    writer.appendLine("${prefix}ReportedAccuracyDistinctCount=${summary.reportedAccuracyDistinctCount}")
    writer.appendLine("${prefix}ReportedAccuracyAllSame=${summary.reportedAccuracyAllSame}")
    writer.appendLine("${prefix}AcceptedFixAgeMedianMs=${summary.ageMedianMs?.toString() ?: "na"}")
    writer.appendLine("${prefix}AcceptedFixAgeP90Ms=${summary.ageP90Ms?.toString() ?: "na"}")
    writer.appendLine("${prefix}AcceptedFixAgeMaxMs=${summary.ageMaxMs?.toString() ?: "na"}")
    writer.appendLine("${prefix}ReportedAccuracyReliability=${quality.reportedAccuracyReliability}")
    writer.appendLine("${prefix}ObservedFixQuality=${quality.quality}")
    writer.appendLine("${prefix}ObservedFixQualityConfidence=${quality.confidence}")
    writer.appendLine("${prefix}ObservedFixQualityReason=${quality.reason}")
}

internal fun deriveAcceptedFixSummariesFromLines(lines: List<String>): AcceptedFixSummaries =
    AcceptedFixSummaries(
        overall = summarizeAcceptedFixes(lines = lines, originFilter = null),
        autoFused = summarizeAcceptedFixes(lines = lines, originFilter = "auto_fused"),
        watchGps = summarizeAcceptedFixes(lines = lines, originFilter = "watch_gps"),
    )

private fun summarizeAcceptedFixes(
    lines: List<String>,
    originFilter: String?,
): AcceptedFixSummary {
    val relevantLines =
        lines.filter { line ->
            "fixAccepted: source=" in line &&
                (originFilter == null || extractTokenValue(line, "origin=") == originFilter)
        }
    if (relevantLines.isEmpty()) return AcceptedFixSummary()

    val accuracies = mutableListOf<Float>()
    val ages = mutableListOf<Long>()
    var callbackFixCount = 0
    var immediateFixCount = 0
    var providerGpsCount = 0
    var providerFusedCount = 0

    relevantLines.forEach { line ->
        when (extractTokenValue(line, "source=")) {
            "callback" -> callbackFixCount += 1
            "immediate" -> immediateFixCount += 1
        }
        when (extractTokenValue(line, "provider=")?.lowercase()) {
            "gps" -> providerGpsCount += 1
            "fused" -> providerFusedCount += 1
        }
        extractTokenValue(line, "accuracyM=")?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { accuracies += it }
        extractTokenValue(line, "ageMs=")?.toLongOrNull()?.takeIf { it >= 0L }?.let { ages += it }
    }

    val sortedAccuracies = accuracies.sorted()
    val sortedAges = ages.sorted()
    return AcceptedFixSummary(
        acceptedFixCount = relevantLines.size,
        callbackFixCount = callbackFixCount,
        immediateFixCount = immediateFixCount,
        providerGpsCount = providerGpsCount,
        providerFusedCount = providerFusedCount,
        reportedAccuracyMedianM = percentileFloat(sortedAccuracies, 0.5),
        reportedAccuracyP90M = percentileFloat(sortedAccuracies, 0.9),
        reportedAccuracyMinM = sortedAccuracies.firstOrNull(),
        reportedAccuracyMaxM = sortedAccuracies.lastOrNull(),
        reportedAccuracyDistinctCount = sortedAccuracies.distinct().size,
        reportedAccuracyAllSame = sortedAccuracies.isNotEmpty() && sortedAccuracies.first() == sortedAccuracies.last(),
        ageMedianMs = percentileLong(sortedAges, 0.5),
        ageP90Ms = percentileLong(sortedAges, 0.9),
        ageMaxMs = sortedAges.lastOrNull(),
    )
}

internal fun inferObservedFixQualityFromSummary(
    summary: AcceptedFixSummary,
    origin: String?,
    gnssInsights: GnssInsights,
): ObservedFixQualitySummary {
    if (summary.acceptedFixCount <= 0) {
        return ObservedFixQualitySummary()
    }

    val reportedAccuracyReliability =
        when {
            origin == "watch_gps" &&
                summary.reportedAccuracyAllSame &&
                summary.reportedAccuracyMedianM == 125f -> "suspect_constant_watch_gps"
            summary.reportedAccuracyAllSame && summary.acceptedFixCount >= 3 -> "suspect_constant"
            summary.reportedAccuracyDistinctCount <= 1 -> "low_variation"
            else -> "variable"
        }

    var score = 0
    summary.ageP90Ms?.let { ageP90 ->
        when {
            ageP90 <= 100L -> score += 2
            ageP90 <= 250L -> score += 1
        }
    }
    summary.ageMaxMs?.let { ageMax ->
        when {
            ageMax <= 250L -> score += 2
            ageMax <= 1_000L -> score += 1
        }
    }
    if (summary.acceptedFixCount >= 5) {
        score += 1
    }

    if (origin == "watch_gps") {
        when {
            gnssInsights.statusSampleCount >= 3 &&
                gnssInsights.usedInFixAvg >= 12.0 &&
                (gnssInsights.cn0AvgDbHz ?: 0.0) >= 20.0 -> score += 2
            gnssInsights.firstFixCount > 0 && gnssInsights.usedInFixAvg >= 6.0 -> score += 1
        }
    }

    val quality =
        when {
            score >= 6 -> "good"
            score >= 3 -> "moderate"
            else -> "weak"
        }
    val confidence =
        when {
            origin == "watch_gps" && summary.acceptedFixCount >= 5 && gnssInsights.statusSampleCount >= 3 -> "high"
            summary.acceptedFixCount >= 3 -> "medium"
            else -> "low"
        }

    return ObservedFixQualitySummary(
        quality = quality,
        confidence = confidence,
        reportedAccuracyReliability = reportedAccuracyReliability,
        reason =
            buildObservedFixQualityReason(
                summary = summary,
                origin = origin,
                gnssInsights = gnssInsights,
                reportedAccuracyReliability = reportedAccuracyReliability,
            ),
    )
}

private fun buildObservedFixQualityReason(
    summary: AcceptedFixSummary,
    origin: String?,
    gnssInsights: GnssInsights,
    reportedAccuracyReliability: String,
): String {
    val freshness =
        buildString {
            append("fresh accepted fixes")
            summary.ageP90Ms?.let { append(" p90AgeMs=").append(it) }
            summary.ageMaxMs?.let { append(" maxAgeMs=").append(it) }
        }
    if (origin != "watch_gps") {
        return freshness
    }
    val gnssSupport =
        buildString {
            append("gnss usedInFixAvg=").append(TelemetryFormatters.decimal(gnssInsights.usedInFixAvg, 1))
            append(" cn0AvgDbHz=").append(TelemetryFormatters.decimalOrNa(gnssInsights.cn0AvgDbHz, 1))
            append(" firstFixCount=").append(gnssInsights.firstFixCount)
        }
    return if (reportedAccuracyReliability == "suspect_constant_watch_gps") {
        "$freshness with $gnssSupport despite constant reported accuracy"
    } else {
        "$freshness with $gnssSupport"
    }
}

private fun percentileFloat(
    sortedValues: List<Float>,
    fraction: Double,
): Float? {
    if (sortedValues.isEmpty()) return null
    val index = ((sortedValues.lastIndex) * fraction).toInt().coerceIn(0, sortedValues.lastIndex)
    return sortedValues[index]
}

private fun percentileLong(
    sortedValues: List<Long>,
    fraction: Double,
): Long? {
    if (sortedValues.isEmpty()) return null
    val index = ((sortedValues.lastIndex) * fraction).toInt().coerceIn(0, sortedValues.lastIndex)
    return sortedValues[index]
}

internal fun deriveGnssInsights(lines: List<String>): GnssInsights {
    if (lines.isEmpty()) return GnssInsights()

    var statusSampleCount = 0
    var startedCount = 0
    var stoppedCount = 0
    var firstFixCount = 0
    var collectorRegisteredCount = 0
    var collectorUnregisteredCount = 0
    var collectorInactiveCount = 0
    var collectorPolicyDisabledCount = 0
    var usedZeroWithFreshLocationCount = 0
    var signalsWithoutFreshLocationCount = 0

    var firstFixTtffTotalMs = 0L
    var firstFixTtffMinMs = Int.MAX_VALUE
    var firstFixTtffMaxMs = 0

    var satellitesTotal = 0L
    var satellitesMax = 0
    var usedInFixTotal = 0L
    var usedInFixMax = 0
    var signalSatellitesTotal = 0L
    var signalSatellitesMax = 0
    var almanacSatellitesTotal = 0L
    var almanacSatellitesMax = 0
    var ephemerisSatellitesTotal = 0L
    var ephemerisSatellitesMax = 0
    var noRadioSignalStatusCount = 0
    var signalsNoEphemerisStatusCount = 0
    var ephemerisNoFixStatusCount = 0
    var satellitesUsedStatusCount = 0
    var acquisitionSignalDetectedCount = 0
    var acquisitionEphemerisAvailableCount = 0
    var acquisitionSatellitesUsedCount = 0

    var cn0SampleCount = 0
    var cn0Total = 0.0
    var cn0Max: Float? = null
    var carrierFrequencyStatusCount = 0
    var l1ObservedStatusCount = 0
    var l5ObservedStatusCount = 0
    var dualBandObservedStatusCount = 0
    var l1SatelliteMax = 0
    var l5SatelliteMax = 0

    lines.forEach { line ->
        when {
            " event=started" in line -> startedCount += 1
            " event=stopped" in line -> stoppedCount += 1
            " event=collector_registered" in line -> collectorRegisteredCount += 1
            " event=collector_unregistered" in line -> collectorUnregisteredCount += 1
            " event=collector_inactive" in line -> collectorInactiveCount += 1
            " event=collector_policy_disabled" in line -> collectorPolicyDisabledCount += 1
            " event=status_location_disagreement kind=used_zero_with_fresh_location" in line ->
                usedZeroWithFreshLocationCount += 1
            " event=status_location_disagreement kind=signals_without_fresh_location" in line ->
                signalsWithoutFreshLocationCount += 1
            " event=acquisition_signal_detected" in line -> acquisitionSignalDetectedCount += 1
            " event=acquisition_ephemeris_available" in line -> acquisitionEphemerisAvailableCount += 1
            " event=acquisition_satellites_used" in line -> acquisitionSatellitesUsedCount += 1
            " event=first_fix" in line -> {
                firstFixCount += 1
                val ttffMs = parseIntToken(line, "ttffMs=")
                if (ttffMs != null && ttffMs >= 0) {
                    firstFixTtffTotalMs += ttffMs.toLong()
                    if (ttffMs < firstFixTtffMinMs) firstFixTtffMinMs = ttffMs
                    if (ttffMs > firstFixTtffMaxMs) firstFixTtffMaxMs = ttffMs
                }
            }
            " status " in line || line.endsWith(" status") -> {
                val sats = parseIntToken(line, "sats=") ?: 0
                val used = parseIntToken(line, "used=") ?: 0
                statusSampleCount += 1
                satellitesTotal += sats.toLong()
                usedInFixTotal += used.toLong()
                if (sats > satellitesMax) satellitesMax = sats
                if (used > usedInFixMax) usedInFixMax = used
                val signalSatellites = parseIntToken(line, "signal=") ?: 0
                val almanacSatellites = parseIntToken(line, "almanac=") ?: 0
                val ephemerisSatellites = parseIntToken(line, "ephemeris=") ?: 0
                signalSatellitesTotal += signalSatellites.toLong()
                almanacSatellitesTotal += almanacSatellites.toLong()
                ephemerisSatellitesTotal += ephemerisSatellites.toLong()
                if (signalSatellites > signalSatellitesMax) signalSatellitesMax = signalSatellites
                if (almanacSatellites > almanacSatellitesMax) almanacSatellitesMax = almanacSatellites
                if (ephemerisSatellites > ephemerisSatellitesMax) ephemerisSatellitesMax = ephemerisSatellites
                when (extractTokenValue(line, "acquisition=")) {
                    "no_radio_signal" -> noRadioSignalStatusCount += 1
                    "signals_no_ephemeris" -> signalsNoEphemerisStatusCount += 1
                    "ephemeris_no_fix" -> ephemerisNoFixStatusCount += 1
                    "satellites_used" -> satellitesUsedStatusCount += 1
                }

                val cn0Avg = parseFloatToken(line, "cn0Avg=")
                if (cn0Avg != null && cn0Avg.isFinite()) {
                    cn0SampleCount += 1
                    cn0Total += cn0Avg.toDouble()
                }
                val cn0LineMax = parseFloatToken(line, "cn0Max=")
                if (cn0LineMax != null && cn0LineMax.isFinite()) {
                    cn0Max = maxOf(cn0Max ?: cn0LineMax, cn0LineMax)
                }
                val carrierSatellites = parseIntToken(line, "carrier=") ?: 0
                if (carrierSatellites > 0) {
                    carrierFrequencyStatusCount += 1
                }
                val l1Satellites = parseIntToken(line, "l1=") ?: 0
                if (l1Satellites > 0) {
                    l1ObservedStatusCount += 1
                }
                if (l1Satellites > l1SatelliteMax) {
                    l1SatelliteMax = l1Satellites
                }
                val l5Satellites = parseIntToken(line, "l5=") ?: 0
                if (l5Satellites > 0) {
                    l5ObservedStatusCount += 1
                }
                if (l5Satellites > l5SatelliteMax) {
                    l5SatelliteMax = l5Satellites
                }
                if (parseBooleanToken(line, "dual=") == true) {
                    dualBandObservedStatusCount += 1
                }
            }
        }
    }

    val firstFixTtffAvgMs =
        if (firstFixCount > 0) {
            (firstFixTtffTotalMs / firstFixCount).coerceAtLeast(0L)
        } else {
            0L
        }
    val satellitesAvg =
        if (statusSampleCount > 0) {
            satellitesTotal.toDouble() / statusSampleCount.toDouble()
        } else {
            0.0
        }
    val usedInFixAvg =
        if (statusSampleCount > 0) {
            usedInFixTotal.toDouble() / statusSampleCount.toDouble()
        } else {
            0.0
        }
    val signalSatellitesAvg =
        if (statusSampleCount > 0) {
            signalSatellitesTotal.toDouble() / statusSampleCount.toDouble()
        } else {
            0.0
        }
    val almanacSatellitesAvg =
        if (statusSampleCount > 0) {
            almanacSatellitesTotal.toDouble() / statusSampleCount.toDouble()
        } else {
            0.0
        }
    val ephemerisSatellitesAvg =
        if (statusSampleCount > 0) {
            ephemerisSatellitesTotal.toDouble() / statusSampleCount.toDouble()
        } else {
            0.0
        }
    val cn0AvgDbHz =
        if (cn0SampleCount > 0) {
            cn0Total / cn0SampleCount.toDouble()
        } else {
            null
        }

    return GnssInsights(
        statusSampleCount = statusSampleCount,
        startedCount = startedCount,
        stoppedCount = stoppedCount,
        firstFixCount = firstFixCount,
        firstFixTtffAvgMs = firstFixTtffAvgMs,
        firstFixTtffMinMs =
            if (firstFixCount > 0 && firstFixTtffMinMs != Int.MAX_VALUE) {
                firstFixTtffMinMs
            } else {
                0
            },
        firstFixTtffMaxMs = if (firstFixCount > 0) firstFixTtffMaxMs else 0,
        satellitesAvg = satellitesAvg,
        satellitesMax = satellitesMax,
        usedInFixAvg = usedInFixAvg,
        usedInFixMax = usedInFixMax,
        signalSatellitesAvg = signalSatellitesAvg,
        signalSatellitesMax = signalSatellitesMax,
        almanacSatellitesAvg = almanacSatellitesAvg,
        almanacSatellitesMax = almanacSatellitesMax,
        ephemerisSatellitesAvg = ephemerisSatellitesAvg,
        ephemerisSatellitesMax = ephemerisSatellitesMax,
        noRadioSignalStatusCount = noRadioSignalStatusCount,
        signalsNoEphemerisStatusCount = signalsNoEphemerisStatusCount,
        ephemerisNoFixStatusCount = ephemerisNoFixStatusCount,
        satellitesUsedStatusCount = satellitesUsedStatusCount,
        acquisitionSignalDetectedCount = acquisitionSignalDetectedCount,
        acquisitionEphemerisAvailableCount = acquisitionEphemerisAvailableCount,
        acquisitionSatellitesUsedCount = acquisitionSatellitesUsedCount,
        cn0AvgDbHz = cn0AvgDbHz,
        cn0MaxDbHz = cn0Max,
        carrierFrequencyStatusCount = carrierFrequencyStatusCount,
        l1ObservedStatusCount = l1ObservedStatusCount,
        l5ObservedStatusCount = l5ObservedStatusCount,
        dualBandObservedStatusCount = dualBandObservedStatusCount,
        l1SatelliteMax = l1SatelliteMax,
        l5SatelliteMax = l5SatelliteMax,
        collectorRegisteredCount = collectorRegisteredCount,
        collectorUnregisteredCount = collectorUnregisteredCount,
        collectorInactiveCount = collectorInactiveCount,
        collectorPolicyDisabledCount = collectorPolicyDisabledCount,
        usedZeroWithFreshLocationCount = usedZeroWithFreshLocationCount,
        signalsWithoutFreshLocationCount = signalsWithoutFreshLocationCount,
    )
}

private fun accumulateModeDurations(
    samples: List<ModeSample>,
    requestStopSamples: List<Long>,
    captureWindowEndEpochMs: Long?,
): ModeDurations {
    if (samples.isEmpty()) {
        return ModeDurations(
            burstMs = 0L,
            stationaryBoundMs = 0L,
            stationaryBackgroundMs = 0L,
            otherwiseMs = 0L,
            coverageMs = 0L,
        )
    }

    var burstMs = 0L
    var stationaryBoundMs = 0L
    var stationaryBackgroundMs = 0L
    var otherwiseMs = 0L
    val sortedStopSamples = requestStopSamples.sorted()

    for (index in samples.indices) {
        val current = samples[index]
        val nextSampleAtMs =
            if (index < samples.lastIndex) {
                samples[index + 1].atEpochMs
            } else {
                captureWindowEndEpochMs ?: current.atEpochMs
            }
        val nextAtMs =
            firstRequestStopBetween(
                sortedStopSamples = sortedStopSamples,
                currentAtMs = current.atEpochMs,
                nextSampleAtMs = nextSampleAtMs,
            ) ?: nextSampleAtMs
        val deltaMs = (nextAtMs - current.atEpochMs).coerceAtLeast(0L)
        when (current.mode) {
            LocationRequestMode.BURST -> burstMs += deltaMs
            LocationRequestMode.STATIONARY_BOUND -> stationaryBoundMs += deltaMs
            LocationRequestMode.STATIONARY_BACKGROUND -> stationaryBackgroundMs += deltaMs
            LocationRequestMode.OTHERWISE -> otherwiseMs += deltaMs
        }
    }

    return ModeDurations(
        burstMs = burstMs,
        stationaryBoundMs = stationaryBoundMs,
        stationaryBackgroundMs = stationaryBackgroundMs,
        otherwiseMs = otherwiseMs,
        coverageMs = burstMs + stationaryBoundMs + stationaryBackgroundMs + otherwiseMs,
    )
}

private fun accumulateBackendDurations(
    samples: List<BackendSample>,
    requestStopSamples: List<Long>,
    captureWindowEndEpochMs: Long?,
): BackendDurations {
    if (samples.isEmpty()) {
        return BackendDurations(
            autoFusedMs = 0L,
            passiveExternalMs = 0L,
            watchGpsMs = 0L,
            coverageMs = 0L,
            switchCount = 0,
        )
    }

    var autoFusedMs = 0L
    var passiveExternalMs = 0L
    var watchGpsMs = 0L
    var switchCount = 0
    val sortedStopSamples = requestStopSamples.sorted()

    for (index in samples.indices) {
        val current = samples[index]
        if (index > 0 && samples[index - 1].backend != current.backend) {
            switchCount += 1
        }
        val nextSampleAtMs =
            if (index < samples.lastIndex) {
                samples[index + 1].atEpochMs
            } else {
                captureWindowEndEpochMs ?: current.atEpochMs
            }
        val nextAtMs =
            firstRequestStopBetween(
                sortedStopSamples = sortedStopSamples,
                currentAtMs = current.atEpochMs,
                nextSampleAtMs = nextSampleAtMs,
            ) ?: nextSampleAtMs
        val deltaMs = (nextAtMs - current.atEpochMs).coerceAtLeast(0L)
        when (current.backend) {
            RequestBackendMode.AUTO_FUSED -> autoFusedMs += deltaMs
            RequestBackendMode.PASSIVE_EXTERNAL -> passiveExternalMs += deltaMs
            RequestBackendMode.WATCH_GPS -> watchGpsMs += deltaMs
        }
    }

    return BackendDurations(
        autoFusedMs = autoFusedMs,
        passiveExternalMs = passiveExternalMs,
        watchGpsMs = watchGpsMs,
        coverageMs = autoFusedMs + passiveExternalMs + watchGpsMs,
        switchCount = switchCount,
    )
}

private fun firstRequestStopBetween(
    sortedStopSamples: List<Long>,
    currentAtMs: Long,
    nextSampleAtMs: Long,
): Long? =
    sortedStopSamples.firstOrNull { stopAtMs ->
        stopAtMs > currentAtMs && stopAtMs < nextSampleAtMs
    }
