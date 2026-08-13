package com.glancemap.glancemapwearos.core.service.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.core.service.diagnostics.export.deriveBundleDownloadTelemetrySummary
import com.glancemap.glancemapwearos.core.service.diagnostics.export.deriveCompassHeadingTelemetrySummary
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeBundleDownloadSummarySection
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeCompassDeepTraceSection
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeCompassHeadingTelemetrySummary
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeDemDownloadSections
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeEnergyByModeSummarySection
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeGnssSections
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeLineDumpSection
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeScreenStateSummarySection
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.maps.MapRenderer
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionMetricSummary
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionMode
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionTelemetry
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val threadtimeLineRegex =
    Regex(
        "^(\\d{2})-(\\d{2}) (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})(\\s+.*)$",
    )
private val threadtimeTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val normalizedLogcatTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

data class DiagnosticsSettingsSnapshot(
    val gpsIntervalMs: Long,
    val watchGpsOnly: Boolean,
    val keepAppOpen: Boolean,
    val gpsInAmbientMode: Boolean,
    val gpsDebugTelemetry: Boolean,
    val diagnosticsCaptureMode: String = SettingsRepository.DEFAULT_DIAGNOSTICS_CAPTURE_MODE,
    val gpsPassiveLocationExperiment: Boolean,
    val backButtonExitsNavigation: Boolean,
    val recordingSampleIntervalSeconds: Int = 0,
    val recordingScreenOffSampleIntervalSeconds: Int = SettingsRepository.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS,
    val recordingAutoPauseMode: String = SettingsRepository.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
    val recordingTrackSmoothingMode: String = SettingsRepository.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
    val recordingElevationSource: String = "na",
    val recordingHeartRateSource: String = "na",
    val recordingCadenceSource: String = "na",
    val recordingSpeedSource: String = "na",
    val recordingDistanceSource: String = "na",
    val recordingStepsSource: String = "na",
    val recordingShowSavedGpxOnMap: Boolean = true,
    val recordingStartWithTurnByTurn: Boolean = false,
    val recordingExternalHeartRateLinked: Boolean = false,
    val recordingExternalHeartRateName: String? = null,
    val recordingExternalHeartRateAddressSuffix: String? = null,
    val recordingExternalRunPodLinked: Boolean = false,
    val recordingExternalRunPodName: String? = null,
    val recordingExternalRunPodAddressSuffix: String? = null,
    val activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    val userWeightKg: Float = 0f,
    val backpackWeightKg: Float = 0f,
    val bikeWeightKg: Float = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG,
    val turnByTurnGuidanceSource: String = "na",
    val turnByTurnGpsIntervalSeconds: Int = SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS,
    val turnByTurnScreenOffGpsIntervalSeconds: Int = SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS,
    val turnByTurnHapticsEnabled: Boolean = true,
    val turnByTurnVoiceGuidanceEnabled: Boolean = false,
    val turnByTurnTurnAlertsMode: String = "na",
    val turnByTurnOffRouteAlertsEnabled: Boolean = true,
    val turnByTurnCompactPopupEnabled: Boolean = SettingsRepository.DEFAULT_TURN_BY_TURN_COMPACT_POPUP_ENABLED,
    val turnByTurnOffRouteAlertThresholdMeters: Int = 0,
    val turnByTurnOffRouteRepeatSeconds: Int = 0,
    val turnByTurnGpsInAmbientMode: Boolean = SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_IN_AMBIENT_MODE,
    val turnByTurnScreenOffBatchingEnabled: Boolean =
        SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED,
    val turnByTurnBrouterGuideBackEnabled: Boolean = false,
    val turnByTurnRouteStartBehavior: String = "na",
    val turnByTurnReverseSuggestionMode: String = "na",
)

@Suppress("TooManyFunctions")
object DiagnosticsExporter {
    private const val SESSION_DURATION_MISMATCH_THRESHOLD_MS = 5_000L

    internal data class FixGapBuckets(
        val interactiveSampleCount: Int = 0,
        val interactiveAvgMs: Long? = null,
        val interactiveMaxMs: Long = 0L,
        val nonInteractiveSampleCount: Int = 0,
        val nonInteractiveAvgMs: Long? = null,
        val nonInteractiveMaxMs: Long = 0L,
        val unknownScreenSampleCount: Int = 0,
        val unknownScreenAvgMs: Long? = null,
        val unknownScreenMaxMs: Long = 0L,
        val delayedCount: Int = 0,
    )

    internal data class RecordingTrackFilterInsights(
        val smoothingMode: String? = null,
        val filterVersion: Int? = null,
        val qualityHeldFixCount: Int? = null,
        val qualityRejectedFixCount: Int? = null,
        val qualityRelocationCount: Int? = null,
        val smoothedPointCount: Int? = null,
        val smoothedAdjustmentMeters: String? = null,
        val maxSmoothedAdjustmentMeters: String? = null,
        val smartTrack: RecordingSmartTrackInsights = RecordingSmartTrackInsights(),
    )

    internal data class RecordingSmartTrackInsights(
        val motionEvaluatedFixCount: Int? = null,
        val acceptedReportedSpeedCount: Int? = null,
        val acceptedSensorCount: Int? = null,
        val acceptedConfirmedSlowCount: Int? = null,
        val suppressedStationaryCount: Int? = null,
        val heldSlowCount: Int? = null,
        val segmentStartBypassCount: Int? = null,
        val stepMotionEvidenceCount: Int? = null,
        val cadenceMotionEvidenceCount: Int? = null,
        val speedAboveThresholdCount: Int? = null,
        val credibleSpeedCount: Int? = null,
        val noMotionSensorDataCount: Int? = null,
        val stationaryRadiusSampleCount: Int? = null,
        val stationaryRadiusAvgMeters: String? = null,
        val stationaryRadiusMaxMeters: String? = null,
        val nonAcceptedDisplacementSampleCount: Int? = null,
        val nonAcceptedDisplacementAvgMeters: String? = null,
        val nonAcceptedDisplacementMaxMeters: String? = null,
        val poorAccuracyRejectedCount: Int? = null,
        val nonMonotonicRejectedCount: Int? = null,
        val implausibleJumpHeldCount: Int? = null,
        val confirmedSustainedMovementCount: Int? = null,
        val adaptiveAccuracyFixCount: Int? = null,
        val accuracyBaselineSampleCount: Int? = null,
        val accuracyBaselineMedianMeters: String? = null,
        val accuracyProfileLimitMeters: String? = null,
        val accuracyResolvedLimitMeters: String? = null,
        val adaptiveAccuracyLimitActive: Boolean? = null,
    )

    internal data class TelemetryInsights(
        val burstStartCount: Int = 0,
        val burstEndCount: Int = 0,
        val availabilityTrueCount: Int = 0,
        val availabilityFalseCount: Int = 0,
        val availabilityInferredFromFixCount: Int = 0,
        val screenResumeCount: Int = 0,
        val screenPauseCount: Int = 0,
        val ambientEnterCount: Int = 0,
        val ambientExitCount: Int = 0,
        val trackingEnabledTrueCount: Int = 0,
        val trackingEnabledFalseCount: Int = 0,
        val trackingDisabledByScreenPauseCount: Int = 0,
        val requestAppliedCount: Int = 0,
        val requestModeBurstCount: Int = 0,
        val requestModeStationaryBoundCount: Int = 0,
        val requestModeStationaryBackgroundCount: Int = 0,
        val requestModeOtherwiseCount: Int = 0,
        val requestModeBurstDurationMs: Long = 0L,
        val requestModeStationaryBoundDurationMs: Long = 0L,
        val requestModeStationaryBackgroundDurationMs: Long = 0L,
        val requestModeOtherwiseDurationMs: Long = 0L,
        val requestModeDurationCoverageMs: Long = 0L,
        val lastObservedBound: Boolean? = null,
        val lastObservedTrackingEnabled: Boolean? = null,
        val lastObservedKeepOpen: Boolean? = null,
        val startupBogusSampleIgnoredCount: Int = 0,
        val staleFixDropCount: Int = 0,
        val sourceMismatchDropCount: Int = 0,
        val immediateRequestGuardSkipCount: Int = 0,
        val immediateRequestDeferredWakeBurstCount: Int = 0,
        val gpsFreshTrueCount: Int = 0,
        val gpsFreshFalseCount: Int = 0,
        val passiveExternalSignalSampleCount: Int = 0,
        val passiveExternalFreshSampleCount: Int = 0,
        val passiveExternalStaleSampleCount: Int = 0,
        val passiveExternalAcceptedSampleCount: Int = 0,
        val passiveExternalRejectedSampleCount: Int = 0,
        val passiveExternalLastAgeMs: Long? = null,
        val passiveExternalMinAgeMs: Long? = null,
        val passiveExternalMaxAgeMs: Long? = null,
        val passiveExternalLastMaxAgeMs: Long? = null,
        val passiveExternalLastAccuracyM: Float? = null,
        val passiveExternalLastProvider: String? = null,
        val watchGpsDegradedEnteredCount: Int = 0,
        val watchGpsDegradedClearedCount: Int = 0,
        val watchGpsDegradedSampleCount: Int = 0,
        val watchGpsDegradedLastObserved: Boolean? = null,
        val batchEventCount: Int = 0,
        val batchOriginAutoFusedCount: Int = 0,
        val batchOriginPassiveExternalCount: Int = 0,
        val batchOriginWatchGpsCount: Int = 0,
        val batchFallbackCount: Int = 0,
        val batchDuplicateCandidatesDroppedTotal: Int = 0,
        val batchRawCandidatesTotal: Int = 0,
        val batchNormalizedCandidatesTotal: Int = 0,
        val batchAcceptedCandidatesTotal: Int = 0,
        val batchRawCandidatesMax: Int = 0,
        val batchNormalizedCandidatesMax: Int = 0,
        val callbackAcceptedFixCount: Int = 0,
        val immediateAcceptedFixCount: Int = 0,
        val acceptedFixOriginAutoFusedCount: Int = 0,
        val acceptedFixOriginPassiveExternalCount: Int = 0,
        val acceptedFixOriginWatchGpsCount: Int = 0,
        val requestBackendAutoFusedCount: Int = 0,
        val requestBackendPassiveExternalCount: Int = 0,
        val requestBackendWatchGpsCount: Int = 0,
        val requestBackendSwitchCount: Int = 0,
        val requestBackendAutoFusedDurationMs: Long = 0L,
        val requestBackendPassiveExternalDurationMs: Long = 0L,
        val requestBackendWatchGpsDurationMs: Long = 0L,
        val requestBackendDurationCoverageMs: Long = 0L,
        val failoverAutoToWatchAccuracyCount: Int = 0,
        val failoverAutoToWatchNoFixCount: Int = 0,
        val failoverWatchToAutoCount: Int = 0,
        val failoverClearedTrackingDisabledCount: Int = 0,
        val failoverClearedOtherCount: Int = 0,
        val fixProviderGpsCount: Int = 0,
        val fixProviderFusedCount: Int = 0,
        val screenOnFixGapSampleCount: Int = 0,
        val screenOnFixGapAvgMs: Long? = null,
        val screenOnFixGapMaxMs: Long = 0L,
        val turnByTurnSampleCount: Int = 0,
        val turnByTurnActiveSampleCount: Int = 0,
        val turnByTurnPausedSampleCount: Int = 0,
        val turnByTurnOffRouteSampleCount: Int = 0,
        val turnByTurnGuideBackActiveSampleCount: Int = 0,
        val turnByTurnGuideBackPromptSampleCount: Int = 0,
        val turnByTurnStartHereDecisionCount: Int = 0,
        val turnByTurnReverseDecisionCount: Int = 0,
        val turnByTurnGeometryInstructionSampleCount: Int = 0,
        val turnByTurnBrouterHintInstructionSampleCount: Int = 0,
        val turnByTurnTurnHapticCount: Int = 0,
        val turnByTurnOffRouteHapticCount: Int = 0,
        val turnByTurnMaxDistanceToRouteMeters: Int? = null,
        val recordingStartCount: Int = 0,
        val recordingRecoveredCount: Int = 0,
        val recordingPauseCount: Int = 0,
        val recordingResumeCount: Int = 0,
        val recordingPointSampleCount: Int = 0,
        val recordingSaveStartCount: Int = 0,
        val recordingSaveSuccessCount: Int = 0,
        val recordingSaveFailureCount: Int = 0,
        val recordingDiscardCount: Int = 0,
        val recordingDraftFailureCount: Int = 0,
        val recordingDraftClearFailureCount: Int = 0,
        val recordingLastUiAction: String? = null,
        val recordingLastEndReason: String? = null,
        val recordingLastPointCount: Int? = null,
        val recordingMaxPointCount: Int? = null,
        val recordingLastDistanceMeters: Int? = null,
        val recordingMaxDistanceMeters: Int? = null,
        val recordingGpsDistanceMeters: Int? = null,
        val recordingDisplayDistanceMeters: Int? = null,
        val recordingPodSessionDistanceMeters: Int? = null,
        val recordingPodIntegratedDistanceMeters: Int? = null,
        val recordingSavedGpxWrittenPoints: Int? = null,
        val recordingSavedGpxParsedPoints: Int? = null,
        val recordingSavedGpxSummaryPoints: Int? = null,
        val recordingSavedGpxSummaryDistanceMeters: Int? = null,
        val recordingLastDurationMs: Long? = null,
        val recordingMaxDurationMs: Long? = null,
        val recordingLastPausedMs: Long? = null,
        val recordingMaxPausedMs: Long? = null,
        val recordingGpsActiveDurationMs: Long? = null,
        val recordingExpectedPointCount: Int? = null,
        val recordingAveragePointIntervalMs: Long? = null,
        val recordingPointCaptureRatePercent: Int? = null,
        val recordingGapCount: Int? = null,
        val recordingGapEventCount: Int = 0,
        val recordingMaxGapMs: Long? = null,
        val recordingLastPointAgeMs: Long? = null,
        val recordingForcedAcceptCount: Int? = null,
        val recordingGapRecoveryAcceptCount: Int? = null,
        val recordingLastSkippedIntervalElapsedMs: Long? = null,
        val recordingMaxSkippedIntervalElapsedMs: Long? = null,
        val recordingLastLiveProvider: String? = null,
        val recordingLastLiveAccuracyMeters: Int? = null,
        val recordingSkippedIntervalCount: Int? = null,
        val recordingSkippedPausedCount: Int? = null,
        val recordingSkippedUnusableCount: Int? = null,
        val recordingElevationSource: String? = null,
        val recordingDemHitCount: Int? = null,
        val recordingDemMissCount: Int? = null,
        val recordingGpsElevationUsedCount: Int? = null,
        val recordingAccuracySampleCount: Int? = null,
        val recordingAccuracyAvgMeters: Int? = null,
        val recordingAccuracyMinMeters: Int? = null,
        val recordingAccuracyMaxMeters: Int? = null,
        val recordingElevationGainMeters: Int? = null,
        val recordingElevationLossMeters: Int? = null,
        val recordingLiveHeartRateBpm: Int? = null,
        val recordingAverageHeartRateBpm: Int? = null,
        val recordingLiveStepCount: Int? = null,
        val recordingLiveCadenceSpm: Int? = null,
        val recordingLiveExternalSpeedMps: String? = null,
        val recordingLiveExternalRawDistanceUnits: Long? = null,
        val recordingLiveExternalDistanceMeters: Int? = null,
        val recordingLiveExternalSessionDistanceMeters: Int? = null,
        val recordingLiveExternalIntegratedDistanceMeters: Int? = null,
        val recordingLivePressureHpa: Int? = null,
        val recordingHeartRateAgeMs: Long? = null,
        val recordingStepCountAgeMs: Long? = null,
        val recordingCadenceAgeMs: Long? = null,
        val recordingExternalSpeedAgeMs: Long? = null,
        val recordingExternalDistanceAgeMs: Long? = null,
        val recordingPressureAgeMs: Long? = null,
        val recordingHeartRateSensorEventCount: Int? = null,
        val recordingStepSensorEventCount: Int? = null,
        val recordingCadenceSensorEventCount: Int? = null,
        val recordingPressureSensorEventCount: Int? = null,
        val recordingSensorRegisterCount: Int = 0,
        val recordingSensorRequested: String? = null,
        val recordingSensorRegistered: String? = null,
        val recordingSensorAvailable: String? = null,
        val recordingBodySensorsGranted: Boolean? = null,
        val recordingActivityRecognitionGranted: Boolean? = null,
        val externalSensorScanStartCount: Int = 0,
        val externalSensorScanStopCount: Int = 0,
        val externalSensorScanFailedCount: Int = 0,
        val externalSensorLastScanDeviceCount: Int? = null,
        val externalSensorLastScanSummary: String? = null,
        val externalSensorLastScanFailureReason: String? = null,
        val externalHeartRateBridgeStartCount: Int = 0,
        val externalHeartRateBridgeStopCount: Int = 0,
        val externalHeartRateConnectRequestedCount: Int = 0,
        val externalHeartRateConnectSkippedCount: Int = 0,
        val externalHeartRateLastConnectSkippedReason: String? = null,
        val externalHeartRateConnectedCount: Int = 0,
        val externalHeartRateDisconnectedCount: Int = 0,
        val externalHeartRateNotifyRequestedCount: Int = 0,
        val externalHeartRateNotifyFailedCount: Int = 0,
        val externalHeartRateServiceFailureCount: Int = 0,
        val externalHeartRateMeasurementMissingCount: Int = 0,
        val externalHeartRateSampleCount: Int = 0,
        val externalHeartRateLastBpm: Int? = null,
        val externalHeartRateMinBpm: Int? = null,
        val externalHeartRateMaxBpm: Int? = null,
        val externalHeartRateLastSampleAgeMs: Long? = null,
        val externalRunPodBridgeStartCount: Int = 0,
        val externalRunPodBridgeStopCount: Int = 0,
        val externalRunPodConnectRequestedCount: Int = 0,
        val externalRunPodConnectSkippedCount: Int = 0,
        val externalRunPodLastConnectSkippedReason: String? = null,
        val externalRunPodConnectedCount: Int = 0,
        val externalRunPodDisconnectedCount: Int = 0,
        val externalRunPodNotifyRequestedCount: Int = 0,
        val externalRunPodNotifyFailedCount: Int = 0,
        val externalRunPodServiceFailureCount: Int = 0,
        val externalRunPodMeasurementMissingCount: Int = 0,
        val externalRunPodSampleCount: Int = 0,
        val externalRunPodLastCadenceSpm: Int? = null,
        val externalRunPodLastPowerWatts: Int? = null,
        val externalRunPodLastBatteryPercent: Int? = null,
        val externalRunPodLastSpeedMps: String? = null,
        val externalRunPodLastRawDistanceUnits: Long? = null,
        val externalRunPodLastDistanceMeters: Int? = null,
        val externalRunPodLastSampleAgeMs: Long? = null,
        val recordingCalorieModel: String? = null,
        val recordingCaloriesGrossKcal: Int? = null,
        val recordingCaloriesActiveKcal: Int? = null,
        val recordingCaloriesRestingKcal: Int? = null,
        val recordingPandolfBaseGrossKcal: Int? = null,
        val recordingPandolfBaseActiveKcal: Int? = null,
        val recordingPandolfBaseRestingKcal: Int? = null,
        val recordingCalorieCompareModel: String? = null,
        val recordingLcdaGrossKcal: Int? = null,
        val recordingLcdaActiveKcal: Int? = null,
        val recordingLcdaRestingKcal: Int? = null,
        val recordingLastSavedByteSize: Int? = null,
        val locationServiceStartFailureCount: Int = 0,
        val locationServiceStartFallbackFailureCount: Int = 0,
        val locationStartForegroundFailureCount: Int = 0,
        val thermalStatusEventCount: Int = 0,
        val thermalMaxStatus: Int? = null,
        val thermalLastStatusLabel: String? = null,
    ) {
        var watchGpsSelfHealSkippedCount: Int = 0
        var watchGpsSelfHealRestartCount: Int = 0
        var watchGpsSelfHealMaxSearchAgeMs: Long? = null
        var fixGapBuckets: FixGapBuckets = FixGapBuckets()
        var externalHeartRateReconnectScheduledCount: Int = 0
        var externalHeartRateReconnectAttemptCount: Int = 0
        var turnByTurnTurnAlertFiredCount: Int = 0
        var turnByTurnTurnAlertFilteredCount: Int = 0
        var turnByTurnTurnAlertOffRouteCount: Int = 0
        var turnByTurnTurnAlertMissedWindowCount: Int = 0
        var recordingTrackFilter: RecordingTrackFilterInsights = RecordingTrackFilterInsights()
        var recordingGapEndpointDistanceSampleCount: Int = 0
        var recordingGapEndpointDistanceAvgMeters: Float? = null
        var recordingGapEndpointDistanceMaxMeters: Float? = null
    }

