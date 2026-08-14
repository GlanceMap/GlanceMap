@file:Suppress("LongMethod")

package com.glancemap.glancemapwearos.core.service.location.service

import com.glancemap.glancemapwearos.core.service.location.adapters.CurrentLocationRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationGateway
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateEvent
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateSink
import com.glancemap.glancemapwearos.core.service.location.engine.LocationEngine
import com.glancemap.glancemapwearos.core.service.location.model.LocationPermissionSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeDemandReason
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRequestCoordinatorTest {
    @Test
    fun appliesBurstRequestAfterBurstTriggeredRefreshSupersedesInteractiveApply() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = CapturingLocationGateway()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var coordinator: LocationRequestCoordinator? = null
            var burstTriggered = false
            coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = true,
                            keepOpen = true,
                            watchOnlyRequested = false,
                            watchOnlyEffective = false,
                            screenState = LocationScreenState.INTERACTIVE,
                            backgroundGps = false,
                            runtimeReason = "idle",
                            passiveLocationExperiment = false,
                            userIntervalMs = 3_000L,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { 3_000L },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { gateway },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = {
                        if (!burstTriggered) {
                            burstTriggered = true
                            engine.requestImmediateBurst(
                                nowElapsedMs = 1_000L,
                                source = "test_burst_before_apply",
                            )
                            coordinator?.requestLocationUpdateIfNeeded()
                        }
                    },
                    removeInactiveLocationUpdates = {},
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()

            withTimeout(1_000L) {
                while (gateway.lastRequest == null) {
                    yield()
                }
            }

            assertEquals(1_000L, gateway.lastRequest?.intervalMs)
            assertTrue(gateway.lastRequest?.waitForAccurateLocation == true)
        }

    @Test
    fun screenOffBackgroundGpsUsesActiveCadenceWhenBackgroundGpsIsEnabled() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = CapturingLocationGateway()
            var requestedSourceMode: LocationSourceMode? = null
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = true,
                            keepOpen = true,
                            watchOnlyRequested = false,
                            watchOnlyEffective = false,
                            screenState = LocationScreenState.SCREEN_OFF,
                            backgroundGps = true,
                            runtimeReason = "recording",
                            passiveLocationExperiment = false,
                            userIntervalMs = 3_000L,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { 3_000L },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { sourceMode ->
                        requestedSourceMode = sourceMode
                        gateway
                    },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = {},
                    removeInactiveLocationUpdates = {},
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()

            withTimeout(1_000L) {
                while (gateway.lastRequest == null) {
                    yield()
                }
            }

            assertEquals(3_000L, gateway.lastRequest?.intervalMs)
            assertEquals(LocationSourceMode.AUTO_FUSED, requestedSourceMode)
            assertFalse(gateway.lastRequest?.waitForAccurateLocation == true)
        }

    @Test
    fun screenOffGuidanceGpsUsesActiveCadenceWhenBackgroundGpsIsEnabled() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = CapturingLocationGateway()
            var requestedSourceMode: LocationSourceMode? = null
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = true,
                            keepOpen = true,
                            watchOnlyRequested = false,
                            watchOnlyEffective = false,
                            screenState = LocationScreenState.SCREEN_OFF,
                            backgroundGps = true,
                            runtimeReason = NavigationRuntimeDemandReason.GUIDANCE_AMBIENT,
                            passiveLocationExperiment = false,
                            userIntervalMs = 2_000L,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { 2_000L },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { sourceMode ->
                        requestedSourceMode = sourceMode
                        gateway
                    },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = {},
                    removeInactiveLocationUpdates = {},
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()

            withTimeout(1_000L) {
                while (gateway.lastRequest == null) {
                    yield()
                }
            }

            assertEquals(2_000L, gateway.lastRequest?.intervalMs)
            assertEquals(Priority.PRIORITY_HIGH_ACCURACY, gateway.lastRequest?.priority)
            assertEquals(1f, gateway.lastRequest?.minDistanceMeters)
            assertEquals(LocationSourceMode.AUTO_FUSED, requestedSourceMode)
            assertFalse(gateway.lastRequest?.waitForAccurateLocation == true)
        }

    @Test
    fun screenOffRecordingGuidanceGpsUsesActiveCadenceWhenBackgroundGpsIsEnabled() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = CapturingLocationGateway()
            var requestedSourceMode: LocationSourceMode? = null
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = true,
                            keepOpen = true,
                            watchOnlyRequested = false,
                            watchOnlyEffective = false,
                            screenState = LocationScreenState.SCREEN_OFF,
                            backgroundGps = true,
                            runtimeReason = NavigationRuntimeDemandReason.RECORDING_GUIDANCE,
                            passiveLocationExperiment = false,
                            userIntervalMs = 2_000L,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { 2_000L },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { sourceMode ->
                        requestedSourceMode = sourceMode
                        gateway
                    },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = {},
                    removeInactiveLocationUpdates = {},
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()

            withTimeout(1_000L) {
                while (gateway.lastRequest == null) {
                    yield()
                }
            }

            assertEquals(2_000L, gateway.lastRequest?.intervalMs)
            assertEquals(Priority.PRIORITY_HIGH_ACCURACY, gateway.lastRequest?.priority)
            assertEquals(1f, gateway.lastRequest?.minDistanceMeters)
            assertEquals(LocationSourceMode.AUTO_FUSED, requestedSourceMode)
            assertFalse(gateway.lastRequest?.waitForAccurateLocation == true)
        }

    @Test
    fun retriesRequestApplicationAfterTransientGatewayFailure() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = FailingOnceLocationGateway()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = true,
                            keepOpen = true,
                            watchOnlyRequested = false,
                            watchOnlyEffective = false,
                            screenState = LocationScreenState.INTERACTIVE,
                            backgroundGps = false,
                            runtimeReason = "idle",
                            passiveLocationExperiment = false,
                            userIntervalMs = 3_000L,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { 3_000L },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { gateway },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = {},
                    removeInactiveLocationUpdates = {},
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    requestFailureRetryDelayMs = { 1L },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()

            withTimeout(1_000L) {
                while (gateway.requestCount < 2 || gateway.lastRequest == null) {
                    yield()
                }
            }

            assertEquals(2, gateway.requestCount)
            assertEquals(3_000L, gateway.lastRequest?.intervalMs)
        }

    @Test
    fun passiveExperimentAppliesPassiveExternalSource() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = CapturingLocationGateway()
            var requestedSourceMode: LocationSourceMode? = null
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = true,
                            keepOpen = true,
                            watchOnlyRequested = false,
                            watchOnlyEffective = false,
                            screenState = LocationScreenState.INTERACTIVE,
                            backgroundGps = false,
                            runtimeReason = "idle",
                            passiveLocationExperiment = true,
                            userIntervalMs = 3_000L,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { 3_000L },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { sourceMode ->
                        requestedSourceMode = sourceMode
                        gateway
                    },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = {},
                    removeInactiveLocationUpdates = {},
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()

            withTimeout(1_000L) {
                while (gateway.lastRequest == null) {
                    yield()
                }
            }

            assertEquals(LocationSourceMode.PASSIVE_EXTERNAL, requestedSourceMode)
            assertEquals(Priority.PRIORITY_PASSIVE, gateway.lastRequest?.priority)
            assertFalse(gateway.lastRequest?.waitForAccurateLocation == true)
        }

    @Test
    fun passiveExperimentKeepsPassiveListenerWhenTrackingIsOffButServiceIsOpen() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = CapturingLocationGateway()
            var requestedSourceMode: LocationSourceMode? = null
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = false,
                            keepOpen = true,
                            watchOnlyRequested = false,
                            watchOnlyEffective = false,
                            screenState = LocationScreenState.INTERACTIVE,
                            backgroundGps = false,
                            runtimeReason = "idle",
                            passiveLocationExperiment = true,
                            userIntervalMs = 3_000L,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { 3_000L },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { sourceMode ->
                        requestedSourceMode = sourceMode
                        gateway
                    },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = {},
                    removeInactiveLocationUpdates = {},
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()

            withTimeout(1_000L) {
                while (gateway.lastRequest == null) {
                    yield()
                }
            }

            assertEquals(LocationSourceMode.PASSIVE_EXTERNAL, requestedSourceMode)
            assertEquals(Priority.PRIORITY_PASSIVE, gateway.lastRequest?.priority)
            assertFalse(gateway.lastRequest?.waitForAccurateLocation == true)
        }

    @Test
    fun sameSourceRequestReconfigurationKeepsActiveBackendRegistered() =
        runBlocking {
            val telemetry = LocationServiceTelemetry(tag = "LocTelemetryTest", summaryIntervalMs = 60_000L)
            telemetry.setDebugEnabled(false)
            val engine = LocationEngine(telemetry)
            val gateway = CapturingLocationGateway()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var userIntervalMs = 3_000L
            var removeAllCount = 0
            var removeInactiveSource: LocationSourceMode? = null
            val coordinator =
                LocationRequestCoordinator(
                    serviceScope = scope,
                    engine = engine,
                    telemetry = telemetry,
                    readAndStoreLocationPermissions = {
                        LocationPermissionSnapshot(
                            hasFinePermission = true,
                            hasCoarsePermission = true,
                        )
                    },
                    updateSelfHealMonitor = {},
                    updateGnssDiagnostics = {},
                    foregroundRefresh = {},
                    inspectLocationEnvironment = { _, _, _ -> LocationEnvironmentAction.CONTINUE },
                    cancelImmediateLocationWork = {},
                    currentState = {
                        RequestUpdateState(
                            bound = false,
                            tracking = true,
                            keepOpen = true,
                            watchOnlyRequested = true,
                            watchOnlyEffective = true,
                            screenState = LocationScreenState.INTERACTIVE,
                            backgroundGps = false,
                            runtimeReason = "idle",
                            passiveLocationExperiment = false,
                            userIntervalMs = userIntervalMs,
                            ambientIntervalMs = 60_000L,
                        )
                    },
                    effectiveUpdateIntervalMs = { userIntervalMs },
                    strictSourceWarmupMs = 0L,
                    setSourceModeWarmup = { _, _ -> },
                    clearSourceModeWarmup = {},
                    locationGatewayFor = { gateway },
                    locationUpdateSink = { NoopLocationUpdateSink },
                    removeAllLocationUpdates = { removeAllCount += 1 },
                    removeInactiveLocationUpdates = { sourceMode -> removeInactiveSource = sourceMode },
                    onNoPermissions = {},
                    onNoRequestSpec = { _, _ -> },
                    onRequestApplied = { _, _ -> },
                    onRequestFailed = {},
                    maybeTriggerInteractiveSelfHealNow = { _, _, _ -> },
                    recordEnergySample = { _, _ -> },
                    elapsedRealtime = { 1_000L },
                )

            coordinator.requestLocationUpdateIfNeeded()
            withTimeout(1_000L) {
                while (gateway.lastRequest?.intervalMs != 3_000L) {
                    yield()
                }
            }

            userIntervalMs = 5_000L
            coordinator.requestLocationUpdateIfNeeded()
            withTimeout(1_000L) {
                while (gateway.lastRequest?.intervalMs != 5_000L) {
                    yield()
                }
            }

            assertEquals(1, removeAllCount)
            assertEquals(LocationSourceMode.WATCH_GPS, removeInactiveSource)
        }
}

