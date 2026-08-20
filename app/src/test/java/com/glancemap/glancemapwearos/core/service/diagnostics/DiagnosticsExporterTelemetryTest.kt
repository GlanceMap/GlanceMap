package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.ZoneId

class DiagnosticsExporterTelemetryTest {
    @Test
    fun requestClearedLineStopsModeAndBackendDurations() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [LocTelemetry] requestUpdates applied: " +
                    "priority=100 intervalMs=1000 minDistanceM=1.0 state=ACTIVE " +
                    "bound=false keepOpen=true watchOnly=true burst=true backend=watch_gps " +
                    "mode=BURST trackingEnabled=true interactive=true screenState=INTERACTIVE " +
                    "finePermission=true coarsePermission=true passivePriority=false",
                "2026-04-20 20:07:14.000 [LocTelemetry] requestUpdates cleared: " +
                    "reason=tracking_disabled bound=false keepOpen=true trackingEnabled=false " +
                    "screenState=SCREEN_OFF backgroundGpsEnabled=false",
                "2026-04-20 20:07:24.000 [LocTelemetry] requestUpdates applied: " +
                    "priority=100 intervalMs=3000 minDistanceM=1.0 state=ACTIVE " +
                    "bound=false keepOpen=true watchOnly=true burst=false backend=watch_gps " +
                    "mode=INTERACTIVE trackingEnabled=true interactive=true screenState=INTERACTIVE " +
                    "finePermission=true coarsePermission=true passivePriority=false",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:29"),
            )

        assertEquals(2_000L, insights.requestModeBurstDurationMs)
        assertEquals(5_000L, insights.requestModeOtherwiseDurationMs)
        assertEquals(7_000L, insights.requestBackendWatchGpsDurationMs)
        assertEquals(7_000L, insights.requestBackendDurationCoverageMs)
    }

    @Test
    fun legacyTrackingDisabledLineStopsActiveRequestDuration() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [LocTelemetry] requestUpdates applied: " +
                    "priority=100 intervalMs=1000 minDistanceM=1.0 state=ACTIVE " +
                    "bound=false keepOpen=true watchOnly=true burst=true backend=watch_gps " +
                    "mode=BURST trackingEnabled=true interactive=true screenState=INTERACTIVE " +
                    "finePermission=true coarsePermission=true passivePriority=false",
                "2026-04-20 20:07:14.500 [LocTelemetry] tracking: enabled=false",
                "2026-04-20 20:07:24.000 [LocTelemetry] requestUpdates applied: " +
                    "priority=100 intervalMs=3000 minDistanceM=1.0 state=ACTIVE " +
                    "bound=false keepOpen=true watchOnly=true burst=false backend=watch_gps " +
                    "mode=INTERACTIVE trackingEnabled=true interactive=true screenState=INTERACTIVE " +
                    "finePermission=true coarsePermission=true passivePriority=false",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:29"),
            )

        assertEquals(2_500L, insights.requestModeBurstDurationMs)
        assertEquals(5_000L, insights.requestModeOtherwiseDurationMs)
        assertEquals(7_500L, insights.requestBackendWatchGpsDurationMs)
    }

    @Test
    fun requestClearedLineUpdatesLastObservedTrackingState() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [LocTelemetry] requestUpdates applied: " +
                    "priority=100 intervalMs=1000 minDistanceM=1.0 state=ACTIVE " +
                    "bound=false keepOpen=true watchOnly=true burst=true backend=watch_gps " +
                    "mode=BURST trackingEnabled=true interactive=true screenState=INTERACTIVE " +
                    "finePermission=true coarsePermission=true passivePriority=false",
                "2026-04-20 20:07:14.000 [LocTelemetry] requestUpdates cleared: " +
                    "reason=tracking_disabled bound=false keepOpen=true trackingEnabled=false " +
                    "screenState=INTERACTIVE backgroundGpsEnabled=false",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:29"),
            )

        assertEquals(false, insights.lastObservedTrackingEnabled)
        assertEquals(2_000L, insights.requestModeBurstDurationMs)
        assertEquals(2_000L, insights.requestBackendWatchGpsDurationMs)
    }

    @Test
    fun immediateGuardAndWakeDebounceCountersAreSummarized() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [LocTelemetry] immediateRequest: " +
                    "skipGuard source=ui_startup_fresh_fix_ambient_exit_after_bind " +
                    "reason=tracking_disabled screenState=SCREEN_OFF trackingEnabled=false",
                "2026-04-20 20:07:13.000 [LocTelemetry] immediateRequest: " +
                    "deferWakeBurst source=ui_startup_fresh_fix_ambient_exit_after_bind " +
                    "delayMs=320 screenState=INTERACTIVE trackingEnabled=true",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:29"),
            )

        assertEquals(1, insights.immediateRequestGuardSkipCount)
        assertEquals(1, insights.immediateRequestDeferredWakeBurstCount)
    }

    @Test
    fun passiveExternalGpsSignalSamplesAreSummarized() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [LocTelemetry] requestUpdates applied: " +
                    "priority=105 intervalMs=3000 minDistanceM=0.0 state=ACTIVE " +
                    "bound=false keepOpen=true watchOnly=false burst=false backend=passive_external " +
                    "mode=INTERACTIVE trackingEnabled=true interactive=true screenState=INTERACTIVE " +
                    "finePermission=true coarsePermission=true passivePriority=true",
                "2026-04-20 20:07:13.000 [LocTelemetry] locationBatch: raw=1 normalized=1 " +
                    "accepted=0 fallback=false origin=passive_external duplicatesDropped=0",
                "2026-04-20 20:07:13.000 [LocTelemetry] gpsSignal: sample " +
                    "ageMs=34118 fresh=false maxAgeMs=20000 accuracyM=125.0 " +
                    "sourceMode=passive_external provider=gps accepted=false " +
                    "watchGpsDegraded=false watchGpsDegradedFixStreak=0",
                "2026-04-20 20:07:16.000 [LocTelemetry] gpsSignal: sample " +
                    "ageMs=100 fresh=true maxAgeMs=20000 accuracyM=8.5 " +
                    "sourceMode=passive_external provider=fused accepted=true " +
                    "watchGpsDegraded=false watchGpsDegradedFixStreak=0",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:29"),
            )

        assertEquals(1, insights.requestBackendPassiveExternalCount)
        assertEquals(1, insights.batchOriginPassiveExternalCount)
        assertEquals(2, insights.passiveExternalSignalSampleCount)
        assertEquals(1, insights.passiveExternalFreshSampleCount)
        assertEquals(1, insights.passiveExternalStaleSampleCount)
        assertEquals(1, insights.passiveExternalAcceptedSampleCount)
        assertEquals(1, insights.passiveExternalRejectedSampleCount)
        assertEquals(100L, insights.passiveExternalLastAgeMs)
        assertEquals(100L, insights.passiveExternalMinAgeMs)
        assertEquals(34_118L, insights.passiveExternalMaxAgeMs)
        assertEquals(20_000L, insights.passiveExternalLastMaxAgeMs)
        assertEquals(8.5f, insights.passiveExternalLastAccuracyM)
        assertEquals("fused", insights.passiveExternalLastProvider)
    }

    @Test
    fun watchGpsSelfHealFirstCallbackGraceTelemetryIsSummarized() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [LocTelemetry] watchGpsSelfHeal: skipped " +
                    "phase=burst reason=await_first_callback searchAgeMs=16000 graceMs=120000 " +
                    "fixGapMs=16000 staleThresholdMs=12000 expectedIntervalMs=3000 state=ACTIVE",
                "2026-04-20 20:09:22.000 [LocTelemetry] watchGpsSelfHeal: restarting " +
                    "phase=burst reason=first_callback_grace_expired searchAgeMs=129000 " +
                    "graceMs=120000 fixGapMs=129000 staleThresholdMs=12000 " +
                    "expectedIntervalMs=3000 state=ACTIVE",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:09:29"),
            )

        assertEquals(1, insights.watchGpsSelfHealSkippedCount)
        assertEquals(1, insights.watchGpsSelfHealRestartCount)
        assertEquals(129_000L, insights.watchGpsSelfHealMaxSearchAgeMs)
    }

    @Test
    fun recordingPointSampleCountFallsBackToObservedRecordingPointTotals() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [TraceRecording] event=point " +
                    "points=1 distanceMeters=0 accuracyMeters=125",
                "2026-04-20 20:07:32.000 [TraceRecording] event=pause " +
                    "points=6 distanceMeters=34 gpsDistanceMeters=34 displayDistanceMeters=34",
                "2026-04-20 20:07:34.000 [TraceRecording] event=save_success " +
                    "points=6 distanceMeters=34 gpsDistanceMeters=34 displayDistanceMeters=34",
                "2026-04-20 20:07:34.100 [TraceRecording] event=saved_gpx_verified " +
                    "writtenPoints=6 parsedPoints=6 summaryPoints=6 summaryDistanceMeters=34",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:39"),
            )

        assertEquals(6, insights.recordingPointSampleCount)
        assertEquals(6, insights.recordingMaxPointCount)
        assertEquals(6, insights.recordingSavedGpxWrittenPoints)
    }

    @Test
    fun recordingGapEndpointDistanceTelemetryIsSummarized() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [TraceRecording] event=gap gapMs=16000 " +
                    "gapEndpointDistanceM=3.0 gapPreviousSpeedMps=0.00 gapCurrentSpeedMps=0.00",
                "2026-04-20 20:07:32.000 [TraceRecording] event=gap gapMs=17000 " +
                    "gapEndpointDistanceM=21.0 gapPreviousSpeedMps=4.00 gapCurrentSpeedMps=4.50",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:39"),
            )

        assertEquals(2, insights.recordingGapEventCount)
        assertEquals(2, insights.recordingGapEndpointDistanceSampleCount)
        assertEquals(12f, insights.recordingGapEndpointDistanceAvgMeters)
        assertEquals(21f, insights.recordingGapEndpointDistanceMaxMeters)
    }

    @Test
    fun recordingTrackFilterTelemetryIsSummarized() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [TraceRecording] event=start " +
                    "trackSmoothingMode=ADAPTIVE trackFilterVersion=1",
                "2026-04-20 20:07:15.000 [TraceRecording] event=fix_quality_held " +
                    "reason=implausible_jump held=1 rejected=0",
                "2026-04-20 20:07:18.000 [TraceRecording] event=point points=5 " +
                    "qualityHeld=1 qualityRejected=0 qualityRelocations=0 smoothedPoints=2",
                "2026-04-20 20:07:20.000 [TraceRecording] event=pause points=6 " +
                    "trackSmoothingMode=ADAPTIVE trackFilterVersion=1 " +
                    "qualityHeldFixCount=1 qualityRejectedFixCount=0 qualityRelocationCount=0 " +
                    "smoothedPointCount=3 smoothedAdjustmentMeters=2.4 maxSmoothedAdjustmentMeters=1.1 " +
                    "smartTrackMotionEvaluatedFixCount=12 smartTrackAcceptedReportedSpeedCount=7 " +
                    "smartTrackAcceptedSensorCount=2 smartTrackAcceptedConfirmedSlowCount=1 " +
                    "smartTrackSuppressedStationaryCount=1 smartTrackHeldSlowCount=1 " +
                    "smartTrackSegmentStartBypassCount=0 smartTrackStepMotionEvidenceCount=2 " +
                    "smartTrackCadenceMotionEvidenceCount=0 smartTrackSpeedAboveThresholdCount=8 " +
                    "smartTrackCredibleSpeedCount=7 smartTrackNoMotionSensorDataCount=10 " +
                    "smartTrackStationaryRadiusSampleCount=3 smartTrackStationaryRadiusAvgMeters=4.20 " +
                    "smartTrackStationaryRadiusMaxMeters=5.10 " +
                    "smartTrackNonAcceptedDisplacementSampleCount=2 " +
                    "smartTrackNonAcceptedDisplacementAvgMeters=4.70 " +
                    "smartTrackNonAcceptedDisplacementMaxMeters=5.30 " +
                    "smartTrackPoorAccuracyRejectedCount=2 smartTrackNonMonotonicRejectedCount=0 " +
                    "smartTrackImplausibleJumpHeldCount=1 " +
                    "smartTrackConfirmedSustainedMovementCount=1 " +
                    "smartTrackAdaptiveAccuracyFixCount=6 smartTrackAccuracyBaselineSampleCount=9 " +
                    "smartTrackAccuracyBaselineMedianMeters=24.0 " +
                    "smartTrackAccuracyProfileLimitMeters=35.0 " +
                    "smartTrackAccuracyResolvedLimitMeters=45.0 " +
                    "smartTrackAdaptiveAccuracyLimitActive=true",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:29"),
            )

        assertEquals("ADAPTIVE", insights.recordingTrackFilter.smoothingMode)
        assertEquals(1, insights.recordingTrackFilter.filterVersion)
        assertEquals(1, insights.recordingTrackFilter.qualityHeldFixCount)
        assertEquals(0, insights.recordingTrackFilter.qualityRejectedFixCount)
        assertEquals(0, insights.recordingTrackFilter.qualityRelocationCount)
        assertEquals(3, insights.recordingTrackFilter.smoothedPointCount)
        assertEquals("2.4", insights.recordingTrackFilter.smoothedAdjustmentMeters)
        assertEquals("1.1", insights.recordingTrackFilter.maxSmoothedAdjustmentMeters)
        val smartTrack = insights.recordingTrackFilter.smartTrack
        assertEquals(12, smartTrack.motionEvaluatedFixCount)
        assertEquals(7, smartTrack.acceptedReportedSpeedCount)
        assertEquals(1, smartTrack.suppressedStationaryCount)
        assertEquals(1, smartTrack.heldSlowCount)
        assertEquals("4.20", smartTrack.stationaryRadiusAvgMeters)
        assertEquals("5.30", smartTrack.nonAcceptedDisplacementMaxMeters)
        assertEquals(2, smartTrack.poorAccuracyRejectedCount)
        assertEquals(9, smartTrack.accuracyBaselineSampleCount)
        assertEquals("45.0", smartTrack.accuracyResolvedLimitMeters)
        assertEquals(true, smartTrack.adaptiveAccuracyLimitActive)
    }

    @Test
    fun recordingSmartTrackTelemetryIsWrittenToDiagnosticReport() {
        val output = StringWriter()
        BufferedWriter(output).use { writer ->
            writeRecordingSmartTrackSection(
                writer = writer,
                insights =
                    DiagnosticsExporter.RecordingSmartTrackInsights(
                        motionEvaluatedFixCount = 12,
                        suppressedStationaryCount = 3,
                        accuracyResolvedLimitMeters = "45.0",
                        adaptiveAccuracyLimitActive = true,
                    ),
            )
        }

        assertEquals(true, "recordingSmartTrackMotionEvaluatedFixCount=12" in output.toString())
        assertEquals(true, "recordingSmartTrackSuppressedStationaryCount=3" in output.toString())
        assertEquals(true, "recordingSmartTrackAccuracyResolvedLimitMeters=45.0" in output.toString())
        assertEquals(true, "recordingSmartTrackAdaptiveAccuracyLimitActive=true" in output.toString())
    }

    @Test
    fun turnAlertOutcomesAreSummarizedSeparately() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [TurnByTurn] haptic=turn turnAlert=fired trigger=window",
                "2026-04-20 20:07:13.000 [TurnByTurn] turnAlert=filtered trigger=window reason=turn_mode",
                "2026-04-20 20:07:14.000 [TurnByTurn] turnAlert=off_route trigger=window reason=off_route",
                "2026-04-20 20:07:15.000 [TurnByTurn] turnAlert=missed_window trigger=crossing",
            )

        val insights =
            deriveTelemetryInsights(
                lines = lines,
                captureWindowEndEpochMs = epochMs("2026-04-20T20:07:19"),
            )

        assertEquals(1, insights.turnByTurnTurnHapticCount)
        assertEquals(1, insights.turnByTurnTurnAlertFiredCount)
        assertEquals(1, insights.turnByTurnTurnAlertFilteredCount)
        assertEquals(1, insights.turnByTurnTurnAlertOffRouteCount)
        assertEquals(1, insights.turnByTurnTurnAlertMissedWindowCount)
    }

    @Test
    fun compassStartupExperienceTelemetryIsSummarized() {
        val lines =
            listOf(
                "2026-04-20 20:07:12.000 [CompassTelemetry] wake_session stage=startup_summary " +
                    "id=1 windowMs=5000 samples=140 headingSpanDeg=286.0 maxJumpDeg=74.0 " +
                    "cumulativeHeadingRotationDeg=721.0 directionReversals=8 cumulativeMapRotationDeg=610.0 " +
                    "visibleHeadingMaxJumpDeg=68.0 visibleMapRotationMaxJumpDeg=63.0 " +
                    "sourceHandoffs=2 sourceHandoffMaxJumpDeg=52.0 " +
                    "renderErrorAvgDeg=2.4 renderErrorMaxDeg=18.0 stable3Ms=na stable5Ms=4300 fusedReadyMs=450",
                "2026-04-20 20:07:12.100 [CompassTelemetry] google_fused first_usable " +
                    "reason=start latencyMs=108 heading=120.0 errorDeg=25.0",
                "2026-04-20 20:07:12.140 [CompassTelemetry] google_fused warmup_relock " +
                    "stepDeg=149.0 allowedStepDeg=50.0 gapMs=20 previous=190.0 " +
                    "heading=41.0 reset=1",
                "2026-04-20 20:07:12.250 [CompassTelemetry] google_fused state " +
                    "transition=active_fused from=starting_fused reason=warmup_complete " +
                    "latencyMs=244 warmupMs=136 usableSamples=7 stableMs=120 " +
                    "stableSamples=6 relockResets=1",
                "2026-04-20 20:07:12.260 [CompassTelemetry] map_heading_continuity stage=start " +
                    "reason=source_ready provider=GOOGLE_FUSED source=google_fused " +
                    "displayed=255.0 raw=120.0 offsetDeg=135.0",
                "2026-04-20 20:07:14.000 [CompassTelemetry] wake_session stage=startup_summary " +
                    "id=2 windowMs=5000 samples=150 headingSpanDeg=42.0 maxJumpDeg=12.0 " +
                    "cumulativeHeadingRotationDeg=90.0 directionReversals=2 cumulativeMapRotationDeg=80.0 " +
                    "visibleHeadingMaxJumpDeg=9.0 visibleMapRotationMaxJumpDeg=11.0 " +
                    "sourceHandoffs=1 sourceHandoffMaxJumpDeg=8.0 " +
                    "renderErrorAvgDeg=0.8 renderErrorMaxDeg=3.0 stable3Ms=1600 stable5Ms=1200 fusedReadyMs=420",
                "2026-04-20 20:07:15.000 [CompassTelemetry] google_fused first_usable " +
                    "reason=start latencyMs=219 heading=44.0 errorDeg=25.0",
                "2026-04-20 20:07:15.230 [CompassTelemetry] google_fused state " +
                    "transition=active_fused from=starting_fused reason=warmup_complete " +
                    "latencyMs=450 warmupMs=231 usableSamples=12 stableMs=231 " +
                    "stableSamples=12 relockResets=0",
                "2026-04-20 20:07:15.240 [CompassTelemetry] map_heading_continuity stage=start " +
                    "reason=source_ready provider=GOOGLE_FUSED source=google_fused " +
                    "displayed=4.0 raw=44.0 offsetDeg=-40.0",
                "2026-04-20 20:07:15.540 [CompassTelemetry] map_heading_continuity stage=cancel " +
                    "reason=heading_drive_inactive durationMs=300 remainingOffsetDeg=-8.0",
                "2026-04-20 20:07:15.760 [CompassTelemetry] map_heading_continuity stage=complete " +
                    "durationMs=760 initialOffsetDeg=135.0 heading=120.0 raw=120.0",
                "2026-04-20 20:07:15.800 [CompassTelemetry] google_fused state " +
                    "transition=active_fallback from=active_fused reason=sample_stale",
                "2026-04-20 20:07:16.000 [CompassTelemetry] user_report heading_looks_wrong " +
                    "source=google_fused heading=220.0 rendered=219.0 mapRotation=-219.0 accuracy=1",
            )

        val insights = deriveCompassTelemetryInsights(lines)

        assertCompassStartupInsights(insights)
    }

    private fun assertCompassStartupInsights(
        insights: DiagnosticsExporter.CompassTelemetryInsights,
    ) {
        assertEquals(2, insights.startupSummaryCount)
        assertEquals(286f, insights.startupHeadingSpanMaxDeg)
        assertEquals(74f, insights.startupMaxJumpMaxDeg)
        assertEquals(68f, insights.startupVisibleHeadingJumpMaxDeg)
        assertEquals(63f, insights.startupVisibleMapRotationJumpMaxDeg)
        assertEquals(3, insights.startupSourceHandoffCount)
        assertEquals(52f, insights.startupSourceHandoffMaxJumpDeg)
        assertEquals(1, insights.startupStable3Count)
        assertEquals(2, insights.startupStable5Count)
        assertEquals(2, insights.fusedFirstUsableCount)
        assertEquals(219L, insights.fusedFirstUsableLatencyMaxMs)
        assertEquals(2, insights.fusedReadyCount)
        assertEquals(450L, insights.fusedReadyLatencyMaxMs)
        assertEquals(1, insights.fusedWarmupRelockCount)
        assertEquals(149f, insights.fusedWarmupRelockStepMaxDeg)
        assertEquals(1, insights.fusedReadyAfterRelockCount)
        assertEquals(1, insights.fusedFallbackActivationCount)
        assertEquals(2, insights.continuityStartCount)
        assertEquals(1, insights.continuityCompleteCount)
        assertEquals(1, insights.continuityCancelCount)
        assertEquals(135f, insights.continuityInitialOffsetMaxDeg)
        assertEquals(760L, insights.continuityDurationMaxMs)
        assertEquals(1, insights.headingLooksWrongReportCount)
    }

    @Test
    fun compassRenderTelemetryIncludesMapsforgeRotationThrottleCount() {
        val insights =
            deriveCompassTelemetryInsights(
                listOf(
                    "2026-07-09 09:00:00.000 [CompassTelemetry] compass_render perf " +
                        "windowMs=5000 navMode=COMPASS_FOLLOW frames=250 frameHz=50.0 " +
                        "targetUpdates=120 headingRenders=180 renderHz=36.0 rotationApplied=120 " +
                        "rotationSkipped=40 rotationThrottled=60 markerUpdates=0 redraws=180",
                ),
            )

        assertEquals(1, insights.renderPerfEventCount)
        assertEquals(120, insights.renderPerfRotationAppliedCount)
        assertEquals(40, insights.renderPerfRotationSkippedCount)
        assertEquals(60, insights.renderPerfRotationThrottledCount)
    }

    @Test
    fun compassRotationSettleTelemetrySummarizesWakeHoldsAndUnlocks() {
        val insights =
            deriveCompassTelemetryInsights(
                listOf(
                    "2026-08-12 17:54:27.000 [CompassTelemetry] rotation_settle stage=start " +
                        "id=1 heldHeading=120.0",
                    "2026-08-12 17:54:27.010 [CompassTelemetry] rotation_settle stage=hold " +
                        "id=1 reason=await_heading_consensus headingDeltaDeg=na",
                    "2026-08-12 17:54:27.650 [CompassTelemetry] rotation_settle stage=hold " +
                        "id=1 reason=large_unverified_stationary_change headingDeltaDeg=81.5",
                    "2026-08-12 17:54:27.720 [CompassTelemetry] rotation_settle stage=unlock " +
                        "id=1 reason=relative_turn_confirmed heading=201.5",
                    "2026-08-12 17:54:27.730 [CompassTelemetry] rotation_settle stage=release " +
                        "id=1 wakeHoldDurationMs=710 wakeReleaseReason=settle_timeout " +
                        "wakeReleaseHeadingDeltaDeg=81.5 firstVisibleReleaseStepDeg=10.0",
                ),
            )

        assertEquals(1, insights.rotationSettleSessionStartCount)
        assertEquals(2, insights.rotationSettleHoldCount)
        assertEquals(1, insights.rotationSettleUnlockCount)
        assertEquals(
            "await_heading_consensus:1,large_unverified_stationary_change:1",
            insights.rotationSettleHoldReasons,
        )
        assertEquals("relative_turn_confirmed:1", insights.rotationSettleUnlockReasons)
        assertEquals(81.5f, insights.rotationSettleHoldMaxHeadingDeltaDeg)
        assertEquals(1, insights.rotationSettleReleaseCount)
        assertEquals("settle_timeout:1", insights.rotationSettleReleaseReasons)
        assertEquals(710L, insights.rotationSettleWakeHoldDurationMaxMs)
        assertEquals(81.5f, insights.rotationSettleReleaseHeadingDeltaMaxDeg)
        assertEquals(10f, insights.rotationSettleFirstVisibleReleaseStepMaxDeg)
    }

    @Test
    fun compassHeadingSampleCountIsExplicitlyDiagnosticOnly() {
        val insights =
            deriveCompassTelemetryInsights(
                listOf(
                    "2026-07-09 09:00:00.000 [CompassTelemetry] heading raw=1.0 smoothed=1.0",
                    "2026-07-09 09:00:05.000 [CompassTelemetry] google_fused sample heading=2.0",
                    "2026-07-09 09:00:05.000 [CompassTelemetry] google_fused perf " +
                        "windowMs=5000 callbacks=250 confirmed=245 unusable=0 headingPublishes=120",
                    "2026-07-09 09:00:06.000 [CompassTelemetry] google_fused sample_stale " +
                        "ageMs=1500 recoveryAttempted=false",
                    "2026-07-09 09:00:06.010 [CompassTelemetry] google_fused bootstrap activate " +
                        "reason=sample_stale_retry",
                ),
            )

        assertEquals(2, insights.headingSampleCount)
        assertEquals(2, insights.headingDiagnosticSampleCount)
        assertEquals(245, insights.fusedPerfConfirmedCount)
        assertEquals(1, insights.staleSampleCount)
    }

    @Test
    fun gnssAcquisitionTelemetryDistinguishesSignalEphemerisAndFixStages() {
        val insights =
            deriveGnssInsights(
                listOf(
                    "2026-08-02 16:00:00.000 event=acquisition_signal_detected afterRegisterMs=1200 signalSats=3",
                    "2026-08-02 16:00:01.000 status sats=12 used=0 signal=0 almanac=0 ephemeris=0 acquisition=no_radio_signal cn0Avg=na cn0Max=na carrier=0 l1=0 l5=0 dual=false gps=0 gal=0 glo=0 bds=0 qzss=0 sbas=0 unk=12",
                    "2026-08-02 16:00:02.000 status sats=12 used=0 signal=3 almanac=3 ephemeris=0 acquisition=signals_no_ephemeris cn0Avg=18.0 cn0Max=21.0 carrier=0 l1=0 l5=0 dual=false gps=0 gal=0 glo=0 bds=0 qzss=0 sbas=0 unk=12",
                    "2026-08-02 16:00:03.000 event=acquisition_ephemeris_available afterRegisterMs=3200 ephemerisSats=2",
                    "2026-08-02 16:00:03.000 status sats=12 used=0 signal=4 almanac=4 ephemeris=2 acquisition=ephemeris_no_fix cn0Avg=20.0 cn0Max=23.0 carrier=0 l1=0 l5=0 dual=false gps=0 gal=0 glo=0 bds=0 qzss=0 sbas=0 unk=12",
                    "2026-08-02 16:00:03.100 event=status_location_disagreement " +
                        "kind=signals_without_fresh_location sourceMode=watch_gps used=0 " +
                        "signal=4 fixFresh=false fixAgeMs=na fixAccuracyM=na",
                    "2026-08-02 16:00:04.000 event=acquisition_satellites_used afterRegisterMs=4200 used=4",
                    "2026-08-02 16:00:04.000 status sats=12 used=4 signal=5 almanac=5 ephemeris=4 acquisition=satellites_used cn0Avg=21.0 cn0Max=25.0 carrier=0 l1=0 l5=0 dual=false gps=0 gal=0 glo=0 bds=0 qzss=0 sbas=0 unk=12",
                    "2026-08-02 16:00:04.100 event=status_location_disagreement " +
                        "kind=used_zero_with_fresh_location sourceMode=watch_gps used=0 " +
                        "signal=5 fixFresh=true fixAgeMs=1000 fixAccuracyM=8.0",
                ),
            )

        assertEquals(4, insights.statusSampleCount)
        assertEquals(3.0, insights.signalSatellitesAvg, 0.001)
        assertEquals(5, insights.signalSatellitesMax)
        assertEquals(1.5, insights.ephemerisSatellitesAvg, 0.001)
        assertEquals(4, insights.ephemerisSatellitesMax)
        assertEquals(1, insights.noRadioSignalStatusCount)
        assertEquals(1, insights.signalsNoEphemerisStatusCount)
        assertEquals(1, insights.ephemerisNoFixStatusCount)
        assertEquals(1, insights.satellitesUsedStatusCount)
        assertEquals(1, insights.acquisitionSignalDetectedCount)
        assertEquals(1, insights.acquisitionEphemerisAvailableCount)
        assertEquals(1, insights.acquisitionSatellitesUsedCount)
        assertEquals(1, insights.usedZeroWithFreshLocationCount)
        assertEquals(1, insights.signalsWithoutFreshLocationCount)
    }

    private fun epochMs(localDateTime: String): Long =
        LocalDateTime
            .parse(localDateTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