    internal data class CompassTelemetryInsights(
        val managerStartCount: Int = 0,
        val managerStopScheduledCount: Int = 0,
        val managerStopRequestedCount: Int = 0,
        val rotationSettleSessionStartCount: Int = 0,
        val rotationSettleHoldCount: Int = 0,
        val rotationSettleUnlockCount: Int = 0,
        val rotationSettleHoldReasons: String = "none",
        val rotationSettleUnlockReasons: String = "none",
        val rotationSettleHoldMaxHeadingDeltaDeg: Float? = null,
        val headingSampleCount: Int = 0,
        val headingDiagnosticSampleCount: Int = 0,
        val largeJumpPendingCount: Int = 0,
        val largeJumpAcceptedCount: Int = 0,
        val staleSampleCount: Int = 0,
        val largeJumpWithinManagerStart500MsCount: Int = 0,
        val sampleAfterStopScheduledCount: Int = 0,
        val sampleAfterStopRequestedCount: Int = 0,
        val startupSummaryCount: Int = 0,
        val startupHeadingSpanMaxDeg: Float? = null,
        val startupMaxJumpMaxDeg: Float? = null,
        val startupVisibleHeadingJumpMaxDeg: Float? = null,
        val startupVisibleMapRotationJumpMaxDeg: Float? = null,
        val startupSourceHandoffCount: Int = 0,
        val startupSourceHandoffMaxJumpDeg: Float? = null,
        val startupStable3Count: Int = 0,
        val startupStable5Count: Int = 0,
        val fusedFirstUsableCount: Int = 0,
        val fusedFirstUsableLatencyMaxMs: Long? = null,
        val fusedReadyCount: Int = 0,
        val fusedReadyLatencyMaxMs: Long? = null,
        val fusedWarmupRelockCount: Int = 0,
        val fusedWarmupRelockStepMaxDeg: Float? = null,
        val fusedReadyAfterRelockCount: Int = 0,
        val fusedFallbackActivationCount: Int = 0,
        val continuityStartCount: Int = 0,
        val continuityCompleteCount: Int = 0,
        val continuityCancelCount: Int = 0,
        val continuityInitialOffsetMaxDeg: Float? = null,
        val continuityDurationMaxMs: Long? = null,
        val headingLooksWrongReportCount: Int = 0,
        val fusedPerfEventCount: Int = 0,
        val fusedPerfCallbackCount: Int = 0,
        val fusedPerfConfirmedCount: Int = 0,
        val fusedPerfUnusableCount: Int = 0,
        val fusedPerfHeadingPublishCount: Int = 0,
        val fusedPerfCallbackHzMax: Float? = null,
        val fusedPerfPublishHzMax: Float? = null,
        val renderPerfEventCount: Int = 0,
        val renderPerfFrameCount: Int = 0,
        val renderPerfTargetUpdateCount: Int = 0,
        val renderPerfHeadingRenderCount: Int = 0,
        val renderPerfRotationAppliedCount: Int = 0,
        val renderPerfRotationSkippedCount: Int = 0,
        val renderPerfRotationThrottledCount: Int = 0,
        val renderPerfMarkerUpdateCount: Int = 0,
        val renderPerfRedrawCount: Int = 0,
        val renderPerfFrameHzMax: Float? = null,
        val renderPerfRenderHzMax: Float? = null,
    )

    internal data class GnssInsights(
        val statusSampleCount: Int = 0,
        val startedCount: Int = 0,
        val stoppedCount: Int = 0,
        val firstFixCount: Int = 0,
        val firstFixTtffAvgMs: Long = 0L,
        val firstFixTtffMinMs: Int = 0,
        val firstFixTtffMaxMs: Int = 0,
        val satellitesAvg: Double = 0.0,
        val satellitesMax: Int = 0,
        val usedInFixAvg: Double = 0.0,
        val usedInFixMax: Int = 0,
        val signalSatellitesAvg: Double = 0.0,
        val signalSatellitesMax: Int = 0,
        val almanacSatellitesAvg: Double = 0.0,
        val almanacSatellitesMax: Int = 0,
        val ephemerisSatellitesAvg: Double = 0.0,
        val ephemerisSatellitesMax: Int = 0,
        val noRadioSignalStatusCount: Int = 0,
        val signalsNoEphemerisStatusCount: Int = 0,
        val ephemerisNoFixStatusCount: Int = 0,
        val satellitesUsedStatusCount: Int = 0,
        val acquisitionSignalDetectedCount: Int = 0,
        val acquisitionEphemerisAvailableCount: Int = 0,
        val acquisitionSatellitesUsedCount: Int = 0,
        val cn0AvgDbHz: Double? = null,
        val cn0MaxDbHz: Float? = null,
        val carrierFrequencyStatusCount: Int = 0,
        val l1ObservedStatusCount: Int = 0,
        val l5ObservedStatusCount: Int = 0,
        val dualBandObservedStatusCount: Int = 0,
        val l1SatelliteMax: Int = 0,
        val l5SatelliteMax: Int = 0,
        val collectorRegisteredCount: Int = 0,
        val collectorUnregisteredCount: Int = 0,
        val collectorInactiveCount: Int = 0,
        val collectorPolicyDisabledCount: Int = 0,
        val usedZeroWithFreshLocationCount: Int = 0,
        val signalsWithoutFreshLocationCount: Int = 0,
    )

    internal data class AcceptedFixSummary(
        val acceptedFixCount: Int = 0,
        val callbackFixCount: Int = 0,
        val immediateFixCount: Int = 0,
        val providerGpsCount: Int = 0,
        val providerFusedCount: Int = 0,
        val reportedAccuracyMedianM: Float? = null,
        val reportedAccuracyP90M: Float? = null,
        val reportedAccuracyMinM: Float? = null,
        val reportedAccuracyMaxM: Float? = null,
        val reportedAccuracyDistinctCount: Int = 0,
        val reportedAccuracyAllSame: Boolean = false,
        val ageMedianMs: Long? = null,
        val ageP90Ms: Long? = null,
        val ageMaxMs: Long? = null,
    )

    internal data class AcceptedFixSummaries(
        val overall: AcceptedFixSummary = AcceptedFixSummary(),
        val autoFused: AcceptedFixSummary = AcceptedFixSummary(),
        val watchGps: AcceptedFixSummary = AcceptedFixSummary(),
    )

    internal data class ObservedFixQualitySummary(
        val quality: String = "unknown",
        val confidence: String = "low",
        val reportedAccuracyReliability: String = "unknown",
        val reason: String = "no accepted fixes",
    )

    private val filenameFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

