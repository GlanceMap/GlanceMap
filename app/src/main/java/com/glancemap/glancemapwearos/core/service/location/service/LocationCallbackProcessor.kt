package com.glancemap.glancemapwearos.core.service.location.service

import android.location.Location
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateEvent
import com.glancemap.glancemapwearos.core.service.location.engine.LocationEngine
import com.glancemap.glancemapwearos.core.service.location.model.LocationPermissionSnapshot
import com.glancemap.glancemapwearos.core.service.location.policy.FixAcceptancePolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceGuard
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry

internal data class LocationBatchSummary(
    val processedCandidates: Int,
    val acceptedCandidates: Int,
)

internal class LocationCallbackProcessor(
    private val engine: LocationEngine,
    private val telemetry: LocationServiceTelemetry,
    private val currentPermissions: () -> LocationPermissionSnapshot,
    private val resolveFixAcceptancePolicy: (LocationPermissionSnapshot, LocationSourceMode) -> FixAcceptancePolicy,
    private val strictFreshMaxAgeMs: () -> Long,
    private val hardMaxAcceptedFixAgeMs: () -> Long,
    private val sourceModeWarmupExpectedOrigin: () -> LocationSourceMode?,
    private val sourceModeWarmupUntilElapsedMs: () -> Long,
    private val emitGpsSignalSnapshot: () -> Unit,
    private val emitAcceptedLocation: (Location, Long) -> Unit,
    private val maybeTriggerAutoFusedFailover: (Location, LocationSourceMode, Long) -> Unit,
    private val endHighAccuracyBurstEarly: () -> Unit,
) {
    fun onLocationAvailability(
        isAvailable: Boolean,
        nowElapsedMs: Long,
    ) {
        engine.onLocationAvailability(
            isAvailable = isAvailable,
            nowElapsedMs = nowElapsedMs,
        )
        emitGpsSignalSnapshot()
    }

    fun processLocationEvent(
        event: LocationUpdateEvent,
        nowElapsedMsProvider: () -> Long,
    ): LocationBatchSummary {
        val fallbackUsed = event.rawCandidateCount == 0 && event.lastCandidate != null
        val locations =
            normalizeLocationBatch(
                candidates = event.candidates,
                lastCandidate = event.lastCandidate,
            )
        telemetry.onLocationCallback()
        val summary =
            if (locations.isEmpty()) {
                LocationBatchSummary(
                    processedCandidates = 0,
                    acceptedCandidates = 0,
                )
            } else {
                processLocationBatch(
                    candidates = locations,
                    callbackOrigin = event.origin,
                    maxUpdateDelayMs = event.maxUpdateDelayMs,
                    nowElapsedMsProvider = nowElapsedMsProvider,
                )
            }
        telemetry.logLocationBatchProcessed(
            rawCandidates = event.rawCandidateCount,
            normalizedCandidates = locations.size,
            acceptedCandidates = summary.acceptedCandidates,
            fallbackUsed = fallbackUsed,
            callbackOrigin = event.origin.telemetryValue,
            duplicateCandidatesDropped = event.duplicateCandidatesDropped,
        )
        return summary
    }

    private fun normalizeLocationBatch(
        candidates: List<Location>,
        lastCandidate: Location?,
    ): List<Location> {
        val normalizedCandidates =
            if (candidates.isNotEmpty()) {
                candidates
            } else {
                listOfNotNull(lastCandidate)
            }
        return sortLocationsForBatchProcessing(normalizedCandidates)
    }

    private fun processLocationBatch(
        candidates: List<Location>,
        callbackOrigin: LocationSourceMode,
        maxUpdateDelayMs: Long,
        nowElapsedMsProvider: () -> Long,
    ): LocationBatchSummary {
        var accepted = 0
        val strictMaxAgeMs =
            strictFreshMaxAgeMsFor(
                callbackOrigin = callbackOrigin,
                candidateCount = candidates.size,
                maxUpdateDelayMs = maxUpdateDelayMs,
            )
        candidates.forEach { candidate ->
            if (
                processLocationCandidate(
                    location = candidate,
                    nowElapsedMs = nowElapsedMsProvider(),
                    callbackOrigin = callbackOrigin,
                    strictMaxAgeMs = strictMaxAgeMs,
                )
            ) {
                accepted += 1
            }
        }
        return LocationBatchSummary(
            processedCandidates = candidates.size,
            acceptedCandidates = accepted,
        )
    }

    private fun processLocationCandidate(
        location: Location,
        nowElapsedMs: Long,
        callbackOrigin: LocationSourceMode,
        strictMaxAgeMs: Long,
    ): Boolean {
        val permissions = currentPermissions()
        if (!permissions.hasAnyPermission) return false

        if (!LocationFixPolicy.hasValidCoordinates(location)) {
            telemetry.logInvalidCoordinatesDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = engine.activityState(),
                burst = engine.isBurstActive(),
                source = "callback_candidate_${callbackOrigin.telemetryValue}",
                latitude = location.latitude,
                longitude = location.longitude,
                provider = location.provider,
            )
            return false
        }

        val expectedSourceMode =
            engine.currentSourceModeOrNull() ?: run {
                val warmupExpected = sourceModeWarmupExpectedOrigin()
                val inWarmup = nowElapsedMs <= sourceModeWarmupUntilElapsedMs()
                if (inWarmup && warmupExpected == callbackOrigin) {
                    warmupExpected
                } else {
                    telemetry.logSourceMismatchDropped(
                        nowElapsedMs = nowElapsedMs,
                        activityState = engine.activityState(),
                        burst = engine.isBurstActive(),
                        callbackOrigin = callbackOrigin.telemetryValue,
                        provider = location.provider,
                        expectedOrigin = "none",
                    )
                    return false
                }
            }

        if (!LocationSourceGuard.acceptsCallbackOrigin(expectedSourceMode, callbackOrigin)) {
            telemetry.logSourceMismatchDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = engine.activityState(),
                burst = engine.isBurstActive(),
                callbackOrigin = callbackOrigin.telemetryValue,
                provider = location.provider,
                expectedOrigin = LocationSourceGuard.expectedOrigin(expectedSourceMode),
            )
            return false
        }

        // Evaluate failover using validated source samples, including samples that may be filtered.
        maybeTriggerAutoFusedFailover(location, callbackOrigin, nowElapsedMs)

        val acceptance =
            adaptAcceptanceForCallbackBatch(
                policy = resolveFixAcceptancePolicy(permissions, callbackOrigin),
                callbackOrigin = callbackOrigin,
                strictMaxAgeMs = strictMaxAgeMs,
            )
        val outcome =
            engine.processCallbackCandidate(
                location = location,
                nowElapsedMs = nowElapsedMs,
                acceptance = acceptance,
                strictMaxAgeMs = strictMaxAgeMs,
                hardMaxAgeMs = hardMaxAcceptedFixAgeMs(),
                callbackOrigin = callbackOrigin,
            )
        emitGpsSignalSnapshot()

        val acceptedLocation = outcome.acceptedLocation ?: return false
        emitAcceptedLocation(
            engine.filterLocationForOutput(
                location = acceptedLocation,
                nowElapsedMs = nowElapsedMs,
                deliveredSourceMode = callbackOrigin,
            ),
            nowElapsedMs,
        )
        if (outcome.shouldEndBurstEarly) {
            endHighAccuracyBurstEarly()
        }
        return true
    }

    private fun strictFreshMaxAgeMsFor(
        callbackOrigin: LocationSourceMode,
        candidateCount: Int,
        maxUpdateDelayMs: Long,
    ): Long {
        val defaultMaxAgeMs = strictFreshMaxAgeMs()
        val sourceMaxAgeMs =
            if (callbackOrigin == LocationSourceMode.PASSIVE_EXTERNAL) {
                maxOf(defaultMaxAgeMs, PASSIVE_EXTERNAL_STRICT_FRESH_MAX_AGE_MS)
            } else {
                defaultMaxAgeMs
            }
        return resolveCallbackStrictFreshMaxAgeMs(
            callbackOrigin = callbackOrigin,
            sourceMaxAgeMs = sourceMaxAgeMs,
            candidateCount = candidateCount,
            maxUpdateDelayMs = maxUpdateDelayMs,
        )
    }

    private companion object {
        const val PASSIVE_EXTERNAL_STRICT_FRESH_MAX_AGE_MS = 20_000L
    }
}

