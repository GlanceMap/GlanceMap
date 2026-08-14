package com.glancemap.glancemapwearos.core.service.location.service

import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationGateway
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateSink
import com.glancemap.glancemapwearos.core.service.location.config.AUTO_PAUSE_MIN_DISTANCE_M
import com.glancemap.glancemapwearos.core.service.location.config.HIGH_ACCURACY_BURST_DURATION
import com.glancemap.glancemapwearos.core.service.location.engine.LocationEngine
import com.glancemap.glancemapwearos.core.service.location.engine.RequestSpec
import com.glancemap.glancemapwearos.core.service.location.model.LocationPermissionSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive
import com.glancemap.glancemapwearos.core.service.location.model.isNonInteractive
import com.glancemap.glancemapwearos.core.service.location.policy.LocationRuntimeMode
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeDemandReason
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class RequestUpdateState(
    val bound: Boolean,
    val tracking: Boolean,
    val keepOpen: Boolean,
    val watchOnlyRequested: Boolean,
    val watchOnlyEffective: Boolean,
    val screenState: LocationScreenState,
    val backgroundGps: Boolean,
    val runtimeReason: String,
    val passiveLocationExperiment: Boolean,
    val userIntervalMs: Long,
    val ambientIntervalMs: Long,
    val turnByTurnScreenOffBatchingEnabled: Boolean = false,
)

internal data class ResolvedRequestPlan(
    val state: RequestUpdateState,
    val interactiveTracking: Boolean,
    val spec: RequestSpec?,
)

