package com.glancemap.glancemapwearos.core.service.location.service

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationGateway
import com.glancemap.glancemapwearos.core.service.location.config.HIGH_ACCURACY_BURST_DURATION
import com.glancemap.glancemapwearos.core.service.location.config.HIGH_ACCURACY_BURST_INITIAL_DURATION
import com.glancemap.glancemapwearos.core.service.location.engine.EndBurstResult
import com.glancemap.glancemapwearos.core.service.location.engine.ImmediateBurstDecision
import com.glancemap.glancemapwearos.core.service.location.engine.LocationEngine
import com.glancemap.glancemapwearos.core.service.location.model.LocationPermissionSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.effectiveAccuracyMeters
import com.glancemap.glancemapwearos.core.service.location.policy.FixAcceptancePolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("LongParameterList")
internal class ImmediateLocationCoordinator(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val engine: LocationEngine,
    private val telemetry: LocationServiceTelemetry,
    readAndStoreLocationPermissions: () -> LocationPermissionSnapshot,
    resolveFixAcceptancePolicy: (LocationPermissionSnapshot, LocationSourceMode) -> FixAcceptancePolicy,
    strictFreshMaxAgeMs: () -> Long,
    hardMaxAcceptedFixAgeMs: () -> Long,
    currentLocationSourceMode: () -> LocationSourceMode,
    locationGatewayFor: (LocationSourceMode) -> LocationGateway,
    private val requestLocationUpdateIfNeeded: () -> Unit,
    private val passiveExperimentSourceMode: () -> LocationSourceMode?,
    private val shouldRequestNavigateOneShot: (Long) -> Boolean,
    emitGpsSignalSnapshot: () -> Unit,
    emitAcceptedImmediateLocation: (Location, Long) -> Unit,
    navigateOneShotTimeoutMs: Long,
) {
    private var burstJob: Job? = null
    private val navigateOneShotCoordinator =
        NavigateOneShotCoordinator(
            serviceScope = serviceScope,
            engine = engine,
            telemetry = telemetry,
            readAndStoreLocationPermissions = readAndStoreLocationPermissions,
            resolveFixAcceptancePolicy = resolveFixAcceptancePolicy,
            strictFreshMaxAgeMs = strictFreshMaxAgeMs,
            hardMaxAcceptedFixAgeMs = hardMaxAcceptedFixAgeMs,
            currentLocationSourceMode = currentLocationSourceMode,
            locationGatewayFor = locationGatewayFor,
            emitGpsSignalSnapshot = emitGpsSignalSnapshot,
            emitAcceptedImmediateLocation = emitAcceptedImmediateLocation,
            endHighAccuracyBurst = { reason, burstId ->
                endHighAccuracyBurst(reason = reason, expectedBurstId = burstId)
            },
            timeoutMs = navigateOneShotTimeoutMs,
        )

    fun requestImmediateLocation(source: String = "service_unknown") {
        val passiveSourceMode = passiveExperimentSourceMode()
        if (passiveSourceMode != null) {
            telemetry.logImmediateRequestSkippedPassiveExperiment(
                source = source,
                backend = passiveSourceMode.telemetryValue,
            )
            requestLocationUpdateIfNeeded()
            return
        }

        val now = SystemClock.elapsedRealtime()
        when (val decision = engine.requestImmediateBurst(nowElapsedMs = now, source = source)) {
            is ImmediateBurstDecision.SkipActiveBurst,
            is ImmediateBurstDecision.SkipCooldown,
            -> return
            is ImmediateBurstDecision.Started -> {
                EnergyDiagnostics.recordSample(
                    context = context,
                    reason = "gps_burst_start",
                    detail = "burstId=${decision.burstId} source=$source durationMs=$HIGH_ACCURACY_BURST_DURATION",
                )

                burstJob?.cancel()
                requestLocationUpdateIfNeeded()
                startBurstTimer(decision.burstId)
                if (shouldRequestNavigateOneShot(now)) {
                    navigateOneShotCoordinator.request(
                        source = source,
                        burstId = decision.burstId,
                    )
                }
            }
        }
    }

    fun onGoodStreamFixAccepted() {
        navigateOneShotCoordinator.onGoodStreamFixAccepted()
    }

    fun cancelNavigateOneShot(reason: String): Boolean = navigateOneShotCoordinator.cancel(reason = reason)

    fun endHighAccuracyBurst(
        reason: String,
        expectedBurstId: Long? = null,
        requestLocationUpdate: Boolean = true,
    ): EndBurstResult? {
        val endedBurst =
            engine.endHighAccuracyBurst(
                reason = reason,
                expectedBurstId = expectedBurstId,
            ) ?: return null

        burstJob?.cancel()
        burstJob = null

        EnergyDiagnostics.recordSample(
            context = context,
            reason = "gps_burst_end",
            detail = "burstId=${endedBurst.burstId} source=${endedBurst.source} reason=$reason",
        )
        telemetry.logBurstSummary(
            burstId = endedBurst.burstId,
            source = endedBurst.source,
            reason = reason,
            endedAtElapsedMs = SystemClock.elapsedRealtime(),
        )

        if (requestLocationUpdate) {
            requestLocationUpdateIfNeeded()
        }
        return endedBurst
    }

    fun cancelImmediateLocationWork(reason: String) {
        val cancelledFetch = cancelNavigateOneShot(reason = reason)
        val cancelledBurst = engine.isBurstActive()
        if (cancelledBurst) {
            endHighAccuracyBurst(reason = reason, requestLocationUpdate = false)
        }

        if (cancelledFetch || cancelledBurst) {
            telemetry.logImmediateLocationWorkCancelled(
                reason = reason,
                cancelledBurst = cancelledBurst,
                cancelledFetch = cancelledFetch,
            )
        }
    }

    fun shutdown(reason: String) {
        endHighAccuracyBurst(reason = reason, requestLocationUpdate = false)
        burstJob?.cancel()
        burstJob = null
        cancelNavigateOneShot(reason = reason)
    }

    private fun startBurstTimer(burstId: Long) {
        burstJob =
            serviceScope.launch {
                delay(HIGH_ACCURACY_BURST_INITIAL_DURATION)
                val signal = engine.gpsSignalSnapshot
                val fixAgeMs =
                    (SystemClock.elapsedRealtime() - signal.lastFixElapsedRealtimeMs).coerceAtLeast(0L)
                if (
                    signal.lastFixElapsedRealtimeMs > 0L &&
                    fixAgeMs <= 6_000L &&
                    signal.effectiveAccuracyMeters() <= 35f
                ) {
                    endHighAccuracyBurst(reason = "timer_good_enough", expectedBurstId = burstId)
                } else {
                    delay(HIGH_ACCURACY_BURST_DURATION - HIGH_ACCURACY_BURST_INITIAL_DURATION)
                    endHighAccuracyBurst(reason = "timer_extended", expectedBurstId = burstId)
                }
            }
    }
}

internal fun shouldSuppressActiveImmediateLocationForPassiveExperiment(
    passiveLocationExperiment: Boolean,
    sourceMode: LocationSourceMode,
): Boolean = passiveLocationExperiment && sourceMode == LocationSourceMode.PASSIVE_EXTERNAL