/**
 * Fused batching delays delivery without changing each fix's original timestamp. A genuine
 * multi-fix batch therefore gets the requested delivery delay in addition to the ordinary
 * freshness allowance. Singleton and non-Fused callbacks remain subject to the live limit.
 */
internal fun resolveCallbackStrictFreshMaxAgeMs(
    callbackOrigin: LocationSourceMode,
    sourceMaxAgeMs: Long,
    candidateCount: Int,
    maxUpdateDelayMs: Long,
): Long {
    if (
        callbackOrigin != LocationSourceMode.AUTO_FUSED ||
        candidateCount < 2 ||
        maxUpdateDelayMs <= 0L
    ) {
        return sourceMaxAgeMs
    }
    val batchMaxAgeMs =
        if (Long.MAX_VALUE - maxUpdateDelayMs < sourceMaxAgeMs) {
            Long.MAX_VALUE
        } else {
            maxUpdateDelayMs + sourceMaxAgeMs
        }
    return maxOf(sourceMaxAgeMs, batchMaxAgeMs)
}

internal fun adaptAcceptanceForCallbackBatch(
    policy: FixAcceptancePolicy,
    callbackOrigin: LocationSourceMode,
    strictMaxAgeMs: Long,
): FixAcceptancePolicy =
    if (
        callbackOrigin == LocationSourceMode.AUTO_FUSED &&
        strictMaxAgeMs > policy.maxAgeMs
    ) {
        policy.copy(maxAgeMs = strictMaxAgeMs)
    } else {
        policy
    }

internal fun sortLocationsForBatchProcessing(locations: List<Location>): List<Location> =
    sortBatchByTimestamp(
        items = locations,
        elapsedRealtimeNanosOf = { it.elapsedRealtimeNanos },
        wallClockTimeMsOf = { it.time },
    )

internal fun <T> sortBatchByTimestamp(
    items: List<T>,
    elapsedRealtimeNanosOf: (T) -> Long,
    wallClockTimeMsOf: (T) -> Long,
): List<T> {
    if (items.size < 2) return items

    val allHaveElapsedRealtime = items.all { elapsedRealtimeNanosOf(it) > 0L }
    if (allHaveElapsedRealtime) {
        return items
            .mapIndexed { index, item -> IndexedValue(index, item) }
            .sortedWith(
                compareBy<IndexedValue<T>> { elapsedRealtimeNanosOf(it.value) }
                    .thenBy { it.index },
            ).map { it.value }
    }

    val allHaveWallClockTime = items.all { wallClockTimeMsOf(it) > 0L }
    if (allHaveWallClockTime) {
        return items
            .mapIndexed { index, item -> IndexedValue(index, item) }
            .sortedWith(
                compareBy<IndexedValue<T>> { wallClockTimeMsOf(it.value) }
                    .thenBy { it.index },
            ).map { it.value }
    }

    return items
}
