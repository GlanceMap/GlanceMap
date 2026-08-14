package com.glancemap.glancemapwearos.core.service.location.service

import com.glancemap.glancemapwearos.core.service.location.engine.LocationEngine
import com.glancemap.glancemapwearos.core.service.location.engine.RequestSpec
import com.glancemap.glancemapwearos.core.service.location.policy.LocationRuntimeMode
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHealFailoverCoordinatorTest {
    @Test
    fun latestAcceptedFixPrefersNewerImmediateFixOverOlderCallback() {
        assertEquals(
            20_000L,
            resolveLatestAcceptedFixAtElapsedMs(
                lastAnyAcceptedFixAtElapsedMs = 20_000L,
                lastCallbackAcceptedFixAtElapsedMs = 1_000L,
            ),
        )
    }

    @Test
    fun phoneConnectionRecheckRunsOnlyDuringAutoFusedWatchGpsFailover() {
        assertFalse(
            shouldRecheckAutoFusedPhoneConnection(
                fallbackToWatchGps = false,
                watchGpsOnly = false,
                nowElapsedMs = 30_000L,
                lastRecheckAtElapsedMs = 0L,
            ),
        )
        assertFalse(
            shouldRecheckAutoFusedPhoneConnection(
                fallbackToWatchGps = true,
                watchGpsOnly = true,
                nowElapsedMs = 30_000L,
                lastRecheckAtElapsedMs = 0L,
            ),
        )
        assertFalse(
            shouldRecheckAutoFusedPhoneConnection(
                fallbackToWatchGps = true,
                watchGpsOnly = false,
                nowElapsedMs = 29_999L,
                lastRecheckAtElapsedMs = 1L,
            ),
        )
        assertTrue(
            shouldRecheckAutoFusedPhoneConnection(
                fallbackToWatchGps = true,
                watchGpsOnly = false,
                nowElapsedMs = 30_001L,
                lastRecheckAtElapsedMs = 1L,
            ),
        )
    }

    @Test
    fun backgroundFailoverUsesAConservativeNoFixThreshold() {
        assertEquals(20_000L, resolveBackgroundAutoFusedFailoverThresholdMs(3_000L))
        assertEquals(30_000L, resolveBackgroundAutoFusedFailoverThresholdMs(10_000L))
    }

    @Test
    fun backgroundNoFixSwitchesFromPhoneToWatchGpsWithoutImmediateBurst() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 10_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.INTERACTIVE,
                sourceMode = LocationSourceMode.AUTO_FUSED,
            ),
        )
        var requestRefreshes = 0
        var immediateRequests = 0
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = { requestRefreshes += 1 },
                requestImmediateLocation = { immediateRequests += 1 },
                trackingEnabled = { true },
                ambientModeActive = { true },
                backgroundGpsEnabled = { true },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { false },
                passiveLocationExperiment = { false },
                phoneConnected = { false },
                lastAnyAcceptedFixAtElapsedMs = { 1_000L },
                lastCallbackAcceptedFixAtElapsedMs = { 1_000L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 10_000L },
                strictFreshMaxAgeMs = { 20_000L },
            )

        coordinator.maybeTriggerBackgroundAutoFusedFailoverNow(
            nowElapsedMs = 31_000L,
            backgroundTracking = true,
            expectedIntervalMs = 10_000L,
        )

        assertTrue(coordinator.isAutoFusedFallbackToWatchGps())
        assertEquals(1, requestRefreshes)
        assertEquals(0, immediateRequests)
    }

    @Test
    fun connectedPhoneNoFixDoesNotTriggerWatchGpsFallback() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 3_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.INTERACTIVE,
                sourceMode = LocationSourceMode.AUTO_FUSED,
            ),
        )
        val lastAnyAcceptedFixAtElapsedMs = 1_000L
        var immediateRequests = 0
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = {},
                requestImmediateLocation = { immediateRequests += 1 },
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { false },
                passiveLocationExperiment = { false },
                phoneConnected = { true },
                lastAnyAcceptedFixAtElapsedMs = { lastAnyAcceptedFixAtElapsedMs },
                lastCallbackAcceptedFixAtElapsedMs = { 1_000L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 13_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )
        assertEquals(0, immediateRequests)
        assertFalse(coordinator.isAutoFusedFallbackToWatchGps())
    }

    @Test
    fun returnsNullBelowAccuracyThresholds() {
        val requiredStreak =
            resolveAutoFusedAccuracyFailoverRequiredStreak(
                accuracyM = 80f,
                fixGapMs = 10_000L,
                expectedIntervalMs = 3_000L,
            )

        assertNull(requiredStreak)
    }

    @Test
    fun usesSeverePlateauStreakWhenAccuracyIsVeryPoorAndFixGapIsLarge() {
        val requiredStreak =
            resolveAutoFusedAccuracyFailoverRequiredStreak(
                accuracyM = 100f,
                fixGapMs = 7_000L,
                expectedIntervalMs = 3_000L,
            )

        assertEquals(3, requiredStreak)
        assertEquals(100f, resolveAutoFusedFailoverThresholdM(requiredStreak ?: 0), 0.001f)
    }

    @Test
    fun usesStandardStreakWhenFixGapIsStillShort() {
        val requiredStreak =
            resolveAutoFusedAccuracyFailoverRequiredStreak(
                accuracyM = 125f,
                fixGapMs = 2_000L,
                expectedIntervalMs = 3_000L,
            )

        assertEquals(4, requiredStreak)
        assertEquals(120f, resolveAutoFusedFailoverThresholdM(requiredStreak ?: 0), 0.001f)
    }

    @Test
    fun usesSeverePlateauStreakForVeryPoorAccuracyWhenNoAcceptedFixesArrive() {
        val requiredStreak =
            resolveAutoFusedAccuracyFailoverRequiredStreak(
                accuracyM = 117f,
                fixGapMs = 12_000L,
                expectedIntervalMs = 3_000L,
            )

        assertEquals(3, requiredStreak)
    }

    @Test
    fun noFixRecoveryStartsProbeBeforeFailover() {
        val action =
            resolveAutoFusedNoFixRecoveryAction(
                fixGapMs = 12_500L,
                thresholdMs = 12_000L,
                nowElapsedMs = 30_000L,
                probeUntilElapsedMs = 0L,
            )

        assertEquals(AutoFusedNoFixRecoveryAction.START_PROBE, action)
    }

    @Test
    fun noFixRecoveryWaitsWhileProbeWindowIsActive() {
        val action =
            resolveAutoFusedNoFixRecoveryAction(
                fixGapMs = 12_500L,
                thresholdMs = 12_000L,
                nowElapsedMs = 30_000L,
                probeUntilElapsedMs = 33_000L,
            )

        assertEquals(AutoFusedNoFixRecoveryAction.WAIT_FOR_PROBE, action)
    }

    @Test
    fun noFixRecoveryFailsOverAfterProbeWindowExpires() {
        val action =
            resolveAutoFusedNoFixRecoveryAction(
                fixGapMs = 16_500L,
                thresholdMs = 12_000L,
                nowElapsedMs = 34_500L,
                probeUntilElapsedMs = 34_000L,
            )

        assertEquals(AutoFusedNoFixRecoveryAction.FAILOVER, action)
    }

    @Test
    fun passiveExperimentUsesShorterNoFixFailoverThreshold() {
        assertEquals(8_000L, resolvePassiveExperimentNoFixFailoverThresholdMs(12_000L))
        assertEquals(6_000L, resolvePassiveExperimentNoFixFailoverThresholdMs(6_000L))
    }

    @Test
    fun gpsSearchRefreshKeepsNormalStartupTtffWindow() {
        assertEquals(15_000L, resolveGpsSearchRefreshThresholdMs(12_000L))
        assertEquals(18_000L, resolveGpsSearchRefreshThresholdMs(18_000L))
    }

    @Test
    fun passiveExperimentNoFixStaysPassiveWithoutFallback() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_PASSIVE,
                intervalMs = 3_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.INTERACTIVE,
                sourceMode = LocationSourceMode.PASSIVE_EXTERNAL,
            ),
        )
        var requestRefreshes = 0
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = { requestRefreshes += 1 },
                requestImmediateLocation = {},
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { false },
                passiveLocationExperiment = { true },
                phoneConnected = { true },
                lastAnyAcceptedFixAtElapsedMs = { 0L },
                lastCallbackAcceptedFixAtElapsedMs = { 0L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 10_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertFalse(coordinator.isAutoFusedFallbackToWatchGps())
        assertEquals(LocationSourceMode.PASSIVE_EXTERNAL, coordinator.currentLocationSourceMode())
        assertEquals(0, requestRefreshes)
    }

    @Test
    fun disconnectedInitialAutoFusedNoFixFallsBackToWatchGpsWithoutSecondFusedProbe() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 3_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.INTERACTIVE,
                sourceMode = LocationSourceMode.AUTO_FUSED,
            ),
        )
        var requestRefreshes = 0
        var immediateRequests = 0
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = { requestRefreshes += 1 },
                requestImmediateLocation = { immediateRequests += 1 },
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { false },
                passiveLocationExperiment = { false },
                phoneConnected = { false },
                lastAnyAcceptedFixAtElapsedMs = { 0L },
                lastCallbackAcceptedFixAtElapsedMs = { 0L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 13_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertTrue(coordinator.isAutoFusedFallbackToWatchGps())
        assertEquals(1, requestRefreshes)
        assertEquals(0, immediateRequests)
    }

    @Test
    fun activeAutoFusedBurstSearchingTooLongForcesRequestRefresh() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.requestImmediateBurst(nowElapsedMs = 1_000L, source = "test_startup")
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 1_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.BURST,
                sourceMode = LocationSourceMode.AUTO_FUSED,
            ),
        )
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = {},
                requestImmediateLocation = {},
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { false },
                passiveLocationExperiment = { false },
                phoneConnected = { false },
                lastAnyAcceptedFixAtElapsedMs = { 0L },
                lastCallbackAcceptedFixAtElapsedMs = { 0L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 17_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertFalse(engine.hasAppliedRequest())
    }

    @Test
    fun activeWatchGpsBurstWithinFirstCallbackGraceDoesNotRefreshRequest() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.requestImmediateBurst(nowElapsedMs = 1_000L, source = "test_startup")
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 1_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.BURST,
                sourceMode = LocationSourceMode.WATCH_GPS,
            ),
        )
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = {},
                requestImmediateLocation = {},
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { true },
                passiveLocationExperiment = { false },
                phoneConnected = { false },
                lastAnyAcceptedFixAtElapsedMs = { 0L },
                lastCallbackAcceptedFixAtElapsedMs = { 0L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 17_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertTrue(engine.hasAppliedRequest())
    }

    @Test
    fun activeWatchGpsBurstAfterFirstCallbackGraceCanRefreshRequest() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.requestImmediateBurst(nowElapsedMs = 1_000L, source = "test_startup")
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 1_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.BURST,
                sourceMode = LocationSourceMode.WATCH_GPS,
            ),
        )
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = {},
                requestImmediateLocation = {},
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { true },
                passiveLocationExperiment = { false },
                phoneConnected = { false },
                lastAnyAcceptedFixAtElapsedMs = { 0L },
                lastCallbackAcceptedFixAtElapsedMs = { 0L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 130_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertFalse(engine.hasAppliedRequest())
    }

    @Test
    fun activeBurstWithinStartupWindowDoesNotRefreshRequest() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.requestImmediateBurst(nowElapsedMs = 1_000L, source = "test_startup")
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 1_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.BURST,
                sourceMode = LocationSourceMode.WATCH_GPS,
            ),
        )
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = {},
                requestImmediateLocation = {},
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { false },
                passiveLocationExperiment = { false },
                phoneConnected = { false },
                lastAnyAcceptedFixAtElapsedMs = { 0L },
                lastCallbackAcceptedFixAtElapsedMs = { 0L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 10_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertTrue(engine.hasAppliedRequest())
    }

    @Test
    fun phoneReconnectionReturnsFromWatchGpsFallbackToAutoFused() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.markRequestApplied(
            RequestSpec(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = 3_000L,
                minDistanceMeters = 1f,
                mode = LocationRuntimeMode.INTERACTIVE,
                sourceMode = LocationSourceMode.WATCH_GPS,
            ),
        )
        var phoneConnected = false
        var requestRefreshes = 0
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = { requestRefreshes += 1 },
                requestImmediateLocation = {},
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { false },
                passiveLocationExperiment = { false },
                phoneConnected = { phoneConnected },
                lastAnyAcceptedFixAtElapsedMs = { 39_000L },
                lastCallbackAcceptedFixAtElapsedMs = { 39_000L },
                lastRequestAppliedAtElapsedMs = { 39_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
            )

        assertTrue(coordinator.forceAutoFusedFallbackToWatchGps("phone_disconnected", nowElapsedMs = 1_000L))
        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 40_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertTrue(coordinator.isAutoFusedFallbackToWatchGps())
        assertEquals(0, requestRefreshes)

        phoneConnected = true
        coordinator.onPhoneConnectionStateChecked(phoneConnected = true)
        assertFalse(coordinator.isAutoFusedFallbackToWatchGps())
        assertEquals(LocationSourceMode.AUTO_FUSED, coordinator.currentLocationSourceMode())
        assertEquals(1, requestRefreshes)
    }

    @Test
    fun watchGpsNoFixRequestsBoundedDirectRecoveryBeforeRestartingTheStream() {
        val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
        telemetry.setDebugEnabled(false)
        val engine = LocationEngine(telemetry)
        engine.markRequestApplied(interactiveWatchGpsRequestSpec())
        var immediateRequests = 0
        var recoveryRequests = 0
        val coordinator =
            SelfHealFailoverCoordinator(
                serviceScope = CoroutineScope(SupervisorJob()),
                isServiceActive = { true },
                engine = engine,
                telemetry = telemetry,
                requestLocationUpdateIfNeeded = {},
                requestImmediateLocation = { immediateRequests += 1 },
                trackingEnabled = { true },
                ambientModeActive = { false },
                hasFinePermission = { true },
                hasCoarsePermission = { true },
                watchGpsOnly = { true },
                passiveLocationExperiment = { false },
                phoneConnected = { false },
                lastAnyAcceptedFixAtElapsedMs = { 1_000L },
                lastCallbackAcceptedFixAtElapsedMs = { 1_000L },
                lastRequestAppliedAtElapsedMs = { 1_000L },
                expectedIntervalMs = { 3_000L },
                strictFreshMaxAgeMs = { 6_000L },
                requestWatchGpsRecovery = { _, _, _ ->
                    recoveryRequests += 1
                    true
                },
            )

        coordinator.maybeTriggerInteractiveSelfHealNow(
            nowElapsedMs = 16_000L,
            interactiveTracking = true,
            expectedIntervalMs = 3_000L,
        )

        assertEquals(1, recoveryRequests)
        assertEquals(0, immediateRequests)
    }

    @Test
    fun watchGpsRecoveryUsesThreeIntervalsWithAFifteenSecondFloor() {
        assertEquals(15_000L, resolveWatchGpsRecoveryStaleThresholdMs(3_000L))
        assertEquals(30_000L, resolveWatchGpsRecoveryStaleThresholdMs(10_000L))
        assertTrue(isWatchGpsRecoveryCooldownActive(60_000L, 1_000L))
        assertFalse(isWatchGpsRecoveryCooldownActive(61_000L, 1_000L))
    }

    private fun interactiveWatchGpsRequestSpec(): RequestSpec =
        RequestSpec(
            priority = Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs = 3_000L,
            minDistanceMeters = 1f,
            mode = LocationRuntimeMode.INTERACTIVE,
            sourceMode = LocationSourceMode.WATCH_GPS,
        )
}