private object NoopLocationUpdateSink : LocationUpdateSink {
    override fun onLocations(event: LocationUpdateEvent) = Unit
}

private class CapturingLocationGateway : LocationGateway {
    var lastRequest: LocationUpdateRequestParams? = null

    override suspend fun getCurrentLocation(request: CurrentLocationRequestParams) = null

    override suspend fun getLastLocation() = null

    override suspend fun requestLocationUpdates(
        request: LocationUpdateRequestParams,
        sink: LocationUpdateSink,
    ) {
        lastRequest = request
    }

    override suspend fun removeLocationUpdates() = Unit

    override fun removeLocationUpdatesBestEffort() = Unit
}

private class FailingOnceLocationGateway : LocationGateway {
    var lastRequest: LocationUpdateRequestParams? = null
    var requestCount: Int = 0

    override suspend fun getCurrentLocation(request: CurrentLocationRequestParams) = null

    override suspend fun getLastLocation() = null

    override suspend fun requestLocationUpdates(
        request: LocationUpdateRequestParams,
        sink: LocationUpdateSink,
    ) {
        requestCount += 1
        if (requestCount == 1) {
            throw IllegalStateException("transient request failure")
        }
        lastRequest = request
    }

    override suspend fun removeLocationUpdates() = Unit

    override fun removeLocationUpdatesBestEffort() = Unit
}