@Suppress("LongParameterList")
internal class LocationRequestCoordinator(
    private val serviceScope: CoroutineScope,
    private val engine: LocationEngine,
    private val telemetry: LocationServiceTelemetry,
    private val readAndStoreLocationPermissions: () -> LocationPermissionSnapshot,
    private val updateSelfHealMonitor: () -> Unit,
    private val updateGnssDiagnostics: () -> Unit,
    private val foregroundRefresh: () -> Unit,
    private val inspectLocationEnvironment: suspend (
        requestSpec: RequestSpec,
        state: RequestUpdateState,
        nowElapsedMs: Long,
    ) -> LocationEnvironmentAction,
    private val cancelImmediateLocationWork: (String) -> Unit,
    private val currentState: () -> RequestUpdateState,
    private val effectiveUpdateIntervalMs: () -> Long,
    private val strictSourceWarmupMs: Long,
    private val setSourceModeWarmup: (expectedOrigin: LocationSourceMode, untilElapsedMs: Long) -> Unit,
    private val clearSourceModeWarmup: () -> Unit,
    private val locationGatewayFor: (LocationSourceMode) -> LocationGateway,
    private val locationUpdateSink: () -> LocationUpdateSink,
    private val removeAllLocationUpdates: suspend () -> Unit,
    private val removeInactiveLocationUpdates: suspend (activeSourceMode: LocationSourceMode) -> Unit,
    private val onNoPermissions: (nowElapsedMs: Long) -> Unit,
    private val onNoRequestSpec: (keepOpen: Boolean, tracking: Boolean) -> Unit,
    private val onRequestApplied: (nowElapsedMs: Long, intervalMs: Long) -> Unit,
    private val onSourceModeChanged: (LocationSourceMode) -> Unit = {},
    private val onRequestFailed: () -> Unit,
    private val maybeTriggerInteractiveSelfHealNow: (nowElapsedMs: Long, interactiveTracking: Boolean, expectedIntervalMs: Long) -> Unit,
    private val recordEnergySample: (reason: String, detail: String) -> Unit,
    private val requestFailureRetryDelayMs: (Long) -> Long = ::resolveRequestFailureRetryDelayMs,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val requestUpdatesJob = AtomicReference<Job?>(null)
    private val requestRetryJob = AtomicReference<Job?>(null)
    private val requestGeneration = AtomicLong(0L)
    private val requestFailureCount = AtomicLong(0L)

    fun requestLocationUpdateIfNeeded() {
        cancelPendingRetry(resetFailureCount = false)
        val generation = requestGeneration.incrementAndGet()
        var launchedJob: Job? = null
        launchedJob =
            serviceScope.launch {
                val permissions = readAndStoreLocationPermissions()
                updateSelfHealMonitor()
                updateGnssDiagnostics()
                if (isSuperseded(generation)) return@launch
                if (!permissions.hasAnyPermission) {
                    foregroundRefresh()
                    runCatching { removeAllLocationUpdates() }
                    if (isSuperseded(generation)) return@launch
                    logRequestUpdatesCleared(
                        reason = "no_permission",
                        state = currentState(),
                    )
                    resetRetryState()
                    onNoPermissions(elapsedRealtime())
                    return@launch
                }

                val state = currentState()
                if (isSuperseded(generation)) return@launch
                if (!state.tracking || (state.screenState.isNonInteractive && !state.backgroundGps)) {
                    cancelImmediateLocationWork(
                        if (!state.tracking) {
                            "request_guard_tracking_disabled"
                        } else {
                            "request_guard_background_without_gps"
                        },
                    )
                }

                val nowElapsedMs = elapsedRealtime()
                foregroundRefresh()

                val requestPlan = resolveRequestPlan(state = state, permissions = permissions)
                var appliedPlan = requestPlan
                var requestSpec = requestPlan.spec?.forRuntimeReason(state.runtimeReason)

                if (isSuperseded(generation)) return@launch
                if (requestSpec == null) {
                    runCatching { removeAllLocationUpdates() }
                    if (isSuperseded(generation)) return@launch
                    logRequestUpdatesCleared(
                        reason = requestClearReason(state),
                        state = state,
                    )
                    resetRetryState()
                    onNoRequestSpec(state.keepOpen, state.tracking)
                    return@launch
                }

                val environmentAction =
                    inspectLocationEnvironment(
                        requestSpec,
                        state,
                        nowElapsedMs,
                    )
                if (isSuperseded(generation)) return@launch
                if (environmentAction == LocationEnvironmentAction.RESTART_REQUEST) {
                    requestLocationUpdateIfNeeded()
                    return@launch
                }

                if (engine.isRequestAlreadyApplied(requestSpec, effectiveUpdateIntervalMs())) {
                    resetRetryState()
                    maybeTriggerInteractiveSelfHealNow(
                        nowElapsedMs,
                        requestPlan.interactiveTracking,
                        requestSpec.intervalMs,
                    )
                    return@launch
                }

                try {
                    if (isSuperseded(generation)) return@launch
                    val previousSourceMode = engine.currentAppliedSourceMode()
                    // Reject any in-flight callbacks until the new request is fully applied.
                    engine.forceRequestRefresh()
                    setSourceModeWarmup(
                        requestSpec.sourceMode,
                        nowElapsedMs + strictSourceWarmupMs,
                    )
                    if (previousSourceMode == requestSpec.sourceMode) {
                        removeInactiveLocationUpdates(requestSpec.sourceMode)
                    } else {
                        removeAllLocationUpdates()
                    }
                    if (isSuperseded(generation)) return@launch
                    val locationGateway = locationGatewayFor(requestSpec.sourceMode)
                    val maxUpdateDelayMs = resolveMaxUpdateDelayMs(state, requestSpec)
                    locationGateway.requestLocationUpdates(
                        request =
                            LocationUpdateRequestParams(
                                priority = requestSpec.priority,
                                intervalMs = requestSpec.intervalMs,
                                minDistanceMeters = requestSpec.minDistanceMeters,
                                waitForAccurateLocation =
                                    requestSpec.mode == LocationRuntimeMode.BURST &&
                                        requestSpec.sourceMode == LocationSourceMode.AUTO_FUSED,
                                maxUpdateDelayMs = maxUpdateDelayMs,
                                durationMs =
                                    if (requestSpec.mode == LocationRuntimeMode.BURST) {
                                        HIGH_ACCURACY_BURST_DURATION
                                    } else {
                                        null
                                    },
                            ),
                        sink = locationUpdateSink(),
                    )
                    if (isSuperseded(generation)) return@launch

                    engine.markRequestApplied(
                        spec = requestSpec,
                        nowElapsedMs = nowElapsedMs,
                    )
                    if (previousSourceMode != null && previousSourceMode != requestSpec.sourceMode) {
                        onSourceModeChanged(requestSpec.sourceMode)
                    }
                    resetRetryState()
                    onRequestApplied(nowElapsedMs, requestSpec.intervalMs)
                    telemetry.logRequestUpdatesApplied(
                        priority = requestSpec.priority,
                        intervalMs = requestSpec.intervalMs,
                        minDistanceMeters = requestSpec.minDistanceMeters,
                        activityState = engine.activityState(),
                        bound = appliedPlan.state.bound,
                        keepOpen = appliedPlan.state.keepOpen,
                        watchOnly = appliedPlan.state.watchOnlyEffective,
                        burst = engine.isBurstActive(),
                        backend = requestSpec.sourceMode.telemetryValue,
                        runtimeMode = requestSpec.mode.name,
                        trackingEnabled = appliedPlan.state.tracking,
                        interactive = appliedPlan.interactiveTracking,
                        screenState = appliedPlan.state.screenState.name,
                        runtimeReason = appliedPlan.state.runtimeReason,
                        hasFinePermission = permissions.hasFinePermission,
                        hasCoarsePermission = permissions.hasCoarsePermission,
                        passivePriority = requestSpec.priority == Priority.PRIORITY_PASSIVE,
                    )
                    recordEnergySample(
                        "gps_request_applied",
                        "priority=${requestSpec.priority} intervalMs=${requestSpec.intervalMs} " +
                            "minDistanceM=${requestSpec.minDistanceMeters} state=${engine.activityState().name} " +
                            "trackingEnabled=${appliedPlan.state.tracking} bound=${appliedPlan.state.bound} " +
                            "keepOpen=${appliedPlan.state.keepOpen} " +
                            "watchOnly=${appliedPlan.state.watchOnlyEffective} burst=${engine.isBurstActive()} " +
                            "backend=${requestSpec.sourceMode.telemetryValue} mode=${requestSpec.mode.name} " +
                            "requestInteractive=${appliedPlan.interactiveTracking} " +
                            "screenState=${appliedPlan.state.screenState.name} " +
                            "reason=${appliedPlan.state.runtimeReason} " +
                            "gpsRequestActive=true gpsBackend=${requestSpec.sourceMode.telemetryValue} " +
                            "gpsRequestIntervalMs=${requestSpec.intervalMs} " +
                            "passivePriority=${requestSpec.priority == Priority.PRIORITY_PASSIVE}",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    telemetry.logRequestUpdatesFailed(
                        priority = requestSpec.priority,
                        intervalMs = requestSpec.intervalMs,
                        minDistanceMeters = requestSpec.minDistanceMeters,
                        backend = requestSpec.sourceMode.telemetryValue,
                        errorType = error.javaClass.simpleName,
                        errorDetail = error.message,
                    )
                    engine.markRequestFailed()
                    clearSourceModeWarmup()
                    onRequestFailed()
                    scheduleRetry(
                        failedGeneration = generation,
                        failedSpec = requestSpec,
                    )
                } finally {
                    launchedJob?.let { requestUpdatesJob.compareAndSet(it, null) }
                }
            }
        requestUpdatesJob.getAndSet(launchedJob)?.cancel()
    }

    fun cancel() {
        requestUpdatesJob.getAndSet(null)?.cancel()
        cancelPendingRetry(resetFailureCount = true)
    }

    private fun isSuperseded(generation: Long): Boolean = generation != requestGeneration.get()

    private fun logRequestUpdatesCleared(
        reason: String,
        state: RequestUpdateState,
    ) {
        telemetry.logRequestUpdatesCleared(
            reason = reason,
            bound = state.bound,
            keepOpen = state.keepOpen,
            trackingEnabled = state.tracking,
            screenState = state.screenState.name,
            backgroundGpsEnabled = state.backgroundGps,
        )
    }

    private fun requestClearReason(state: RequestUpdateState): String =
        when {
            !state.tracking -> "tracking_disabled"
            state.screenState.isNonInteractive && !state.backgroundGps -> "background_gps_disabled"
            else -> "no_request_spec"
        }

    private fun resolveRequestPlan(
        state: RequestUpdateState,
        permissions: LocationPermissionSnapshot,
    ): ResolvedRequestPlan {
        val explicitBackgroundTracking = state.tracking && state.screenState.isNonInteractive && state.backgroundGps
        val activeBackgroundTracking =
            explicitBackgroundTracking &&
                (
                    state.runtimeReason == NavigationRuntimeDemandReason.RECORDING ||
                        state.runtimeReason == NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED ||
                        state.runtimeReason == NavigationRuntimeDemandReason.RECORDING_GUIDANCE ||
                        state.runtimeReason == NavigationRuntimeDemandReason.GUIDANCE_AMBIENT ||
                        state.runtimeReason == NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND
                )
        val passiveExperimentListening =
            state.passiveLocationExperiment && !state.watchOnlyEffective && state.keepOpen
        val passiveTracking =
            explicitBackgroundTracking ||
                passiveExperimentListening
        val interactiveTracking = state.tracking && state.screenState.isInteractive
        val spec =
            engine.resolveRequestSpec(
                interactive = interactiveTracking,
                passiveTracking = passiveTracking,
                watchOnly = state.watchOnlyEffective,
                hasFinePermission = permissions.hasFinePermission,
                passiveLocationExperiment = state.passiveLocationExperiment && !explicitBackgroundTracking,
                activeBackgroundTracking = activeBackgroundTracking,
                userIntervalMs = state.userIntervalMs,
                ambientIntervalMs =
                    if (explicitBackgroundTracking) {
                        minOf(state.ambientIntervalMs, state.userIntervalMs)
                    } else {
                        state.ambientIntervalMs
                    },
            )
        return ResolvedRequestPlan(
            state = state,
            interactiveTracking = interactiveTracking,
            spec = spec,
        )
    }

    private fun scheduleRetry(
        failedGeneration: Long,
        failedSpec: RequestSpec?,
    ) {
        val failureCount = requestFailureCount.incrementAndGet()
        val delayMs = requestFailureRetryDelayMs(failureCount)
        var scheduledRetryJob: Job? = null
        scheduledRetryJob =
            serviceScope.launch {
                try {
                    if (delayMs > 0L) {
                        delay(delayMs)
                    }
                    if (requestGeneration.get() != failedGeneration) return@launch
                    scheduledRetryJob?.let { requestRetryJob.compareAndSet(it, null) }
                    requestLocationUpdateIfNeeded()
                } finally {
                    scheduledRetryJob?.let { requestRetryJob.compareAndSet(it, null) }
                }
            }
        requestRetryJob.getAndSet(scheduledRetryJob)?.cancel()
        failedSpec?.let { spec ->
            recordEnergySample(
                "gps_request_retry_scheduled",
                "priority=${spec.priority} intervalMs=${spec.intervalMs} " +
                    "minDistanceM=${spec.minDistanceMeters} backend=${spec.sourceMode.telemetryValue} " +
                    "failureCount=$failureCount delayMs=$delayMs",
            )
        }
    }

    private fun resetRetryState() {
        cancelPendingRetry(resetFailureCount = true)
    }

    private fun cancelPendingRetry(resetFailureCount: Boolean) {
        requestRetryJob.getAndSet(null)?.cancel()
        if (resetFailureCount) {
            requestFailureCount.set(0L)
        }
    }
}

internal fun RequestSpec.forRuntimeReason(runtimeReason: String): RequestSpec =
    if (runtimeReason == NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED) {
        copy(minDistanceMeters = maxOf(minDistanceMeters, AUTO_PAUSE_MIN_DISTANCE_M))
    } else {
        this
    }

internal fun resolveMaxUpdateDelayMs(
    state: RequestUpdateState,
    requestSpec: RequestSpec,
): Long =
    resolveMaxUpdateDelayMs(
        screenState = state.screenState,
        runtimeReason = state.runtimeReason,
        turnByTurnScreenOffBatchingEnabled = state.turnByTurnScreenOffBatchingEnabled,
        requestSpec = requestSpec,
    )

private fun resolveRequestFailureRetryDelayMs(failureCount: Long): Long =
    when {
        failureCount <= 1L -> 2_000L
        failureCount == 2L -> 5_000L
        failureCount == 3L -> 10_000L
        else -> 15_000L
    }