    fun export(
        context: Context,
        settings: DiagnosticsSettingsSnapshot,
        reuseLatestIfAvailable: Boolean = false,
    ): File {
        val now = Instant.now()
        val dir = diagnosticsDir(context)
        if (reuseLatestIfAvailable) {
            latestExportFile(context)?.let { return it }
        }

        val allTelemetryLines = DebugTelemetry.snapshot()
        val captureSession = DebugTelemetry.captureSessionSnapshot()
        val file =
            File(
                dir,
                buildString {
                    append("glancemap_diagnostics_")
                    append(filenameFormatter.format(now))
                    append('_')
                    append(buildDeviceSlug())
                    captureSession.sessionId.takeIf { it > 0L }?.let {
                        append("_s")
                        append(it)
                    }
                    append(".txt")
                },
            )
        val captureWindowEndEpochMs = resolveCaptureWindowEndEpochMs(captureSession, now.toEpochMilli())
        val telemetryWindow =
            toTelemetryWindow(
                lines = allTelemetryLines,
                startEpochMs = captureSession.startedAtMs,
                endEpochMs = captureWindowEndEpochMs,
            )
        val telemetryLines = telemetryWindow.lines
        val telemetryInsights =
            deriveTelemetryInsights(
                lines = telemetryLines,
                captureWindowEndEpochMs = captureWindowEndEpochMs,
            )
        val compassTelemetryInsights = deriveCompassTelemetryInsights(telemetryLines)
        val compassHeadingTelemetrySummary = deriveCompassHeadingTelemetrySummary(telemetryLines)
        val acceptedFixSummaries = deriveAcceptedFixSummaries(telemetryLines)
        val bundleDownloadSummary = deriveBundleDownloadTelemetrySummary(telemetryLines)
        val captureDurationMs =
            captureDurationMs(
                startedAtMs = captureSession.startedAtMs,
                endedAtMs = captureSession.endedAtMs,
                active = captureSession.active,
            )
        val bufferedSpanMs =
            bufferedSpanMs(
                firstBufferedAtMs = telemetryWindow.firstAtMs,
                lastBufferedAtMs = telemetryWindow.lastAtMs,
            )
        val sessionVsBufferedMismatch =
            captureDurationMs != null &&
                bufferedSpanMs != null &&
                !captureSession.active &&
                kotlin.math.abs(captureDurationMs - bufferedSpanMs) > SESSION_DURATION_MISMATCH_THRESHOLD_MS
        val energyLines = EnergyDiagnostics.snapshotLines()
        val energyDroppedLines = EnergyDiagnostics.droppedLineCount()
        val energySummary = EnergyDiagnostics.summary()
        val batteryBenchmarkValidity = EnergyDiagnostics.batteryBenchmarkValidity()
        val compassDeepTraceSnapshot = CompassDeepTraceDiagnostics.snapshot()
        val screenStateSummary = ScreenStateDiagnostics.summary()
        val demDownloadSummary = DemDownloadDiagnostics.summary()
        val demDownloadLines = DemDownloadDiagnostics.snapshotLines()
        val demDownloadDroppedLines = DemDownloadDiagnostics.droppedLineCount()
        val markerMotionSummary = MarkerMotionTelemetry.summary(android.os.SystemClock.elapsedRealtime())
        val markerMotionSnapshot = MarkerMotionTelemetry.latestSnapshot()
        val mapHotPathSummary = MapHotPathDiagnostics.summary()
        val mapHotPathLines = MapHotPathDiagnostics.snapshotLines()
        val mapHotPathDroppedLines = MapHotPathDiagnostics.droppedLineCount()
        val gnssLines = GnssDiagnostics.snapshotLines()
        val gnssDroppedLines = GnssDiagnostics.droppedLineCount()
        val gnssInsights = deriveGnssInsights(gnssLines)
        val fieldMarkerLines = FieldMarkerDiagnostics.snapshotLines()
        val fieldMarkerDroppedLines = FieldMarkerDiagnostics.droppedLineCount()
        val telemetryTruncated = captureSession.droppedLines > 0
        val energyTruncated = energyDroppedLines > 0
        val demDownloadTruncated = demDownloadDroppedLines > 0
        val mapHotPathTruncated = mapHotPathDroppedLines > 0
        val gnssTruncated = gnssDroppedLines > 0
        val fieldMarkerTruncated = fieldMarkerDroppedLines > 0
        val lastCrash = CrashDiagnosticsStore.read(context)
        val logcatSnapshot = captureAppLogcat(capturedAt = now)
        val performanceSummary = summarizePerformanceFromLogcat(logcatSnapshot.lines)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appInfo = context.applicationInfo
        val sensorInventory = captureSensorInventory(context)
        val locationPermission = captureLocationPermissionSnapshot(context)
        val gpsCapability = captureGpsCapabilitySnapshot(context)
        val memorySnapshot = captureMemorySnapshot(context)
        val cacheSnapshot = MapRenderer.captureCacheDiagnostics(context)
        val historicalExitReasons = captureHistoricalProcessExitReasons(context)

        file.bufferedWriter().use { writer ->
            writer.appendLine("GlanceMap Wear Diagnostics")
            writer.appendLine("Generated: ${timestampFormatter.format(now)}")
            writer.appendLine()
            writer.appendLine("App")
            writer.appendLine("Package: ${context.packageName}")
            writer.appendLine("VersionName: ${packageInfo.versionName}")
            writer.appendLine("VersionCode: ${packageInfo.longVersionCode}")
            writer.appendLine("BuildType: ${BuildConfig.BUILD_TYPE}")
            writer.appendLine("Debuggable: ${(appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0}")
            writer.appendLine("GitSha: ${BuildConfig.GIT_SHA}")
            writer.appendLine("GitBranch: ${BuildConfig.GIT_BRANCH}")
            writer.appendLine(
                "MappingIdentity: version=${packageInfo.versionName} code=${packageInfo.longVersionCode} " +
                    "buildType=${BuildConfig.BUILD_TYPE} git=${BuildConfig.GIT_SHA}",
            )
            writer.appendLine("TargetSdk: ${appInfo.targetSdkVersion}")
            writer.appendLine("FirstInstall: ${formatInstallTime(packageInfo.firstInstallTime)}")
            writer.appendLine("LastUpdate: ${formatInstallTime(packageInfo.lastUpdateTime)}")
            writer.appendLine()
            writer.appendLine("Device")
            writer.appendLine("Manufacturer: ${Build.MANUFACTURER}")
            writer.appendLine("Model: ${Build.MODEL}")
            writer.appendLine("SDK: ${Build.VERSION.SDK_INT}")
            writer.appendLine()
            writer.appendLine("Memory Snapshot")
            writer.appendLine("runtimeMaxHeapMb=${formatBytesToMb(memorySnapshot.runtimeMaxHeapBytes)}")
            writer.appendLine("runtimeTotalHeapMb=${formatBytesToMb(memorySnapshot.runtimeTotalHeapBytes)}")
            writer.appendLine("runtimeUsedHeapMb=${formatBytesToMb(memorySnapshot.runtimeUsedHeapBytes)}")
            writer.appendLine("runtimeFreeHeapMb=${formatBytesToMb(memorySnapshot.runtimeFreeHeapBytes)}")
            writer.appendLine("nativeHeapSizeMb=${formatBytesToMb(memorySnapshot.nativeHeapSizeBytes)}")
            writer.appendLine("nativeHeapAllocatedMb=${formatBytesToMb(memorySnapshot.nativeHeapAllocatedBytes)}")
            writer.appendLine("nativeHeapFreeMb=${formatBytesToMb(memorySnapshot.nativeHeapFreeBytes)}")
            writer.appendLine("memoryClassMb=${memorySnapshot.memoryClassMb?.toString() ?: "na"}")
            writer.appendLine("largeMemoryClassMb=${memorySnapshot.largeMemoryClassMb?.toString() ?: "na"}")
            writer.appendLine("largeHeapRequested=${memorySnapshot.largeHeapRequested}")
            writer.appendLine("processTotalPssKb=${memorySnapshot.totalPssKb?.toString() ?: "na"}")
            writer.appendLine("processDalvikPssKb=${memorySnapshot.dalvikPssKb?.toString() ?: "na"}")
            writer.appendLine("processNativePssKb=${memorySnapshot.nativePssKb?.toString() ?: "na"}")
            writer.appendLine("processOtherPssKb=${memorySnapshot.otherPssKb?.toString() ?: "na"}")
            writer.appendLine("processTotalPrivateDirtyKb=${memorySnapshot.totalPrivateDirtyKb?.toString() ?: "na"}")
            writer.appendLine("processTotalSharedDirtyKb=${memorySnapshot.totalSharedDirtyKb?.toString() ?: "na"}")
            writer.appendLine("systemAvailMemMb=${formatNullableBytesToMb(memorySnapshot.availMemBytes)}")
            writer.appendLine("systemTotalMemMb=${formatNullableBytesToMb(memorySnapshot.totalMemBytes)}")
            writer.appendLine("systemLowMemory=${formatNullableBoolean(memorySnapshot.lowMemory)}")
            writer.appendLine("systemLowMemoryThresholdMb=${formatNullableBytesToMb(memorySnapshot.thresholdBytes)}")
            writer.appendLine()
            writer.appendLine("Cache Summary")
            writer.appendLine("activeTileCacheId=${cacheSnapshot.activeTileCacheId ?: "na"}")
            writer.appendLine("activeTileCacheLastUsedAt=${formatCaptureTime(cacheSnapshot.activeTileCacheLastUsedMs)}")
            writer.appendLine(
                "activeTileCacheLastUsedAgeMs=${
                    formatAgeMs(
                        nowMs = now.toEpochMilli(),
                        pastMs = cacheSnapshot.activeTileCacheLastUsedMs,
                    )
                }",
            )
            writer.appendLine("tileCacheBucketCount=${cacheSnapshot.tileCacheBucketCount}")
            writer.appendLine("tileCacheTotalSizeMb=${formatBytesToMb(cacheSnapshot.tileCacheTotalSizeBytes)}")
            writer.appendLine("activeTileCacheSizeMb=${formatNullableBytesToMb(cacheSnapshot.activeTileCacheSizeBytes)}")
            writer.appendLine("tileCacheSoftLimitMb=${formatBytesToMb(cacheSnapshot.tileCacheSoftLimitBytes)}")
            writer.appendLine("tileCacheTargetLimitMb=${formatBytesToMb(cacheSnapshot.tileCacheTargetLimitBytes)}")
            writer.appendLine("tileCacheMaxAgeMs=${cacheSnapshot.tileCacheMaxAgeMs}")
            writer.appendLine("tileCacheCleanupIntervalMs=${cacheSnapshot.tileCacheCleanupIntervalMs}")
            writer.appendLine("cacheLastCleanupAt=${formatCaptureTime(cacheSnapshot.lastCleanupMs)}")
            writer.appendLine(
                "cacheLastCleanupAgeMs=${
                    formatAgeMs(
                        nowMs = now.toEpochMilli(),
                        pastMs = cacheSnapshot.lastCleanupMs,
                    )
                }",
            )
            writer.appendLine("reliefOverlayNamespaceCount=${cacheSnapshot.reliefOverlayNamespaceCount}")
            writer.appendLine("reliefOverlayCacheSizeMb=${formatBytesToMb(cacheSnapshot.reliefOverlayCacheSizeBytes)}")
            writer.appendLine("bundledThemeCacheDirCount=${cacheSnapshot.bundledThemeCacheDirCount}")
            writer.appendLine("bundledThemeCacheTotalSizeMb=${formatBytesToMb(cacheSnapshot.bundledThemeCacheTotalSizeBytes)}")
            if (cacheSnapshot.tileCacheBuckets.isEmpty()) {
                writer.appendLine("tileCacheBuckets=none")
            } else {
                cacheSnapshot.tileCacheBuckets.forEachIndexed { index, bucket ->
                    writer.appendLine(
                        "tileCacheBucket[$index]=id=${bucket.id} active=${bucket.active} " +
                            "sizeMb=${formatBytesToMb(bucket.sizeBytes)} " +
                            "lastUsedAt=${formatCaptureTime(bucket.lastUsedMs)} " +
                            "lastUsedAgeMs=${formatAgeMs(now.toEpochMilli(), bucket.lastUsedMs)}",
                    )
                }
            }
            writer.appendLine()
            writer.appendLine("Sensor Inventory")
            writer.appendLine("headingPublicApiSupported=${sensorInventory.headingPublicApiSupported}")
            writer.appendLine("typeHeadingAvailable=${sensorInventory.headingAvailable}")
            writer.appendLine("rotationVectorAvailable=${sensorInventory.rotationVectorAvailable}")
            writer.appendLine("magnetometerAvailable=${sensorInventory.magnetometerAvailable}")
            writer.appendLine("accelerometerAvailable=${sensorInventory.accelerometerAvailable}")
            writer.appendLine("typeHeading=${formatSensorDescriptor(sensorInventory.headingSensor)}")
            writer.appendLine("rotationVector=${formatSensorDescriptor(sensorInventory.rotationVectorSensor)}")
            writer.appendLine("magnetometer=${formatSensorDescriptor(sensorInventory.magnetometerSensor)}")
            writer.appendLine("accelerometer=${formatSensorDescriptor(sensorInventory.accelerometerSensor)}")
            writer.appendLine("allSensorCount=${sensorInventory.allSensors.size}")
            if (sensorInventory.allSensors.isEmpty()) {
                writer.appendLine("No public sensors reported by SensorManager.")
            } else {
                sensorInventory.allSensors.forEachIndexed { index, sensor ->
                    writer.appendLine("sensor[$index]=${formatSensorDescriptor(sensor)}")
                }
            }
            writer.appendLine()
            writer.appendLine("GPS Settings")
            writer.appendLine("gpsIntervalMs=${settings.gpsIntervalMs}")
            writer.appendLine("watchGpsOnly=${settings.watchGpsOnly}")
            writer.appendLine("keepAppOpen=${settings.keepAppOpen}")
            writer.appendLine("gpsInAmbientMode=${settings.gpsInAmbientMode}")
            writer.appendLine("gpsDebugTelemetry=${settings.gpsDebugTelemetry}")
            writer.appendLine("diagnosticsCaptureMode=${settings.diagnosticsCaptureMode}")
            writer.appendLine("gpsPassiveLocationExperiment=${settings.gpsPassiveLocationExperiment}")
            writer.appendLine("backButtonExitsNavigation=${settings.backButtonExitsNavigation}")
            writer.appendLine("recordingSampleIntervalSeconds=${settings.recordingSampleIntervalSeconds}")
            writer.appendLine(
                "recordingScreenOffSampleIntervalSeconds=${settings.recordingScreenOffSampleIntervalSeconds}",
            )
            writer.appendLine("recordingAutoPauseMode=${settings.recordingAutoPauseMode}")
            writer.appendLine("recordingTrackSmoothingMode=${settings.recordingTrackSmoothingMode}")
            writer.appendLine("recordingElevationSource=${settings.recordingElevationSource}")
            writer.appendLine("recordingHeartRateSource=${settings.recordingHeartRateSource}")
            writer.appendLine("recordingCadenceSource=${settings.recordingCadenceSource}")
            writer.appendLine("recordingSpeedSource=${settings.recordingSpeedSource}")
            writer.appendLine("recordingDistanceSource=${settings.recordingDistanceSource}")
            writer.appendLine("recordingStepsSource=${settings.recordingStepsSource}")
            writer.appendLine("recordingShowSavedGpxOnMap=${settings.recordingShowSavedGpxOnMap}")
            writer.appendLine("recordingStartWithTurnByTurn=${settings.recordingStartWithTurnByTurn}")
            writer.appendLine("recordingExternalHeartRateLinked=${settings.recordingExternalHeartRateLinked}")
            writer.appendLine("recordingExternalHeartRateName=${settings.recordingExternalHeartRateName ?: "na"}")
            writer.appendLine(
                "recordingExternalHeartRateAddressSuffix=${
                    settings.recordingExternalHeartRateAddressSuffix ?: "na"
                }",
            )
            writer.appendLine("recordingExternalRunPodLinked=${settings.recordingExternalRunPodLinked}")
            writer.appendLine("recordingExternalRunPodName=${settings.recordingExternalRunPodName ?: "na"}")
            writer.appendLine(
                "recordingExternalRunPodAddressSuffix=${
                    settings.recordingExternalRunPodAddressSuffix ?: "na"
                }",
            )
            writer.appendLine("bluetoothScanPermissionGranted=${hasBluetoothScanPermission(context)}")
            writer.appendLine("bluetoothConnectPermissionGranted=${hasBluetoothConnectPermission(context)}")
            writer.appendLine("activityProfile=${settings.activityProfile}")
            writer.appendLine("userWeightKg=${settings.userWeightKg}")
            writer.appendLine("backpackWeightKg=${settings.backpackWeightKg}")
            writer.appendLine("bikeWeightKg=${settings.bikeWeightKg}")
            writer.appendLine("turnByTurnGuidanceSource=${settings.turnByTurnGuidanceSource}")
            writer.appendLine("turnByTurnGpsIntervalSeconds=${settings.turnByTurnGpsIntervalSeconds}")
            writer.appendLine(
                "turnByTurnScreenOffGpsIntervalSeconds=${settings.turnByTurnScreenOffGpsIntervalSeconds}",
            )
            writer.appendLine(
                "turnByTurnScreenOffGpsAdaptive=${
                    settings.turnByTurnScreenOffGpsIntervalSeconds ==
                        SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS
                }",
            )
            writer.appendLine("turnByTurnHapticsEnabled=${settings.turnByTurnHapticsEnabled}")
            writer.appendLine("turnByTurnVoiceGuidanceEnabled=${settings.turnByTurnVoiceGuidanceEnabled}")
            writer.appendLine("turnByTurnTurnAlertsMode=${settings.turnByTurnTurnAlertsMode}")
            writer.appendLine("turnByTurnOffRouteAlertsEnabled=${settings.turnByTurnOffRouteAlertsEnabled}")
            writer.appendLine("turnByTurnCompactPopupEnabled=${settings.turnByTurnCompactPopupEnabled}")
            writer.appendLine(
                "turnByTurnOffRouteAlertThresholdMeters=${settings.turnByTurnOffRouteAlertThresholdMeters}",
            )
            writer.appendLine("turnByTurnOffRouteRepeatSeconds=${settings.turnByTurnOffRouteRepeatSeconds}")
            writer.appendLine("turnByTurnGpsInAmbientMode=${settings.turnByTurnGpsInAmbientMode}")
            writer.appendLine(
                "turnByTurnScreenOffBatchingEnabled=${settings.turnByTurnScreenOffBatchingEnabled}",
            )
            writer.appendLine("turnByTurnBrouterGuideBackEnabled=${settings.turnByTurnBrouterGuideBackEnabled}")
            writer.appendLine("turnByTurnRouteStartBehavior=${settings.turnByTurnRouteStartBehavior}")
            writer.appendLine("turnByTurnReverseSuggestionMode=${settings.turnByTurnReverseSuggestionMode}")
            writer.appendLine("continuousLocationGranularity=permission_level")
            writer.appendLine("currentLocationGranularity=permission_level")
            writer.appendLine("locationFinePermissionGranted=${locationPermission.hasFinePermission}")
            writer.appendLine("locationCoarsePermissionGranted=${locationPermission.hasCoarsePermission}")
            writer.appendLine("locationPermissionMode=${locationPermission.mode}")
            writer.appendLine("gpsPositionFilterEnabled=true")
            writer.appendLine()
            writer.appendLine("Battery Benchmark Context")
            val batteryBenchmarkMode =
                settings.diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY
            writer.appendLine(
                "batteryBenchmarkValidity=${
                    when {
                        !batteryBenchmarkMode -> "NOT_APPLICABLE"
                        batteryBenchmarkValidity.valid -> "VALID"
                        else -> "INVALID"
                    }
                }",
            )
            writer.appendLine(
                "batteryBenchmarkInvalidReasons=${
                    batteryBenchmarkValidity.invalidReasons.ifEmpty { listOf("none") }.joinToString(",")
                }",
            )
            writer.appendLine("compassDeepTraceObserved=${compassDeepTraceSnapshot.sessionCount > 0}")
            writer.appendLine("compassDeepTraceAggregateWindows=${compassDeepTraceSnapshot.windowCount}")
            writer.appendLine("recordingSessionObserved=${recordingSessionObserved(telemetryInsights)}")
            writer.appendLine("recordingSessionMode=${recordingGuidanceSessionMode(telemetryInsights)}")
            writer.appendLine("externalSensorsObserved=${observedExternalSensors(telemetryInsights)}")
            writer.appendLine("externalHeartRateObserved=${telemetryInsights.externalHeartRateSampleCount > 0}")
            writer.appendLine("externalHeartRateSampleCount=${telemetryInsights.externalHeartRateSampleCount}")
            writer.appendLine("externalRunPodObserved=${telemetryInsights.externalRunPodSampleCount > 0}")
            writer.appendLine("externalRunPodSampleCount=${telemetryInsights.externalRunPodSampleCount}")
            writer.appendLine()
            writer.appendLine("GPS Capability")
            writer.appendLine("locationManagerAvailable=${gpsCapability.locationManagerAvailable}")
            writer.appendLine("systemLocationEnabled=${formatNullableBoolean(gpsCapability.systemLocationEnabled)}")
            writer.appendLine("watchGpsHardwareFeature=${formatNullableBoolean(gpsCapability.watchGpsHardwareFeature)}")
            writer.appendLine("gpsProviderPresent=${gpsCapability.gpsProviderPresent}")
            writer.appendLine("gpsProviderEnabled=${formatNullableBoolean(gpsCapability.gpsProviderEnabled)}")
            writer.appendLine("networkProviderPresent=${gpsCapability.networkProviderPresent}")
            writer.appendLine("networkProviderEnabled=${formatNullableBoolean(gpsCapability.networkProviderEnabled)}")
            writer.appendLine("passiveProviderPresent=${gpsCapability.passiveProviderPresent}")
            writer.appendLine("passiveProviderEnabled=${formatNullableBoolean(gpsCapability.passiveProviderEnabled)}")
            writer.appendLine("locationProviders=${formatStringList(gpsCapability.allProviders)}")
            writer.appendLine("connectedPhoneNodeCount=${gpsCapability.connectedPhoneNodeCount?.toString() ?: "na"}")
            writer.appendLine("connectedPhoneNodeCaptureError=${gpsCapability.connectedPhoneNodeCaptureError ?: "none"}")
            writer.appendLine("activeNetworkTransports=${formatStringList(gpsCapability.activeNetworkTransports)}")
            writer.appendLine("activeNetworkValidated=${formatNullableBoolean(gpsCapability.activeNetworkValidated)}")
            writer.appendLine(
                "activeNetworkInternetCapable=${formatNullableBoolean(gpsCapability.activeNetworkInternetCapable)}",
            )
            writer.appendLine()
            writer.appendLine("Capture Session")
            writer.appendLine("sessionId=${captureSessionIdText(captureSession.sessionId)}")
            writer.appendLine("activeAtExport=${captureSession.active}")
            writer.appendLine("startedAt=${formatCaptureTime(captureSession.startedAtMs)}")
            writer.appendLine("endedAt=${formatCaptureEndTime(captureSession.endedAtMs, captureSession.active)}")
            writer.appendLine(
                "durationMs=${
                    formatCaptureDurationMs(
                        startedAtMs = captureSession.startedAtMs,
                        endedAtMs = captureSession.endedAtMs,
                        active = captureSession.active,
                    )
                }",
            )
            writer.appendLine("telemetryTotalLoggedLines=${captureSession.totalLoggedLines}")
            writer.appendLine("telemetryBufferedLines=${telemetryLines.size}")
            writer.appendLine("telemetryBufferMaxLines=${DebugTelemetry.maxBufferedLines()}")
            writer.appendLine("telemetryDroppedLines=${captureSession.droppedLines}")
            writer.appendLine("telemetryTruncated=$telemetryTruncated")
            writer.appendLine("telemetryBufferedFirstAt=${formatCaptureTime(telemetryWindow.firstAtMs)}")
            writer.appendLine("telemetryBufferedLastAt=${formatCaptureTime(telemetryWindow.lastAtMs)}")
            writer.appendLine("telemetryBufferedSpanMs=${formatBufferedSpanMs(telemetryWindow.firstAtMs, telemetryWindow.lastAtMs)}")
            writer.appendLine("energyBufferedLines=${energyLines.size}")
            writer.appendLine("energyBufferMaxLines=${EnergyDiagnostics.maxBufferedLines()}")
            writer.appendLine("energyDroppedLines=$energyDroppedLines")
            writer.appendLine("energyTruncated=$energyTruncated")
            writer.appendLine("demDownloadBufferedLines=${demDownloadLines.size}")
            writer.appendLine("demDownloadBufferMaxLines=${DemDownloadDiagnostics.maxBufferedLines()}")
            writer.appendLine("demDownloadDroppedLines=$demDownloadDroppedLines")
            writer.appendLine("demDownloadTruncated=$demDownloadTruncated")
            writer.appendLine("markerMotionAcceptedFixes=${markerMotionSummary.acceptedFixes}")
            writer.appendLine("markerMotionPredictionUpdates=${markerMotionSummary.predictionUpdates}")
            writer.appendLine("markerMotionRenderedUpdates=${markerMotionSummary.renderedMotionUpdates}")
            writer.appendLine("markerMotionFirstRenderDelaySamples=${markerMotionSummary.firstRenderDelaySamples}")
            writer.appendLine(
                "markerMotionFirstRenderDelayMeanMs=${markerMotionSummary.firstRenderDelayMeanMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "markerMotionFirstRenderDelayMaxMs=${markerMotionSummary.firstRenderDelayMaxMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "markerMotionActiveRenderIntervalSamples=" +
                    markerMotionSummary.activeRenderIntervalSamples,
            )
            writer.appendLine(
                "markerMotionActiveRenderIntervalMeanMs=" +
                    (markerMotionSummary.activeRenderIntervalMeanMs?.toString() ?: "na"),
            )
            writer.appendLine(
                "markerMotionActiveRenderIntervalP50Ms=" +
                    (markerMotionSummary.activeRenderIntervalP50Ms?.toString() ?: "na"),
            )
            writer.appendLine(
                "markerMotionActiveRenderIntervalP95Ms=" +
                    (markerMotionSummary.activeRenderIntervalP95Ms?.toString() ?: "na"),
            )
            writer.appendLine(
                "markerMotionActiveRenderIntervalMaxMs=" +
                    (markerMotionSummary.activeRenderIntervalMaxMs?.toString() ?: "na"),
            )
            writer.appendLine(
                "markerMotionNextFixResidualM=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.nextFixPredictionResidualM, digits = 1),
            )
            writer.appendLine(
                "markerMotionVisibleNextFixResidualM=" +
                    formatMarkerMotionMetricSummary(
                        markerMotionSummary.visibleNextFixPredictionResidualM,
                        digits = 1,
                    ),
            )
            writer.appendLine(
                "markerMotionScreenOffNextFixResidualM=" +
                    formatMarkerMotionMetricSummary(
                        markerMotionSummary.screenOffNextFixPredictionResidualM,
                        digits = 1,
                    ),
            )
            writer.appendLine(
                "markerMotionRenderStepPx=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.renderDisplacementPx, digits = 2),
            )
            writer.appendLine(
                "markerMotionCorrectionSettleMs=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.correctionSettleDurationMs, digits = 0),
            )
            writer.appendLine("markerMotionCorrectionInterrupted=${markerMotionSummary.correctionInterruptedCount}")
            writer.appendLine("markerMotionBlendStarts=${markerMotionSummary.blendStarts}")
            writer.appendLine("markerMotionOutlierDrops=${markerMotionSummary.outlierDrops}")
            writer.appendLine("markerMotionBlockedTransitions=${markerMotionSummary.blockedTransitions}")
            writer.appendLine(
                "markerMotionBlockedReasons=${
                    formatMarkerMotionBlockedReasons(markerMotionSummary.blockedReasonCounts)
                }",
            )
            writer.appendLine("mapHotPathBufferedLines=${mapHotPathLines.size}")
            writer.appendLine("mapHotPathBufferMaxLines=${MapHotPathDiagnostics.maxBufferedLines()}")
            writer.appendLine("mapHotPathDroppedLines=$mapHotPathDroppedLines")
            writer.appendLine("mapHotPathTruncated=$mapHotPathTruncated")
            writer.appendLine("gnssBufferedLines=${gnssLines.size}")
            writer.appendLine("gnssBufferMaxLines=${GnssDiagnostics.maxBufferedLines()}")
            writer.appendLine("gnssDroppedLines=$gnssDroppedLines")
            writer.appendLine("gnssTruncated=$gnssTruncated")
            writer.appendLine("fieldMarkerBufferedLines=${fieldMarkerLines.size}")
            writer.appendLine("fieldMarkerBufferMaxLines=${FieldMarkerDiagnostics.maxBufferedLines()}")
            writer.appendLine("fieldMarkerDroppedLines=$fieldMarkerDroppedLines")
            writer.appendLine("fieldMarkerTruncated=$fieldMarkerTruncated")
            writer.appendLine(
                "anyCaptureBufferTruncated=${
                    telemetryTruncated ||
                        energyTruncated ||
                        demDownloadTruncated ||
                        mapHotPathTruncated ||
                        gnssTruncated ||
                        fieldMarkerTruncated
                }",
            )
            writer.appendLine()
            writer.appendLine("Telemetry Integrity")
            writer.appendLine("burstStartCount=${telemetryInsights.burstStartCount}")
            writer.appendLine("burstEndCount=${telemetryInsights.burstEndCount}")
            writer.appendLine(
                "burstStartsWithoutEnd=${
                    (telemetryInsights.burstStartCount - telemetryInsights.burstEndCount).coerceAtLeast(0)
                }",
            )
            writer.appendLine("availabilityTrueCount=${telemetryInsights.availabilityTrueCount}")
            writer.appendLine("availabilityFalseCount=${telemetryInsights.availabilityFalseCount}")
            writer.appendLine(
                "availabilityEventCount=${telemetryInsights.availabilityTrueCount + telemetryInsights.availabilityFalseCount}",
            )
            writer.appendLine(
                "availabilityInferredFromFixCount=${telemetryInsights.availabilityInferredFromFixCount}",
            )
            writer.appendLine("screenResumeCount=${telemetryInsights.screenResumeCount}")
            writer.appendLine("screenPauseCount=${telemetryInsights.screenPauseCount}")
            writer.appendLine("ambientEnterCount=${telemetryInsights.ambientEnterCount}")
            writer.appendLine("ambientExitCount=${telemetryInsights.ambientExitCount}")
            writer.appendLine("trackingEnabledTrueCount=${telemetryInsights.trackingEnabledTrueCount}")
            writer.appendLine("trackingEnabledFalseCount=${telemetryInsights.trackingEnabledFalseCount}")
            writer.appendLine(
                "trackingDisabledByScreenPauseCount=${telemetryInsights.trackingDisabledByScreenPauseCount}",
            )
            writer.appendLine("requestUpdatesAppliedCount=${telemetryInsights.requestAppliedCount}")
            writer.appendLine("requestModeBurstCount=${telemetryInsights.requestModeBurstCount}")
            writer.appendLine("requestModeStationaryBoundCount=${telemetryInsights.requestModeStationaryBoundCount}")
            writer.appendLine("requestModeStationaryBackgroundCount=${telemetryInsights.requestModeStationaryBackgroundCount}")
            writer.appendLine("requestModeOtherwiseCount=${telemetryInsights.requestModeOtherwiseCount}")
            writer.appendLine("requestBackendAutoFusedCount=${telemetryInsights.requestBackendAutoFusedCount}")
            writer.appendLine(
                "requestBackendPassiveExternalCount=${telemetryInsights.requestBackendPassiveExternalCount}",
            )
            writer.appendLine("requestBackendWatchGpsCount=${telemetryInsights.requestBackendWatchGpsCount}")
            writer.appendLine("requestBackendSwitchCount=${telemetryInsights.requestBackendSwitchCount}")
            writer.appendLine(
                "requestBackendAutoFusedDurationMs=${telemetryInsights.requestBackendAutoFusedDurationMs}",
            )
            writer.appendLine(
                "requestBackendPassiveExternalDurationMs=${telemetryInsights.requestBackendPassiveExternalDurationMs}",
            )
            writer.appendLine(
                "requestBackendWatchGpsDurationMs=${telemetryInsights.requestBackendWatchGpsDurationMs}",
            )
            writer.appendLine(
                "requestBackendDurationCoverageMs=${telemetryInsights.requestBackendDurationCoverageMs}",
            )
            writer.appendLine("requestModeBurstDurationMs=${telemetryInsights.requestModeBurstDurationMs}")
            writer.appendLine("requestModeStationaryBoundDurationMs=${telemetryInsights.requestModeStationaryBoundDurationMs}")
            writer.appendLine("requestModeStationaryBackgroundDurationMs=${telemetryInsights.requestModeStationaryBackgroundDurationMs}")
            writer.appendLine("requestModeOtherwiseDurationMs=${telemetryInsights.requestModeOtherwiseDurationMs}")
            writer.appendLine("requestModeDurationCoverageMs=${telemetryInsights.requestModeDurationCoverageMs}")
            writer.appendLine("foregroundPinnedSetting=${settings.keepAppOpen}")
            writer.appendLine(
                "foregroundPinnedLastObserved=${
                    TelemetryFormatters.booleanOrNa(telemetryInsights.lastObservedKeepOpen)
                }",
            )
            writer.appendLine("boundLastObserved=${TelemetryFormatters.booleanOrNa(telemetryInsights.lastObservedBound)}")
            writer.appendLine(
                "trackingEnabledLastObserved=${
                    TelemetryFormatters.booleanOrNa(telemetryInsights.lastObservedTrackingEnabled)
                }",
            )
            val gpsTrackingExpectedLastObserved =
                telemetryInsights.lastObservedTrackingEnabled
                    ?: telemetryInsights.lastObservedBound?.let { bound ->
                        bound || settings.gpsInAmbientMode
                    }
            writer.appendLine(
                "gpsTrackingExpectedByPolicyLastObserved=${
                    TelemetryFormatters.booleanOrNa(gpsTrackingExpectedLastObserved)
                }",
            )
            writer.appendLine(
                "startupBogusSampleIgnoredCount=${telemetryInsights.startupBogusSampleIgnoredCount}",
            )
            writer.appendLine("staleFixDropCount=${telemetryInsights.staleFixDropCount}")
            writer.appendLine("sourceMismatchDropCount=${telemetryInsights.sourceMismatchDropCount}")
            writer.appendLine("immediateRequestGuardSkipCount=${telemetryInsights.immediateRequestGuardSkipCount}")
            writer.appendLine(
                "immediateRequestDeferredWakeBurstCount=${
                    telemetryInsights.immediateRequestDeferredWakeBurstCount
                }",
            )
            writer.appendLine(
                "failoverAutoToWatchAccuracyCount=${telemetryInsights.failoverAutoToWatchAccuracyCount}",
            )
            writer.appendLine(
                "failoverAutoToWatchNoFixCount=${telemetryInsights.failoverAutoToWatchNoFixCount}",
            )
            writer.appendLine("failoverWatchToAutoCount=${telemetryInsights.failoverWatchToAutoCount}")
            writer.appendLine(
                "failoverClearedTrackingDisabledCount=${telemetryInsights.failoverClearedTrackingDisabledCount}",
            )
            writer.appendLine(
                "failoverClearedOtherCount=${telemetryInsights.failoverClearedOtherCount}",
            )
            writer.appendLine("gpsFreshTrueCount=${telemetryInsights.gpsFreshTrueCount}")
            writer.appendLine("gpsFreshFalseCount=${telemetryInsights.gpsFreshFalseCount}")
            writer.appendLine(
                "passiveExternalStatus=${
                    formatPassiveExternalStatus(
                        settings = settings,
                        insights = telemetryInsights,
                    )
                }",
            )
            writer.appendLine("passiveExternalSignalSampleCount=${telemetryInsights.passiveExternalSignalSampleCount}")
            writer.appendLine("passiveExternalFreshSampleCount=${telemetryInsights.passiveExternalFreshSampleCount}")
            writer.appendLine("passiveExternalStaleSampleCount=${telemetryInsights.passiveExternalStaleSampleCount}")
            writer.appendLine(
                "passiveExternalAcceptedSampleCount=${telemetryInsights.passiveExternalAcceptedSampleCount}",
            )
            writer.appendLine(
                "passiveExternalRejectedSampleCount=${telemetryInsights.passiveExternalRejectedSampleCount}",
            )
            writer.appendLine("passiveExternalLastAgeMs=${telemetryInsights.passiveExternalLastAgeMs ?: "na"}")
            writer.appendLine("passiveExternalMinAgeMs=${telemetryInsights.passiveExternalMinAgeMs ?: "na"}")
            writer.appendLine("passiveExternalMaxAgeMs=${telemetryInsights.passiveExternalMaxAgeMs ?: "na"}")
            writer.appendLine("passiveExternalLastMaxAgeMs=${telemetryInsights.passiveExternalLastMaxAgeMs ?: "na"}")
            writer.appendLine(
                "passiveExternalLastAccuracyM=${
                    TelemetryFormatters.decimalOrNa(telemetryInsights.passiveExternalLastAccuracyM, 1)
                }",
            )
            writer.appendLine("passiveExternalLastProvider=${telemetryInsights.passiveExternalLastProvider ?: "na"}")
            writer.appendLine(
                "watchGpsDegradedEnteredCount=${telemetryInsights.watchGpsDegradedEnteredCount}",
            )
            writer.appendLine(
                "watchGpsDegradedClearedCount=${telemetryInsights.watchGpsDegradedClearedCount}",
            )
            writer.appendLine(
                "watchGpsDegradedSampleCount=${telemetryInsights.watchGpsDegradedSampleCount}",
            )
            writer.appendLine(
                "watchGpsDegradedLastObserved=${
                    TelemetryFormatters.booleanOrNa(telemetryInsights.watchGpsDegradedLastObserved)
                }",
            )
            writer.appendLine("watchGpsSelfHealSkippedCount=${telemetryInsights.watchGpsSelfHealSkippedCount}")
            writer.appendLine("watchGpsSelfHealRestartCount=${telemetryInsights.watchGpsSelfHealRestartCount}")
            writer.appendLine(
                "watchGpsSelfHealMaxSearchAgeMs=${
                    telemetryInsights.watchGpsSelfHealMaxSearchAgeMs?.toString() ?: "na"
                }",
            )
            writer.appendLine()
            writer.appendLine("Compass Telemetry Summary")
            writer.appendLine("managerStartCount=${compassTelemetryInsights.managerStartCount}")
            writer.appendLine("managerStopScheduledCount=${compassTelemetryInsights.managerStopScheduledCount}")
            writer.appendLine("managerStopRequestedCount=${compassTelemetryInsights.managerStopRequestedCount}")
            writer.appendLine(
                "rotationSettleSessionStartCount=${compassTelemetryInsights.rotationSettleSessionStartCount}",
            )
            writer.appendLine("rotationSettleHoldCount=${compassTelemetryInsights.rotationSettleHoldCount}")
            writer.appendLine("rotationSettleUnlockCount=${compassTelemetryInsights.rotationSettleUnlockCount}")
            writer.appendLine(
                "rotationSettleHoldReasons=${compassTelemetryInsights.rotationSettleHoldReasons}",
            )
            writer.appendLine(
                "rotationSettleUnlockReasons=${compassTelemetryInsights.rotationSettleUnlockReasons}",
            )
            writer.appendLine(
                "rotationSettleHoldMaxHeadingDeltaDeg=${
                    compassTelemetryInsights.rotationSettleHoldMaxHeadingDeltaDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine("headingSampleCount=${compassTelemetryInsights.headingSampleCount}")
            writer.appendLine(
                "headingDiagnosticSampleCount=${compassTelemetryInsights.headingDiagnosticSampleCount}",
            )
            writer.appendLine("headingSampleCountScope=throttled_debug_lines_not_sensor_callbacks")
            writer.appendLine("largeJumpPendingCount=${compassTelemetryInsights.largeJumpPendingCount}")
            writer.appendLine("largeJumpAcceptedCount=${compassTelemetryInsights.largeJumpAcceptedCount}")
            writer.appendLine(
                "largeJumpRatePercent=${
                    TelemetryFormatters.ratePercent(
                        compassTelemetryInsights.largeJumpPendingCount +
                            compassTelemetryInsights.largeJumpAcceptedCount,
                        compassTelemetryInsights.headingSampleCount,
                    )
                }",
            )
            writer.appendLine("staleSampleCount=${compassTelemetryInsights.staleSampleCount}")
            writer.appendLine(
                "staleSampleRatePercent=${
                    TelemetryFormatters.ratePercent(
                        compassTelemetryInsights.staleSampleCount,
                        compassTelemetryInsights.headingSampleCount,
                    )
                }",
            )
            writer.appendLine(
                "largeJumpWithinManagerStart500MsCount=${
                    compassTelemetryInsights.largeJumpWithinManagerStart500MsCount
                }",
            )
            writer.appendLine(
                "sampleAfterStopScheduledCount=${compassTelemetryInsights.sampleAfterStopScheduledCount}",
            )
            writer.appendLine(
                "sampleAfterStopRequestedCount=${compassTelemetryInsights.sampleAfterStopRequestedCount}",
            )
            writer.appendLine("startupSummaryCount=${compassTelemetryInsights.startupSummaryCount}")
            writer.appendLine(
                "startupHeadingSpanMaxDeg=${
                    compassTelemetryInsights.startupHeadingSpanMaxDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "startupMaxJumpMaxDeg=${
                    compassTelemetryInsights.startupMaxJumpMaxDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "startupVisibleHeadingJumpMaxDeg=${
                    compassTelemetryInsights.startupVisibleHeadingJumpMaxDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "startupVisibleMapRotationJumpMaxDeg=${
                    compassTelemetryInsights.startupVisibleMapRotationJumpMaxDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "startupSourceHandoffCount=${compassTelemetryInsights.startupSourceHandoffCount}",
            )
            writer.appendLine(
                "startupSourceHandoffMaxJumpDeg=${
                    compassTelemetryInsights.startupSourceHandoffMaxJumpDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine("startupStable3Count=${compassTelemetryInsights.startupStable3Count}")
            writer.appendLine("startupStable5Count=${compassTelemetryInsights.startupStable5Count}")
            writer.appendLine(
                "fusedFirstUsableCount=${compassTelemetryInsights.fusedFirstUsableCount}",
            )
            writer.appendLine(
                "fusedFirstUsableLatencyMaxMs=${
                    compassTelemetryInsights.fusedFirstUsableLatencyMaxMs ?: "na"
                }",
            )
            writer.appendLine("fusedReadyCount=${compassTelemetryInsights.fusedReadyCount}")
            writer.appendLine(
                "fusedReadyLatencyMaxMs=${
                    compassTelemetryInsights.fusedReadyLatencyMaxMs ?: "na"
                }",
            )
            writer.appendLine(
                "fusedWarmupRelockCount=${compassTelemetryInsights.fusedWarmupRelockCount}",
            )
            writer.appendLine(
                "fusedWarmupRelockStepMaxDeg=${
                    compassTelemetryInsights.fusedWarmupRelockStepMaxDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "fusedReadyAfterRelockCount=${compassTelemetryInsights.fusedReadyAfterRelockCount}",
            )
            writer.appendLine(
                "fusedFallbackActivationCount=${compassTelemetryInsights.fusedFallbackActivationCount}",
            )
            writer.appendLine("continuityStartCount=${compassTelemetryInsights.continuityStartCount}")
            writer.appendLine(
                "continuityCompleteCount=${compassTelemetryInsights.continuityCompleteCount}",
            )
            writer.appendLine(
                "continuityCancelCount=${compassTelemetryInsights.continuityCancelCount}",
            )
            writer.appendLine(
                "continuityInitialOffsetMaxDeg=${
                    compassTelemetryInsights.continuityInitialOffsetMaxDeg?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "continuityDurationMaxMs=${
                    compassTelemetryInsights.continuityDurationMaxMs ?: "na"
                }",
            )
            writer.appendLine(
                "headingLooksWrongReportCount=${compassTelemetryInsights.headingLooksWrongReportCount}",
            )
            writer.appendLine("fusedPerfEventCount=${compassTelemetryInsights.fusedPerfEventCount}")
            writer.appendLine("fusedPerfCallbackCount=${compassTelemetryInsights.fusedPerfCallbackCount}")
            writer.appendLine("fusedPerfConfirmedCount=${compassTelemetryInsights.fusedPerfConfirmedCount}")
            writer.appendLine("fusedPerfUnusableCount=${compassTelemetryInsights.fusedPerfUnusableCount}")
            writer.appendLine(
                "fusedPerfHeadingPublishCount=${compassTelemetryInsights.fusedPerfHeadingPublishCount}",
            )
            writer.appendLine(
                "fusedPerfCallbackHzMax=${
                    compassTelemetryInsights.fusedPerfCallbackHzMax?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "fusedPerfPublishHzMax=${
                    compassTelemetryInsights.fusedPerfPublishHzMax?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine("renderPerfEventCount=${compassTelemetryInsights.renderPerfEventCount}")
            writer.appendLine("renderPerfFrameCount=${compassTelemetryInsights.renderPerfFrameCount}")
            writer.appendLine(
                "renderPerfTargetUpdateCount=${compassTelemetryInsights.renderPerfTargetUpdateCount}",
            )
            writer.appendLine(
                "renderPerfHeadingRenderCount=${compassTelemetryInsights.renderPerfHeadingRenderCount}",
            )
            writer.appendLine(
                "renderPerfRotationAppliedCount=${compassTelemetryInsights.renderPerfRotationAppliedCount}",
            )
            writer.appendLine(
                "renderPerfRotationSkippedCount=${compassTelemetryInsights.renderPerfRotationSkippedCount}",
            )
            writer.appendLine(
                "renderPerfRotationThrottledCount=${
                    compassTelemetryInsights.renderPerfRotationThrottledCount
                }",
            )
            writer.appendLine(
                "renderPerfMarkerUpdateCount=${compassTelemetryInsights.renderPerfMarkerUpdateCount}",
            )
            writer.appendLine("renderPerfRedrawCount=${compassTelemetryInsights.renderPerfRedrawCount}")
            writer.appendLine(
                "renderPerfFrameHzMax=${
                    compassTelemetryInsights.renderPerfFrameHzMax?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.appendLine(
                "renderPerfRenderHzMax=${
                    compassTelemetryInsights.renderPerfRenderHzMax?.let {
                        TelemetryFormatters.decimal(it, 1)
                    } ?: "na"
                }",
            )
            writer.writeCompassHeadingTelemetrySummary(compassHeadingTelemetrySummary)
            writer.appendLine("batchEventCount=${telemetryInsights.batchEventCount}")
            writer.appendLine("batchOriginAutoFusedCount=${telemetryInsights.batchOriginAutoFusedCount}")
            writer.appendLine(
                "batchOriginPassiveExternalCount=${telemetryInsights.batchOriginPassiveExternalCount}",
            )
            writer.appendLine("batchOriginWatchGpsCount=${telemetryInsights.batchOriginWatchGpsCount}")
            writer.appendLine("batchFallbackCount=${telemetryInsights.batchFallbackCount}")
            writer.appendLine(
                "batchDuplicateCandidatesDroppedTotal=${telemetryInsights.batchDuplicateCandidatesDroppedTotal}",
            )
            writer.appendLine("batchRawCandidatesTotal=${telemetryInsights.batchRawCandidatesTotal}")
            writer.appendLine("batchNormalizedCandidatesTotal=${telemetryInsights.batchNormalizedCandidatesTotal}")
            writer.appendLine("batchAcceptedCandidatesTotal=${telemetryInsights.batchAcceptedCandidatesTotal}")
            writer.appendLine("batchRawCandidatesMax=${telemetryInsights.batchRawCandidatesMax}")
            writer.appendLine("batchNormalizedCandidatesMax=${telemetryInsights.batchNormalizedCandidatesMax}")
            writer.appendLine("callbackAcceptedFixCount=${telemetryInsights.callbackAcceptedFixCount}")
            writer.appendLine("immediateAcceptedFixCount=${telemetryInsights.immediateAcceptedFixCount}")
            writer.appendLine(
                "acceptedFixOriginAutoFusedCount=${telemetryInsights.acceptedFixOriginAutoFusedCount}",
            )
            writer.appendLine(
                "acceptedFixOriginPassiveExternalCount=${
                    telemetryInsights.acceptedFixOriginPassiveExternalCount
                }",
            )
            writer.appendLine(
                "acceptedFixOriginWatchGpsCount=${telemetryInsights.acceptedFixOriginWatchGpsCount}",
            )
            writer.appendLine("fixProviderGpsCount=${telemetryInsights.fixProviderGpsCount}")
            writer.appendLine("fixProviderFusedCount=${telemetryInsights.fixProviderFusedCount}")
            writer.appendLine("screenOnFixGapSampleCount=${telemetryInsights.screenOnFixGapSampleCount}")
            writer.appendLine(
                "screenOnFixGapAvgMs=${
                    telemetryInsights.screenOnFixGapAvgMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "screenOnFixGapMaxMs=${
                    if (telemetryInsights.screenOnFixGapSampleCount > 0) {
                        telemetryInsights.screenOnFixGapMaxMs.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine("interactiveFixGapSampleCount=${telemetryInsights.fixGapBuckets.interactiveSampleCount}")
            writer.appendLine(
                "interactiveFixGapAvgMs=${
                    telemetryInsights.fixGapBuckets.interactiveAvgMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "interactiveFixGapMaxMs=${
                    if (telemetryInsights.fixGapBuckets.interactiveSampleCount > 0) {
                        telemetryInsights.fixGapBuckets.interactiveMaxMs.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine(
                "nonInteractiveFixGapSampleCount=${telemetryInsights.fixGapBuckets.nonInteractiveSampleCount}",
            )
            writer.appendLine(
                "nonInteractiveFixGapAvgMs=${
                    telemetryInsights.fixGapBuckets.nonInteractiveAvgMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "nonInteractiveFixGapMaxMs=${
                    if (telemetryInsights.fixGapBuckets.nonInteractiveSampleCount > 0) {
                        telemetryInsights.fixGapBuckets.nonInteractiveMaxMs.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine(
                "unknownScreenFixGapSampleCount=${telemetryInsights.fixGapBuckets.unknownScreenSampleCount}",
            )
            writer.appendLine(
                "unknownScreenFixGapAvgMs=${
                    telemetryInsights.fixGapBuckets.unknownScreenAvgMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "unknownScreenFixGapMaxMs=${
                    if (telemetryInsights.fixGapBuckets.unknownScreenSampleCount > 0) {
                        telemetryInsights.fixGapBuckets.unknownScreenMaxMs.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine("delayedFixGapCount=${telemetryInsights.fixGapBuckets.delayedCount}")
            writer.appendLine()
            writer.appendLine("Turn-by-Turn Guidance")
            writer.appendLine("turnByTurnSampleCount=${telemetryInsights.turnByTurnSampleCount}")
            writer.appendLine("turnByTurnActiveSampleCount=${telemetryInsights.turnByTurnActiveSampleCount}")
            writer.appendLine("turnByTurnPausedSampleCount=${telemetryInsights.turnByTurnPausedSampleCount}")
            writer.appendLine("turnByTurnOffRouteSampleCount=${telemetryInsights.turnByTurnOffRouteSampleCount}")
            writer.appendLine(
                "turnByTurnGuideBackActiveSampleCount=${telemetryInsights.turnByTurnGuideBackActiveSampleCount}",
            )
            writer.appendLine(
                "turnByTurnGuideBackPromptSampleCount=${telemetryInsights.turnByTurnGuideBackPromptSampleCount}",
            )
            writer.appendLine(
                "turnByTurnStartHereDecisionCount=${telemetryInsights.turnByTurnStartHereDecisionCount}",
            )
            writer.appendLine("turnByTurnReverseDecisionCount=${telemetryInsights.turnByTurnReverseDecisionCount}")
            writer.appendLine(
                "turnByTurnGeometryInstructionSampleCount=${
                    telemetryInsights.turnByTurnGeometryInstructionSampleCount
                }",
            )
            writer.appendLine(
                "turnByTurnBrouterHintInstructionSampleCount=${
                    telemetryInsights.turnByTurnBrouterHintInstructionSampleCount
                }",
            )
            writer.appendLine("turnByTurnTurnHapticCount=${telemetryInsights.turnByTurnTurnHapticCount}")
            writer.appendLine("turnByTurnOffRouteHapticCount=${telemetryInsights.turnByTurnOffRouteHapticCount}")
            writer.appendLine(
                "turnByTurnTurnAlertFiredCount=${telemetryInsights.turnByTurnTurnAlertFiredCount}",
            )
            writer.appendLine(
                "turnByTurnTurnAlertFilteredCount=${telemetryInsights.turnByTurnTurnAlertFilteredCount}",
            )
            writer.appendLine(
                "turnByTurnTurnAlertOffRouteCount=${telemetryInsights.turnByTurnTurnAlertOffRouteCount}",
            )
            writer.appendLine(
                "turnByTurnTurnAlertMissedWindowCount=${telemetryInsights.turnByTurnTurnAlertMissedWindowCount}",
            )
            writer.appendLine(
                "turnByTurnMaxDistanceToRouteMeters=${
                    telemetryInsights.turnByTurnMaxDistanceToRouteMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine()
            writer.appendLine("Trace Recording")
            writer.appendLine("navigationSessionMode=${recordingGuidanceSessionMode(telemetryInsights)}")
            writer.appendLine("recordingStartCount=${telemetryInsights.recordingStartCount}")
            writer.appendLine("recordingRecoveredCount=${telemetryInsights.recordingRecoveredCount}")
            writer.appendLine("recordingPauseCount=${telemetryInsights.recordingPauseCount}")
            writer.appendLine("recordingResumeCount=${telemetryInsights.recordingResumeCount}")
            writer.appendLine("recordingPointSampleCount=${telemetryInsights.recordingPointSampleCount}")
            writer.appendLine("recordingSaveStartCount=${telemetryInsights.recordingSaveStartCount}")
            writer.appendLine("recordingSaveSuccessCount=${telemetryInsights.recordingSaveSuccessCount}")
            writer.appendLine("recordingSaveFailureCount=${telemetryInsights.recordingSaveFailureCount}")
            writer.appendLine("recordingDiscardCount=${telemetryInsights.recordingDiscardCount}")
            writer.appendLine("recordingDraftFailureCount=${telemetryInsights.recordingDraftFailureCount}")
            writer.appendLine("recordingDraftClearFailureCount=${telemetryInsights.recordingDraftClearFailureCount}")
            writer.appendLine("recordingLastUiAction=${telemetryInsights.recordingLastUiAction ?: "na"}")
            writer.appendLine("recordingLastEndReason=${telemetryInsights.recordingLastEndReason ?: "na"}")
            writer.appendLine(
                "recordingLastPointCount=${telemetryInsights.recordingLastPointCount?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingMaxPointCount=${telemetryInsights.recordingMaxPointCount?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingLastDistanceMeters=${telemetryInsights.recordingLastDistanceMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingMaxDistanceMeters=${telemetryInsights.recordingMaxDistanceMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingGpsDistanceMeters=${telemetryInsights.recordingGpsDistanceMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingDisplayDistanceMeters=${
                    telemetryInsights.recordingDisplayDistanceMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingPodSessionDistanceMeters=${
                    telemetryInsights.recordingPodSessionDistanceMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingPodIntegratedDistanceMeters=${
                    telemetryInsights.recordingPodIntegratedDistanceMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSavedGpxWrittenPoints=${
                    telemetryInsights.recordingSavedGpxWrittenPoints?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSavedGpxParsedPoints=${
                    telemetryInsights.recordingSavedGpxParsedPoints?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSavedGpxSummaryPoints=${
                    telemetryInsights.recordingSavedGpxSummaryPoints?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSavedGpxSummaryDistanceMeters=${
                    telemetryInsights.recordingSavedGpxSummaryDistanceMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingLastDurationMs=${telemetryInsights.recordingLastDurationMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingMaxDurationMs=${telemetryInsights.recordingMaxDurationMs?.toString() ?: "na"}",
            )
            writer.appendLine("recordingLastPausedMs=${telemetryInsights.recordingLastPausedMs?.toString() ?: "na"}")
            writer.appendLine("recordingMaxPausedMs=${telemetryInsights.recordingMaxPausedMs?.toString() ?: "na"}")
            writer.appendLine(
                "recordingGpsActiveDurationMs=${
                    telemetryInsights.recordingGpsActiveDurationMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingExpectedPointCount=${
                    telemetryInsights.recordingExpectedPointCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingAveragePointIntervalMs=${
                    telemetryInsights.recordingAveragePointIntervalMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingPointCaptureRatePercent=${
                    telemetryInsights.recordingPointCaptureRatePercent?.toString() ?: "na"
                }",
            )
            writer.appendLine("recordingGapCount=${telemetryInsights.recordingGapCount?.toString() ?: "na"}")
            writer.appendLine("recordingGapEventCount=${telemetryInsights.recordingGapEventCount}")
            writer.appendLine("recordingGapDefinition=accepted_point_time_gap_not_confirmed_movement_loss")
            writer.appendLine(
                "recordingGapEndpointDistanceM=" +
                    if (telemetryInsights.recordingGapEndpointDistanceSampleCount <= 0) {
                        "samples:0"
                    } else {
                        val averageMeters =
                            TelemetryFormatters.decimalOrNa(
                                telemetryInsights.recordingGapEndpointDistanceAvgMeters,
                                1,
                            )
                        val maximumMeters =
                            TelemetryFormatters.decimalOrNa(
                                telemetryInsights.recordingGapEndpointDistanceMaxMeters,
                                1,
                            )
                        "samples:${telemetryInsights.recordingGapEndpointDistanceSampleCount}," +
                            "avg:$averageMeters,max:$maximumMeters"
                    },
            )
            writer.appendLine("recordingMaxGapMs=${telemetryInsights.recordingMaxGapMs?.toString() ?: "na"}")
            writer.appendLine(
                "recordingLastPointAgeMs=${telemetryInsights.recordingLastPointAgeMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingForcedAcceptCount=${telemetryInsights.recordingForcedAcceptCount?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingGapRecoveryAcceptCount=${
                    telemetryInsights.recordingGapRecoveryAcceptCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingTrackSmoothingMode=${telemetryInsights.recordingTrackFilter.smoothingMode ?: "na"}",
            )
            writer.appendLine(
                "recordingTrackFilterVersion=${
                    telemetryInsights.recordingTrackFilter.filterVersion?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingQualityHeldFixCount=${
                    telemetryInsights.recordingTrackFilter.qualityHeldFixCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingQualityRejectedFixCount=${
                    telemetryInsights.recordingTrackFilter.qualityRejectedFixCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingQualityRelocationCount=${
                    telemetryInsights.recordingTrackFilter.qualityRelocationCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSmoothedPointCount=${
                    telemetryInsights.recordingTrackFilter.smoothedPointCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSmoothedAdjustmentMeters=${
                    telemetryInsights.recordingTrackFilter.smoothedAdjustmentMeters ?: "na"
                }",
            )
            writer.appendLine(
                "recordingMaxSmoothedAdjustmentMeters=${
                    telemetryInsights.recordingTrackFilter.maxSmoothedAdjustmentMeters ?: "na"
                }",
            )
            writeRecordingSmartTrackSection(
                writer = writer,
                insights = telemetryInsights.recordingTrackFilter.smartTrack,
            )
            writer.appendLine(
                "recordingLastSkippedIntervalElapsedMs=${
                    telemetryInsights.recordingLastSkippedIntervalElapsedMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingMaxSkippedIntervalElapsedMs=${
                    telemetryInsights.recordingMaxSkippedIntervalElapsedMs?.toString() ?: "na"
                }",
            )
            writer.appendLine("recordingLastLiveProvider=${telemetryInsights.recordingLastLiveProvider ?: "na"}")
            writer.appendLine(
                "recordingLastLiveAccuracyMeters=${
                    telemetryInsights.recordingLastLiveAccuracyMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSkippedIntervalCount=${
                    telemetryInsights.recordingSkippedIntervalCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingSkippedPausedCount=${telemetryInsights.recordingSkippedPausedCount?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingSkippedUnusableCount=${telemetryInsights.recordingSkippedUnusableCount?.toString() ?: "na"}",
            )
            writer.appendLine("recordingElevationSource=${telemetryInsights.recordingElevationSource ?: "na"}")
            writer.appendLine("recordingDemHitCount=${telemetryInsights.recordingDemHitCount?.toString() ?: "na"}")
            writer.appendLine("recordingDemMissCount=${telemetryInsights.recordingDemMissCount?.toString() ?: "na"}")
            writer.appendLine(
                "recordingGpsElevationUsedCount=${
                    telemetryInsights.recordingGpsElevationUsedCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingAccuracySampleCount=${telemetryInsights.recordingAccuracySampleCount?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingAccuracyAvgMeters=${telemetryInsights.recordingAccuracyAvgMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingAccuracyMinMeters=${telemetryInsights.recordingAccuracyMinMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingAccuracyMaxMeters=${telemetryInsights.recordingAccuracyMaxMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingElevationGainMeters=${telemetryInsights.recordingElevationGainMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingElevationLossMeters=${telemetryInsights.recordingElevationLossMeters?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingLiveHeartRateBpm=${telemetryInsights.recordingLiveHeartRateBpm?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingAverageHeartRateBpm=${
                    telemetryInsights.recordingAverageHeartRateBpm?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingLiveStepCount=${telemetryInsights.recordingLiveStepCount?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingLiveCadenceSpm=${telemetryInsights.recordingLiveCadenceSpm?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingLiveExternalSpeedMps=${telemetryInsights.recordingLiveExternalSpeedMps ?: "na"}",
            )
            writer.appendLine(
                "recordingLiveExternalRawDistanceUnits=${
                    telemetryInsights.recordingLiveExternalRawDistanceUnits?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingLiveExternalDistanceMeters=${
                    telemetryInsights.recordingLiveExternalDistanceMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingLiveExternalSessionDistanceMeters=${
                    telemetryInsights.recordingLiveExternalSessionDistanceMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingLiveExternalIntegratedDistanceMeters=${
                    telemetryInsights.recordingLiveExternalIntegratedDistanceMeters?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingLivePressureHpa=${telemetryInsights.recordingLivePressureHpa?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingHeartRateAgeMs=${telemetryInsights.recordingHeartRateAgeMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingStepCountAgeMs=${telemetryInsights.recordingStepCountAgeMs?.toString() ?: "na"}",
            )
            writer.appendLine("recordingCadenceAgeMs=${telemetryInsights.recordingCadenceAgeMs?.toString() ?: "na"}")
            writer.appendLine(
                "recordingExternalSpeedAgeMs=${
                    telemetryInsights.recordingExternalSpeedAgeMs?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingExternalDistanceAgeMs=${
                    telemetryInsights.recordingExternalDistanceAgeMs?.toString() ?: "na"
                }",
            )
            writer.appendLine("recordingPressureAgeMs=${telemetryInsights.recordingPressureAgeMs?.toString() ?: "na"}")
            writer.appendLine(
                "recordingHeartRateSensorEventCount=${
                    telemetryInsights.recordingHeartRateSensorEventCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingStepSensorEventCount=${
                    telemetryInsights.recordingStepSensorEventCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingCadenceSensorEventCount=${
                    telemetryInsights.recordingCadenceSensorEventCount?.toString() ?: "na"
                }",
            )
            writer.appendLine(
                "recordingPressureSensorEventCount=${
                    telemetryInsights.recordingPressureSensorEventCount?.toString() ?: "na"
                }",
            )
            writer.appendLine("recordingSensorRegisterCount=${telemetryInsights.recordingSensorRegisterCount}")
            writer.appendLine("recordingSensorRequested=${telemetryInsights.recordingSensorRequested ?: "na"}")
            writer.appendLine("recordingSensorRegistered=${telemetryInsights.recordingSensorRegistered ?: "na"}")
            writer.appendLine("recordingSensorAvailable=${telemetryInsights.recordingSensorAvailable ?: "na"}")
            writer.appendLine(
                "recordingBodySensorsGranted=${telemetryInsights.recordingBodySensorsGranted?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingActivityRecognitionGranted=${
                    telemetryInsights.recordingActivityRecognitionGranted?.toString() ?: "na"
                }",
            )
            writer.appendLine("externalSensorScanStartCount=${telemetryInsights.externalSensorScanStartCount}")
            writer.appendLine("externalSensorScanStopCount=${telemetryInsights.externalSensorScanStopCount}")
            writer.appendLine("externalSensorScanFailedCount=${telemetryInsights.externalSensorScanFailedCount}")
            writer.appendLine(
                "externalSensorLastScanDeviceCount=${
                    telemetryInsights.externalSensorLastScanDeviceCount?.toString() ?: "na"
                }",
            )
            writer.appendLine("externalSensorLastScanSummary=${telemetryInsights.externalSensorLastScanSummary ?: "na"}")
            writer.appendLine(
                "externalSensorLastScanFailureReason=${
                    telemetryInsights.externalSensorLastScanFailureReason ?: "na"
                }",
            )
            writer.appendLine("externalHeartRateStatus=${formatExternalHeartRateStatus(settings, telemetryInsights)}")
            writer.appendLine(
                "externalHeartRateBridgeStartCount=${telemetryInsights.externalHeartRateBridgeStartCount}",
            )
            writer.appendLine(
                "externalHeartRateBridgeStopCount=${telemetryInsights.externalHeartRateBridgeStopCount}",
            )
            writer.appendLine(
                "externalHeartRateConnectRequestedCount=${
                    telemetryInsights.externalHeartRateConnectRequestedCount
                }",
            )
            writer.appendLine(
                "externalHeartRateConnectSkippedCount=${telemetryInsights.externalHeartRateConnectSkippedCount}",
            )
            writer.appendLine(
                "externalHeartRateLastConnectSkippedReason=${
                    telemetryInsights.externalHeartRateLastConnectSkippedReason ?: "na"
                }",
            )
            writer.appendLine("externalHeartRateConnectedCount=${telemetryInsights.externalHeartRateConnectedCount}")
            writer.appendLine(
                "externalHeartRateDisconnectedCount=${telemetryInsights.externalHeartRateDisconnectedCount}",
            )
            writer.appendLine(
                "externalHeartRateReconnectScheduledCount=${
                    telemetryInsights.externalHeartRateReconnectScheduledCount
                }",
            )
            writer.appendLine(
                "externalHeartRateReconnectAttemptCount=${telemetryInsights.externalHeartRateReconnectAttemptCount}",
            )
            writer.appendLine(
                "externalHeartRateNotifyRequestedCount=${telemetryInsights.externalHeartRateNotifyRequestedCount}",
            )
            writer.appendLine("externalHeartRateNotifyFailedCount=${telemetryInsights.externalHeartRateNotifyFailedCount}")
            writer.appendLine(
                "externalHeartRateServiceFailureCount=${telemetryInsights.externalHeartRateServiceFailureCount}",
            )
            writer.appendLine(
                "externalHeartRateMeasurementMissingCount=${
                    telemetryInsights.externalHeartRateMeasurementMissingCount
                }",
            )
            writer.appendLine("externalHeartRateSampleCount=${telemetryInsights.externalHeartRateSampleCount}")
            writer.appendLine("externalHeartRateLastBpm=${telemetryInsights.externalHeartRateLastBpm ?: "na"}")
            writer.appendLine("externalHeartRateMinBpm=${telemetryInsights.externalHeartRateMinBpm ?: "na"}")
            writer.appendLine("externalHeartRateMaxBpm=${telemetryInsights.externalHeartRateMaxBpm ?: "na"}")
            writer.appendLine(
                "externalHeartRateLastSampleAgeMs=${
                    telemetryInsights.externalHeartRateLastSampleAgeMs?.toString() ?: "na"
                }",
            )
            writer.appendLine("externalRunPodStatus=${formatExternalRunPodStatus(settings, telemetryInsights)}")
            writer.appendLine("externalRunPodBridgeStartCount=${telemetryInsights.externalRunPodBridgeStartCount}")
            writer.appendLine("externalRunPodBridgeStopCount=${telemetryInsights.externalRunPodBridgeStopCount}")
            writer.appendLine("externalRunPodConnectRequestedCount=${telemetryInsights.externalRunPodConnectRequestedCount}")
            writer.appendLine("externalRunPodConnectSkippedCount=${telemetryInsights.externalRunPodConnectSkippedCount}")
            writer.appendLine(
                "externalRunPodLastConnectSkippedReason=${
                    telemetryInsights.externalRunPodLastConnectSkippedReason ?: "na"
                }",
            )
            writer.appendLine("externalRunPodConnectedCount=${telemetryInsights.externalRunPodConnectedCount}")
            writer.appendLine("externalRunPodDisconnectedCount=${telemetryInsights.externalRunPodDisconnectedCount}")
            writer.appendLine("externalRunPodNotifyRequestedCount=${telemetryInsights.externalRunPodNotifyRequestedCount}")
            writer.appendLine("externalRunPodNotifyFailedCount=${telemetryInsights.externalRunPodNotifyFailedCount}")
            writer.appendLine("externalRunPodServiceFailureCount=${telemetryInsights.externalRunPodServiceFailureCount}")
            writer.appendLine(
                "externalRunPodMeasurementMissingCount=${telemetryInsights.externalRunPodMeasurementMissingCount}",
            )
            writer.appendLine("externalRunPodSampleCount=${telemetryInsights.externalRunPodSampleCount}")
            writer.appendLine("externalRunPodLastCadenceSpm=${telemetryInsights.externalRunPodLastCadenceSpm ?: "na"}")
            writer.appendLine("externalRunPodLastPowerWatts=${telemetryInsights.externalRunPodLastPowerWatts ?: "na"}")
            writer.appendLine(
                "externalRunPodLastBatteryPercent=${telemetryInsights.externalRunPodLastBatteryPercent ?: "na"}",
            )
            writer.appendLine("externalRunPodLastSpeedMps=${telemetryInsights.externalRunPodLastSpeedMps ?: "na"}")
            writer.appendLine(
                "externalRunPodLastRawDistanceUnits=${telemetryInsights.externalRunPodLastRawDistanceUnits ?: "na"}",
            )
            writer.appendLine(
                "externalRunPodLastDistanceMeters=${telemetryInsights.externalRunPodLastDistanceMeters ?: "na"}",
            )
            writer.appendLine(
                "externalRunPodLastSampleAgeMs=${
                    telemetryInsights.externalRunPodLastSampleAgeMs?.toString() ?: "na"
                }",
            )
            writer.appendLine("recordingCalorieModel=${telemetryInsights.recordingCalorieModel ?: "na"}")
            writer.appendLine(
                "recordingCaloriesGrossKcal=${telemetryInsights.recordingCaloriesGrossKcal?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingCaloriesActiveKcal=${telemetryInsights.recordingCaloriesActiveKcal?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingCaloriesRestingKcal=${telemetryInsights.recordingCaloriesRestingKcal?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingPandolfBaseGrossKcal=${telemetryInsights.recordingPandolfBaseGrossKcal?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingPandolfBaseActiveKcal=${telemetryInsights.recordingPandolfBaseActiveKcal?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingPandolfBaseRestingKcal=${telemetryInsights.recordingPandolfBaseRestingKcal?.toString() ?: "na"}",
            )
            writer.appendLine("recordingCalorieCompareModel=${telemetryInsights.recordingCalorieCompareModel ?: "na"}")
            writer.appendLine("recordingLcdaGrossKcal=${telemetryInsights.recordingLcdaGrossKcal?.toString() ?: "na"}")
            writer.appendLine("recordingLcdaActiveKcal=${telemetryInsights.recordingLcdaActiveKcal?.toString() ?: "na"}")
            writer.appendLine(
                "recordingLcdaRestingKcal=${telemetryInsights.recordingLcdaRestingKcal?.toString() ?: "na"}",
            )
            writer.appendLine(
                "recordingLastSavedByteSize=${telemetryInsights.recordingLastSavedByteSize?.toString() ?: "na"}",
            )
            writer.appendLine()
            writer.appendLine("Runtime Reliability")
            writer.appendLine("locationServiceStartFailureCount=${telemetryInsights.locationServiceStartFailureCount}")
            writer.appendLine(
                "locationServiceStartFallbackFailureCount=${
                    telemetryInsights.locationServiceStartFallbackFailureCount
                }",
            )
            writer.appendLine(
                "locationStartForegroundFailureCount=${telemetryInsights.locationStartForegroundFailureCount}",
            )
            writer.appendLine("thermalStatusEventCount=${telemetryInsights.thermalStatusEventCount}")
            writer.appendLine("thermalMaxStatus=${telemetryInsights.thermalMaxStatus?.toString() ?: "na"}")
            writer.appendLine("thermalLastStatusLabel=${telemetryInsights.thermalLastStatusLabel ?: "na"}")
            writer.appendLine(
                "batchAvgRawCandidates=${
                    TelemetryFormatters.average(
                        total = telemetryInsights.batchRawCandidatesTotal,
                        count = telemetryInsights.batchEventCount,
                    )
                }",
            )
            writer.appendLine(
                "batchAvgAcceptedCandidates=${
                    TelemetryFormatters.average(
                        total = telemetryInsights.batchAcceptedCandidatesTotal,
                        count = telemetryInsights.batchEventCount,
                    )
                }",
            )
            writer.appendLine(
                "batchAcceptanceRatePct=${
                    TelemetryFormatters.ratePercent(
                        numerator = telemetryInsights.batchAcceptedCandidatesTotal,
                        denominator = telemetryInsights.batchNormalizedCandidatesTotal,
                    )
                }",
            )
            writer.appendLine("sessionDurationMs=${captureDurationMs?.toString() ?: "na"}")
            writer.appendLine("bufferedSpanMs=${bufferedSpanMs?.toString() ?: "na"}")
            writer.appendLine("sessionVsBufferedMismatch=$sessionVsBufferedMismatch")
            writer.appendLine()
            writer.appendLine("Reported Vs Observed Fix Quality")
            writeAcceptedFixQualitySection(
                writer = writer,
                prefix = "overall",
                summary = acceptedFixSummaries.overall,
                quality =
                    inferObservedFixQuality(
                        summary = acceptedFixSummaries.overall,
                        origin = null,
                        gnssInsights = gnssInsights,
                    ),
            )
            writeAcceptedFixQualitySection(
                writer = writer,
                prefix = "autoFused",
                summary = acceptedFixSummaries.autoFused,
                quality =
                    inferObservedFixQuality(
                        summary = acceptedFixSummaries.autoFused,
                        origin = "auto_fused",
                        gnssInsights = gnssInsights,
                    ),
            )
            writeAcceptedFixQualitySection(
                writer = writer,
                prefix = "watchGps",
                summary = acceptedFixSummaries.watchGps,
                quality =
                    inferObservedFixQuality(
                        summary = acceptedFixSummaries.watchGps,
                        origin = "watch_gps",
                        gnssInsights = gnssInsights,
                    ),
            )
            writer.writeBundleDownloadSummarySection(bundleDownloadSummary)
            writer.writeLineDumpSection(
                title = "Telemetry",
                emptyMessage = "No telemetry captured yet. Enable diagnostics capture and reproduce.",
                lines = telemetryLines,
            )
            writer.writeEnergyByModeSummarySection(energySummary)
            writer.writeCompassDeepTraceSection(compassDeepTraceSnapshot)
            writer.writeScreenStateSummarySection(screenStateSummary)
            writer.writeLineDumpSection(
                title = "Energy Diagnostics",
                emptyMessage = "No energy diagnostics samples yet.",
                lines = energyLines,
            )
            writer.writeDemDownloadSections(
                demDownloadSummary = demDownloadSummary,
                demDownloadLines = demDownloadLines,
                demDownloadTruncated = demDownloadTruncated,
            )
            writer.writeGnssSections(
                gnssInsights = gnssInsights,
                gnssLines = gnssLines,
            )
            writer.writeLineDumpSection(
                title = "Field Markers",
                emptyMessage = "No field markers captured.",
                lines = fieldMarkerLines,
            )
            writer.appendLine()
            writer.appendLine("Marker Motion Summary")
            writer.appendLine("summary=${markerMotionSummary.summaryLabel()}")
            writer.appendLine("acceptedFixes=${markerMotionSummary.acceptedFixes}")
            writer.appendLine("outlierDrops=${markerMotionSummary.outlierDrops}")
            writer.appendLine("predictionUpdates=${markerMotionSummary.predictionUpdates}")
            writer.appendLine("renderedMotionUpdates=${markerMotionSummary.renderedMotionUpdates}")
            writer.appendLine("firstRenderDelaySamples=${markerMotionSummary.firstRenderDelaySamples}")
            writer.appendLine(
                "firstRenderDelayMeanMs=${markerMotionSummary.firstRenderDelayMeanMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "firstRenderDelayMaxMs=${markerMotionSummary.firstRenderDelayMaxMs?.toString() ?: "na"}",
            )
            writer.appendLine("activeRenderIntervalSamples=${markerMotionSummary.activeRenderIntervalSamples}")
            writer.appendLine(
                "activeRenderIntervalMeanMs=${markerMotionSummary.activeRenderIntervalMeanMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "activeRenderIntervalP50Ms=${markerMotionSummary.activeRenderIntervalP50Ms?.toString() ?: "na"}",
            )
            writer.appendLine(
                "activeRenderIntervalP95Ms=${markerMotionSummary.activeRenderIntervalP95Ms?.toString() ?: "na"}",
            )
            writer.appendLine(
                "activeRenderIntervalMaxMs=${markerMotionSummary.activeRenderIntervalMaxMs?.toString() ?: "na"}",
            )
            writer.appendLine(
                "nextFixPredictionResidualM=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.nextFixPredictionResidualM, digits = 1),
            )
            writer.appendLine(
                "visibleNextFixPredictionResidualM=" +
                    formatMarkerMotionMetricSummary(
                        markerMotionSummary.visibleNextFixPredictionResidualM,
                        digits = 1,
                    ),
            )
            writer.appendLine(
                "screenOffNextFixPredictionResidualM=" +
                    formatMarkerMotionMetricSummary(
                        markerMotionSummary.screenOffNextFixPredictionResidualM,
                        digits = 1,
                    ),
            )
            writer.appendLine("correctionComponentSamples=${markerMotionSummary.correctionComponentSamples}")
            writer.appendLine(
                "correctionAlongTrackMeanM=" +
                    TelemetryFormatters.decimalOrNa(markerMotionSummary.correctionAlongTrackMeanM, 1),
            )
            writer.appendLine(
                "correctionCrossTrackMeanM=" +
                    TelemetryFormatters.decimalOrNa(markerMotionSummary.correctionCrossTrackMeanM, 1),
            )
            writer.appendLine(
                "correctionAlongTrackAbsM=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.correctionAlongTrackAbsM, digits = 1),
            )
            writer.appendLine(
                "correctionCrossTrackAbsM=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.correctionCrossTrackAbsM, digits = 1),
            )
            writer.appendLine(
                "renderDisplacementM=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.renderDisplacementM, digits = 2),
            )
            writer.appendLine(
                "renderDisplacementPx=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.renderDisplacementPx, digits = 2),
            )
            writer.appendLine(
                "correctionSettleDurationMs=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.correctionSettleDurationMs, digits = 0),
            )
            writer.appendLine("correctionInterruptedCount=${markerMotionSummary.correctionInterruptedCount}")
            writer.appendLine(
                "rawFilteredPositionOffsetM=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.rawFilteredPositionOffsetM, digits = 1),
            )
            writer.appendLine(
                "rawFilteredSpeedOffsetMps=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.rawFilteredSpeedOffsetMps, digits = 2),
            )
            writer.appendLine(
                "rawFilteredBearingOffsetDeg=" +
                    formatMarkerMotionMetricSummary(markerMotionSummary.rawFilteredBearingOffsetDeg, digits = 1),
            )
            writer.appendLine("modeDwellMs=${formatMarkerMotionModeDwell(markerMotionSummary.modeDwellMs)}")
            writer.appendLine("blendStarts=${markerMotionSummary.blendStarts}")
            writer.appendLine("clampedCorrections=${markerMotionSummary.clampedCorrections}")
            writer.appendLine("blockedTransitions=${markerMotionSummary.blockedTransitions}")
            writer.appendLine("innovationSamples=${markerMotionSummary.innovationSamples}")
            writer.appendLine(
                "innovationMeanM=${TelemetryFormatters.decimalOrNa(markerMotionSummary.innovationMeanM, 1)}",
            )
            writer.appendLine(
                "innovationMaxM=${TelemetryFormatters.decimalOrNa(markerMotionSummary.innovationMaxM, 1)}",
            )
            writer.appendLine("fixGapSamples=${markerMotionSummary.fixGapSamples}")
            writer.appendLine("fixGapMeanMs=${markerMotionSummary.fixGapMeanMs?.toString() ?: "na"}")
            writer.appendLine("fixGapMaxMs=${markerMotionSummary.fixGapMaxMs?.toString() ?: "na"}")
            writer.appendLine(
                "blockedReasons=${
                    formatMarkerMotionBlockedReasons(markerMotionSummary.blockedReasonCounts)
                }",
            )
            writer.appendLine("latestMode=${markerMotionSnapshot.mode.label}")
            writer.appendLine("latestReason=${markerMotionSnapshot.reason ?: "na"}")
            writer.appendLine("latestFixAgeMs=${markerMotionSnapshot.fixAgeMs?.toString() ?: "na"}")
            writer.appendLine(
                "latestAccuracyM=${
                    TelemetryFormatters.decimalOrNa(markerMotionSnapshot.accuracyM, 1)
                }",
            )
            writer.appendLine(
                "latestSpeedMps=${
                    TelemetryFormatters.decimalOrNa(markerMotionSnapshot.speedMps, 2)
                }",
            )
            writer.appendLine(
                "latestBearingDeg=${
                    TelemetryFormatters.decimalOrNa(markerMotionSnapshot.bearingDeg, 1)
                }",
            )
            writer.appendLine(
                "latestCorrectionDistanceM=${
                    TelemetryFormatters.decimalOrNa(markerMotionSnapshot.correctionDistanceM, 1)
                }",
            )
            writer.appendLine(
                "latestCorrectionAgeMs=${markerMotionSnapshot.correctionAgeMs?.toString() ?: "na"}",
            )
            writer.appendLine("latestUpdatedAtElapsedMs=${markerMotionSnapshot.updatedAtElapsedMs}")
            writer.appendLine()
            writer.appendLine("Marker Motion Events")
            writer.appendLine("Marker motion decisions are included in the Telemetry section under [MarkerMotion].")
            writer.appendLine()
            writer.appendLine("Performance Summary")
            writer.appendLine("skippedFrameEventCount=${performanceSummary.skippedFrameEventCount}")
            writer.appendLine("skippedFrameWarningCount=${performanceSummary.skippedFrameWarningCount}")
            writer.appendLine(
                "skippedFramesMax=${
                    if (performanceSummary.skippedFrameEventCount > 0) {
                        performanceSummary.skippedFramesMax.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine("daveyCount=${performanceSummary.daveyCount}")
            writer.appendLine(
                "daveyMaxDurationMs=${
                    if (performanceSummary.daveyCount > 0) {
                        performanceSummary.daveyMaxDurationMs.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine(
                "daveyAvgDurationMs=${
                    if (performanceSummary.daveyCount > 0) {
                        (performanceSummary.daveyTotalDurationMs / performanceSummary.daveyCount).toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine("gcEventCount=${performanceSummary.gcEventCount}")
            writer.appendLine(
                "gcMaxDurationMs=${
                    if (performanceSummary.gcEventCount > 0) {
                        performanceSummary.gcMaxDurationMs.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine(
                "gcAvgDurationMs=${
                    if (performanceSummary.gcEventCount > 0) {
                        (performanceSummary.gcTotalDurationMs / performanceSummary.gcEventCount).toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine(
                "gcMaxFreedKb=${
                    if (performanceSummary.gcEventCount > 0) {
                        performanceSummary.gcMaxFreedKb.toString()
                    } else {
                        "na"
                    }
                }",
            )
            writer.appendLine("resourceCloseWarningCount=${performanceSummary.resourceCloseWarningCount}")
            writer.appendLine()
            writer.appendLine("Map Hot Path Summary")
            writer.appendLine("eventCount=${mapHotPathSummary.eventCount}")
            writer.appendLine("bufferMaxLines=${mapHotPathSummary.maxBufferedLines}")
            writer.appendLine("droppedLines=${mapHotPathSummary.droppedLineCount}")
            writer.appendLine("truncated=$mapHotPathTruncated")
            writer.appendLine("stageCount=${mapHotPathSummary.stageCount}")
            writer.appendLine("slowEventCount=${mapHotPathSummary.slowEventCount}")
            writer.appendLine("errorEventCount=${mapHotPathSummary.errorEventCount}")
            writer.appendLine(
                "maxDurationMs=${
                    if (mapHotPathSummary.eventCount > 0) {
                        mapHotPathSummary.maxDurationMs.toString()
                    } else {
                        "na"
                    }
                }",
            )
            if (mapHotPathSummary.stageSummaries.isEmpty()) {
                writer.appendLine("No map hot path timings captured.")
            } else {
                mapHotPathSummary.stageSummaries.forEachIndexed { index, stage ->
                    writer.appendLine(
                        "stage[$index]=name=${stage.stage} count=${stage.count} avgMs=${stage.avgDurationMs} maxMs=${stage.maxDurationMs} slowCount=${stage.slowCount} errorCount=${stage.errorCount}",
                    )
                }
            }
            writer.appendLine()
            writer.appendLine("Map Hot Path Events")
            if (mapHotPathLines.isEmpty()) {
                writer.appendLine("No map hot path events captured yet.")
            } else {
                mapHotPathLines.forEach { line -> writer.appendLine(line) }
            }
            writer.appendLine()
            writer.appendLine("Historical Process Exit Reasons")
            writer.appendLine("apiSupported=${historicalExitReasons.apiSupported}")
            historicalExitReasons.captureError?.let { writer.appendLine("captureError=$it") }
            writer.appendLine("entryCount=${historicalExitReasons.entries.size}")
            if (historicalExitReasons.entries.isEmpty()) {
                writer.appendLine("No historical process exit reasons reported.")
            } else {
                historicalExitReasons.entries.forEachIndexed { index, entry ->
                    writer.appendLine(
                        "exit[$index]=timestamp=${formatCaptureTime(entry.timestampMs)} reason=${entry.reason} subReason=${entry.subReason} importance=${entry.importance} status=${entry.status} pssKb=${entry.pssKb} rssKb=${entry.rssKb} description=${entry.description ?: "na"}",
                    )
                    if (entry.traceInsightLines.isNotEmpty()) {
                        writer.appendLine("exit[$index].traceInsightLineCount=${entry.traceInsightLines.size}")
                        entry.traceInsightLines.forEachIndexed { traceIndex, line ->
                            writer.appendLine("exit[$index].traceInsight[$traceIndex]=$line")
                        }
                    }
                    if (entry.tracePreviewLines.isNotEmpty()) {
                        writer.appendLine("exit[$index].tracePreviewLineCount=${entry.tracePreviewLines.size}")
                        entry.tracePreviewLines.forEachIndexed { traceIndex, line ->
                            writer.appendLine("exit[$index].trace[$traceIndex]=$line")
                        }
                    }
                }
            }
            writer.appendLine()
            writer.appendLine("Last Fatal Crash")
            if (lastCrash.isNullOrBlank()) {
                writer.appendLine("No crash recorded.")
            } else {
                writer.appendLine(lastCrash)
            }
            writer.appendLine()
            writer.appendLine("App Logcat")
            writer.appendLine("pid=${logcatSnapshot.pid}")
            writer.appendLine("capturedLines=${logcatSnapshot.lines.size}")
            writer.appendLine("totalReadLines=${logcatSnapshot.totalReadLines}")
            writer.appendLine("truncated=${logcatSnapshot.truncated}")
            logcatSnapshot.captureError?.let { writer.appendLine("captureError=$it") }
            if (logcatSnapshot.lines.isEmpty()) {
                writer.appendLine("No app logcat lines captured.")
            } else {
                logcatSnapshot.lines.forEach { line -> writer.appendLine(line) }
            }
        }

        return file
    }

    fun latestExportFile(context: Context): File? {
        val dir = existingDiagnosticsDir(context) ?: return null
        return dir
            .listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith("glancemap_diagnostics_") &&
                    file.name.endsWith(".txt")
            }?.maxByOrNull { it.lastModified() }
    }

    fun exportedFileCount(context: Context): Int {
        val dir = existingDiagnosticsDir(context) ?: return 0
        return dir
            .listFiles()
            ?.count { file ->
                file.isFile &&
                    file.name.startsWith("glancemap_diagnostics_") &&
                    file.name.endsWith(".txt")
            }
            ?: 0
    }

    fun clearExportedFiles(context: Context) {
        val dir = existingDiagnosticsDir(context) ?: return
        dir.listFiles()?.forEach { file ->
            runCatching { if (file.isFile) file.delete() }
        }
    }

    private fun diagnosticsDir(context: Context): File {
        val dir = File(context.filesDir, "diagnostics")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun buildDeviceSlug(): String {
        val raw = "${Build.MANUFACTURER}_${Build.MODEL}"
        val normalized =
            raw
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "wear_watch" }
        return normalized.take(40)
    }

    private fun existingDiagnosticsDir(context: Context): File? {
        val dir = File(context.filesDir, "diagnostics")
        return if (dir.exists() && dir.isDirectory) dir else null
    }

    internal fun deriveAcceptedFixSummaries(lines: List<String>): AcceptedFixSummaries = deriveAcceptedFixSummariesFromLines(lines)

    internal fun inferObservedFixQuality(
        summary: AcceptedFixSummary,
        origin: String?,
        gnssInsights: GnssInsights,
    ): ObservedFixQualitySummary =
        inferObservedFixQualityFromSummary(
            summary = summary,
            origin = origin,
            gnssInsights = gnssInsights,
        )

    private fun formatPassiveExternalStatus(
        settings: DiagnosticsSettingsSnapshot,
        insights: TelemetryInsights,
    ): String =
        when {
            !settings.gpsPassiveLocationExperiment &&
                insights.requestBackendPassiveExternalCount == 0 -> "disabled"
            insights.requestBackendPassiveExternalCount == 0 -> "not_requested"
            insights.batchOriginPassiveExternalCount == 0 &&
                insights.passiveExternalSignalSampleCount == 0 -> "listening_no_callbacks"
            insights.passiveExternalSignalSampleCount == 0 -> "callbacks_no_valid_samples"
            insights.passiveExternalAcceptedSampleCount > 0 -> "fresh_fixes_accepted"
            insights.passiveExternalStaleSampleCount ==
                insights.passiveExternalSignalSampleCount -> "only_stale_samples"
            insights.passiveExternalRejectedSampleCount > 0 -> "samples_rejected"
            else -> "unknown"
        }

    private fun formatExternalHeartRateStatus(
        settings: DiagnosticsSettingsSnapshot,
        insights: TelemetryInsights,
    ): String =
        when {
            !settings.recordingExternalHeartRateLinked -> "not_linked"
            settings.recordingHeartRateSource == "WATCH" -> "watch_selected"
            insights.externalHeartRateBridgeStartCount == 0 -> "linked_not_started"
            insights.externalHeartRateConnectSkippedCount > 0 ->
                "connect_skipped_${insights.externalHeartRateLastConnectSkippedReason ?: "unknown"}"
            insights.externalHeartRateConnectRequestedCount == 0 -> "not_requested"
            insights.externalHeartRateConnectedCount == 0 -> "requested_not_connected"
            insights.externalHeartRateNotifyFailedCount > 0 -> "notify_failed"
            insights.externalHeartRateServiceFailureCount > 0 -> "service_discovery_failed"
            insights.externalHeartRateMeasurementMissingCount > 0 -> "measurement_missing"
            insights.externalHeartRateSampleCount > 0 -> "samples_received"
            insights.externalHeartRateNotifyRequestedCount > 0 -> "notifications_requested_no_samples"
            else -> "connected_no_samples"
        }

    private fun formatExternalRunPodStatus(
        settings: DiagnosticsSettingsSnapshot,
        insights: TelemetryInsights,
    ): String =
        initialExternalRunPodStatus(settings, insights)
            ?: connectedExternalRunPodStatus(insights)

    private fun initialExternalRunPodStatus(
        settings: DiagnosticsSettingsSnapshot,
        insights: TelemetryInsights,
    ): String? {
        val runPodSelected = isRunPodSelected(settings)
        return when {
            !settings.recordingExternalRunPodLinked -> "not_linked"
            !runPodSelected && insights.externalRunPodBridgeStartCount == 0 -> "linked_not_selected"
            insights.externalRunPodBridgeStartCount == 0 -> "linked_not_started"
            else -> null
        }
    }

    private fun connectedExternalRunPodStatus(insights: TelemetryInsights): String =
        when {
            insights.externalRunPodConnectSkippedCount > 0 ->
                "connect_skipped_${insights.externalRunPodLastConnectSkippedReason ?: "unknown"}"
            insights.externalRunPodConnectRequestedCount == 0 -> "not_requested"
            insights.externalRunPodConnectedCount == 0 -> "requested_not_connected"
            insights.externalRunPodNotifyFailedCount > 0 -> "notify_failed"
            insights.externalRunPodServiceFailureCount > 0 -> "service_discovery_failed"
            insights.externalRunPodMeasurementMissingCount > 0 -> "measurement_missing"
            insights.externalRunPodSampleCount > 0 -> "samples_received"
            insights.externalRunPodNotifyRequestedCount > 0 -> "notifications_requested_no_samples"
            else -> "connected_no_samples"
        }

    private fun isRunPodSelected(settings: DiagnosticsSettingsSnapshot): Boolean =
        settings.recordingCadenceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD ||
            settings.recordingSpeedSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD ||
            settings.recordingDistanceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD

    private fun hasBluetoothScanPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun recordingGuidanceSessionMode(insights: TelemetryInsights): String {
        val recordingObserved = recordingSessionObserved(insights)
        val guidanceObserved = insights.turnByTurnActiveSampleCount > 0
        return when {
            recordingObserved && guidanceObserved -> "recordingAndGuidance"
            recordingObserved -> "recordingOnly"
            guidanceObserved -> "guidanceOnly"
            else -> "none"
        }
    }

    private fun recordingSessionObserved(insights: TelemetryInsights): Boolean =
        insights.recordingStartCount > 0 ||
            insights.recordingRecoveredCount > 0 ||
            insights.recordingPointSampleCount > 0 ||
            insights.recordingSaveStartCount > 0 ||
            insights.recordingSaveSuccessCount > 0 ||
            insights.recordingDiscardCount > 0

    private fun observedExternalSensors(insights: TelemetryInsights): String {
        val observedSensors = mutableListOf<String>()
        if (insights.externalHeartRateSampleCount > 0) observedSensors += "heart_rate"
        if (insights.externalRunPodSampleCount > 0) observedSensors += "run_pod"
        return observedSensors.ifEmpty { listOf("none") }.joinToString(",")
    }

    private fun formatMarkerMotionBlockedReasons(reasonCounts: Map<String, Int>): String =
        if (reasonCounts.isEmpty()) {
            "none"
        } else {
            reasonCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString(separator = ",") { (reason, count) -> "$reason:$count" }
        }

    private fun formatMarkerMotionMetricSummary(
        summary: MarkerMotionMetricSummary,
        digits: Int,
    ): String =
        if (summary.samples <= 0) {
            "samples:0"
        } else {
            buildString {
                append("samples:${summary.samples}")
                append(",mean:${TelemetryFormatters.decimalOrNa(summary.mean, digits)}")
                append(",p50:${TelemetryFormatters.decimalOrNa(summary.p50, digits)}")
                append(",p95:${TelemetryFormatters.decimalOrNa(summary.p95, digits)}")
                append(",max:${TelemetryFormatters.decimalOrNa(summary.max, digits)}")
            }
        }

    private fun formatMarkerMotionModeDwell(modeDwellMs: Map<MarkerMotionMode, Long>): String =
        modeDwellMs.entries
            .filter { (_, dwellMs) -> dwellMs > 0L }
            .sortedBy { (mode, _) -> mode.ordinal }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ",") { (mode, dwellMs) -> "${mode.label}:$dwellMs" }
            ?: "none"
}

internal fun normalizeThreadtimeLogcatLine(
    line: String,
    capturedAt: Instant,
    zoneId: ZoneId,
): String {
    val match = threadtimeLineRegex.matchEntire(line) ?: return line
    val month = match.groupValues[1].toIntOrNull() ?: return line
    val day = match.groupValues[2].toIntOrNull() ?: return line
    val time =
        runCatching {
            LocalTime.parse(match.groupValues[3], threadtimeTimeFormatter)
        }.getOrNull() ?: return line
    val suffix = match.groupValues[4]
    val capturedYear = capturedAt.atZone(zoneId).year
    val inferredDateTime =
        inferThreadtimeDateTime(
            month = month,
            day = day,
            time = time,
            capturedAt = capturedAt,
            zoneId = zoneId,
            baseYear = capturedYear,
        ) ?: return line
    return "${normalizedLogcatTimestampFormatter.format(inferredDateTime)}$suffix"
}

internal fun inferThreadtimeDateTime(
    month: Int,
    day: Int,
    time: LocalTime,
    capturedAt: Instant,
    zoneId: ZoneId,
    baseYear: Int,
): LocalDateTime? {
    val candidates =
        sequenceOf(baseYear - 1, baseYear, baseYear + 1)
            .mapNotNull { year ->
                runCatching {
                    ZonedDateTime.of(year, month, day, time.hour, time.minute, time.second, time.nano, zoneId)
                }.getOrNull()
            }.toList()
    if (candidates.isEmpty()) return null
    val best =
        candidates.minByOrNull { candidate ->
            kotlin.math.abs(Duration.between(candidate.toInstant(), capturedAt).toMillis())
        } ?: return null
    return best.toLocalDateTime()
}
