package com.glancemap.glancemapwearos.core.service.location.processing

import android.location.Location
import com.glancemap.glancemapwearos.core.service.location.activity.LocationActivityState
import com.glancemap.glancemapwearos.core.service.location.activity.LocationActivityTracker
import com.glancemap.glancemapwearos.core.service.location.activity.LocationActivityTransition
import com.glancemap.glancemapwearos.core.service.location.policy.FixAcceptancePolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry

internal data class LocationCandidateEvaluation(
    val freshFixAtMs: Long?,
    val acceptedLocation: Location?,
    val acceptedFixAtMs: Long?,
    val activityTransition: LocationActivityTransition?,
    val shouldEndBurstEarly: Boolean,
)

internal data class ProcessedLocationCandidate(
    val acceptedLocation: Location?,
    val shouldEndBurstEarly: Boolean,
    val activityStateChanged: Boolean = false,
)

internal class LocationCandidateProcessor(
    private val activityTracker: LocationActivityTracker,
    private val telemetry: LocationServiceTelemetry,
    private val jitterThresholdMoving: Float,
    private val jitterThresholdStationary: Float,
    private val burstEarlyStopAccuracyM: Float,
    private val watchGpsBurstEarlyStopAccuracyM: Float,
) {
    private var lastLocation: Location? = null
    private var lastAcceptedFixAtMs: Long = 0L
    private var pendingJumpLocation: Location? = null
    private var pendingJumpSeenAtMs: Long = 0L

    fun activityState(): LocationActivityState = activityTracker.state

    fun acceptCachedLocation(
        location: Location,
        nowElapsedMs: Long,
        ageMs: Long,
    ) {
        val fixAtMs = (nowElapsedMs - ageMs).coerceAtLeast(0L)
        lastLocation = location
        lastAcceptedFixAtMs = fixAtMs
    }

    fun resetState() {
        lastLocation = null
        lastAcceptedFixAtMs = 0L
        pendingJumpLocation = null
        pendingJumpSeenAtMs = 0L
    }

    fun processImmediateCandidate(
        location: Location,
        nowElapsedMs: Long,
        acceptance: FixAcceptancePolicy,
        strictMaxAgeMs: Long,
        hardMaxAgeMs: Long,
        source: String,
        sourceMode: LocationSourceMode,
        isInHighAccuracyBurst: Boolean,
        burstEarlyStopMaxAgeMs: Long,
    ): ProcessedLocationCandidate {
        val ageMs = LocationFixPolicy.locationAgeMs(location, nowElapsedMs)
        if (ageMs == Long.MAX_VALUE || ageMs > hardMaxAgeMs) {
            telemetry.logStaleFixDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
                source = "${source}_hard_cap",
                ageMs = ageMs,
                maxAgeMs = hardMaxAgeMs,
            )
            return ProcessedLocationCandidate(
                acceptedLocation = null,
                shouldEndBurstEarly = false,
                activityStateChanged = false,
            )
        }
        val effectiveMaxAgeMs = minOf(acceptance.maxAgeMs, strictMaxAgeMs)
        if (ageMs > effectiveMaxAgeMs) {
            telemetry.logStaleFixDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
                source = source,
                ageMs = ageMs,
                maxAgeMs = effectiveMaxAgeMs,
            )
            return ProcessedLocationCandidate(
                acceptedLocation = null,
                shouldEndBurstEarly = false,
                activityStateChanged = false,
            )
        }
        if (!location.accuracy.isFinite() || location.accuracy > acceptance.maxAccuracyM) {
            telemetry.logAccuracyFixDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
                source = source,
                accuracyM = location.accuracy,
                maxAccuracyM = acceptance.maxAccuracyM,
                ageMs = ageMs,
                maxAgeMs = effectiveMaxAgeMs,
            )
            return ProcessedLocationCandidate(
                acceptedLocation = null,
                shouldEndBurstEarly = false,
                activityStateChanged = false,
            )
        }

        val freshFixAtMs = (nowElapsedMs - ageMs).coerceAtLeast(0L)
        lastLocation = location
        lastAcceptedFixAtMs = freshFixAtMs
        return ProcessedLocationCandidate(
            acceptedLocation = location,
            shouldEndBurstEarly =
                shouldEndBurstEarly(
                    isInHighAccuracyBurst = isInHighAccuracyBurst,
                    sourceMode = sourceMode,
                    accuracyM = location.accuracy,
                    ageMs = ageMs,
                    burstEarlyStopMaxAgeMs = burstEarlyStopMaxAgeMs,
                ),
            activityStateChanged = false,
        )
    }

    fun processCallbackCandidate(
        location: Location,
        nowElapsedMs: Long,
        acceptance: FixAcceptancePolicy,
        strictMaxAgeMs: Long,
        hardMaxAgeMs: Long,
        isInHighAccuracyBurst: Boolean,
        callbackOrigin: LocationSourceMode,
        burstEarlyStopMaxAgeMs: Long,
    ): ProcessedLocationCandidate {
        val ageMs = LocationFixPolicy.locationAgeMs(location, nowElapsedMs)
        if (ageMs == Long.MAX_VALUE || ageMs > hardMaxAgeMs) {
            telemetry.logStaleFixDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
                source = "callback_candidate_hard_cap",
                ageMs = ageMs,
                maxAgeMs = hardMaxAgeMs,
            )
            return ProcessedLocationCandidate(
                acceptedLocation = null,
                shouldEndBurstEarly = false,
                activityStateChanged = false,
            )
        }
        val effectiveMaxAgeMs = minOf(acceptance.maxAgeMs, strictMaxAgeMs)
        if (ageMs > effectiveMaxAgeMs) {
            telemetry.logStaleFixDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
                source = "callback_candidate",
                ageMs = ageMs,
                maxAgeMs = effectiveMaxAgeMs,
            )
            return ProcessedLocationCandidate(
                acceptedLocation = null,
                shouldEndBurstEarly = false,
                activityStateChanged = false,
            )
        }
        if (!location.accuracy.isFinite() || location.accuracy > acceptance.maxAccuracyM) {
            telemetry.logAccuracyFixDropped(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
                source = "callback_candidate",
                accuracyM = location.accuracy,
                maxAccuracyM = acceptance.maxAccuracyM,
                ageMs = ageMs,
                maxAgeMs = effectiveMaxAgeMs,
            )
            return ProcessedLocationCandidate(
                acceptedLocation = null,
                shouldEndBurstEarly = false,
                activityStateChanged = false,
            )
        }

        val freshFixAtMs = (nowElapsedMs - ageMs).coerceAtLeast(0L)
        val acceptedFixAtMs = freshFixAtMs
        val previousLocation = lastLocation
        val previousFixAtMs = lastAcceptedFixAtMs
        val jumpDiagnostics =
            if (
                previousLocation != null &&
                previousFixAtMs > 0L
            ) {
                analyzeJump(
                    previous = previousLocation,
                    previousFixAtMs = previousFixAtMs,
                    candidate = location,
                    candidateFixAtMs = freshFixAtMs,
                )
            } else {
                null
            }

        if (jumpDiagnostics != null && jumpDiagnostics.suspicious) {
            val confirmation =
                confirmPendingJump(
                    candidate = location,
                    candidateFixAtMs = freshFixAtMs,
                )
            if (!confirmation.confirmed) {
                pendingJumpLocation = Location(location)
                pendingJumpSeenAtMs = freshFixAtMs
                telemetry.logJumpFixDeferred(
                    nowElapsedMs = nowElapsedMs,
                    activityState = activityTracker.state,
                    burst = isInHighAccuracyBurst,
                    source = "callback_candidate",
                    jumpM = jumpDiagnostics.jumpMeters,
                    maxAllowedM = jumpDiagnostics.maxAllowedMeters,
                    gapMs = jumpDiagnostics.gapMs,
                    previousSpeedMps = jumpDiagnostics.previousSpeedMps,
                    candidateSpeedMps = jumpDiagnostics.candidateSpeedMps,
                )
                return ProcessedLocationCandidate(
                    acceptedLocation = null,
                    shouldEndBurstEarly = false,
                    activityStateChanged = false,
                )
            }
            telemetry.logJumpFixConfirmed(
                source = "callback_candidate",
                jumpM = confirmation.jumpMeters,
                confirmRadiusM = confirmation.confirmRadiusM,
                gapMs = confirmation.pendingGapMs,
            )
        } else {
            pendingJumpLocation = null
            pendingJumpSeenAtMs = 0L
        }

        // Keep fix timestamp ordering so delayed callbacks do not look newer than they are.
        val transition = activityTracker.onAcceptedLocation(location, acceptedFixAtMs)
        transition?.let { telemetry.logActivityTransition(it.from, it.to) }
        telemetry.onCallbackFixAccepted(
            nowElapsedMs = nowElapsedMs,
            activityState = activityTracker.state,
            burst = isInHighAccuracyBurst,
            source = "callback_candidate",
            ageMs = ageMs,
            accuracyM = location.accuracy,
            provider = location.provider,
            origin = callbackOrigin.telemetryValue,
        )

        lastLocation = location
        lastAcceptedFixAtMs = acceptedFixAtMs
        return ProcessedLocationCandidate(
            acceptedLocation = location,
            shouldEndBurstEarly =
                shouldEndBurstEarly(
                    isInHighAccuracyBurst = isInHighAccuracyBurst,
                    sourceMode = callbackOrigin,
                    accuracyM = location.accuracy,
                    ageMs = ageMs,
                    burstEarlyStopMaxAgeMs = burstEarlyStopMaxAgeMs,
                ),
            activityStateChanged = transition != null,
        )
    }

    fun evaluate(
        location: Location,
        nowElapsedMs: Long,
        ageMs: Long,
        acceptance: FixAcceptancePolicy,
        lastLocation: Location?,
        isInHighAccuracyBurst: Boolean,
        burstEarlyStopMaxAgeMs: Long,
    ): LocationCandidateEvaluation {
        if (ageMs > acceptance.maxAgeMs) {
            telemetry.onFilteredByStale(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
            )
            return LocationCandidateEvaluation(
                freshFixAtMs = null,
                acceptedLocation = null,
                acceptedFixAtMs = null,
                activityTransition = null,
                shouldEndBurstEarly = false,
            )
        }

        if (!location.accuracy.isFinite() || location.accuracy > acceptance.maxAccuracyM) {
            telemetry.onFilteredByAccuracy(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
            )
            return LocationCandidateEvaluation(
                freshFixAtMs = null,
                acceptedLocation = null,
                acceptedFixAtMs = null,
                activityTransition = null,
                shouldEndBurstEarly = false,
            )
        }

        val freshFixAtMs = (nowElapsedMs - ageMs).coerceAtLeast(0L)
        val transition = activityTracker.onAcceptedLocation(location, nowElapsedMs)

        val threshold =
            if (activityTracker.state == LocationActivityState.STATIONARY) {
                jitterThresholdStationary
            } else {
                jitterThresholdMoving
            }
        if (lastLocation != null && location.distanceTo(lastLocation) < threshold) {
            telemetry.onFilteredByJitter(
                nowElapsedMs = nowElapsedMs,
                activityState = activityTracker.state,
                burst = isInHighAccuracyBurst,
            )
            return LocationCandidateEvaluation(
                freshFixAtMs = freshFixAtMs,
                acceptedLocation = null,
                acceptedFixAtMs = null,
                activityTransition = transition,
                shouldEndBurstEarly =
                    shouldEndBurstEarly(
                        isInHighAccuracyBurst = isInHighAccuracyBurst,
                        sourceMode = LocationSourceMode.AUTO_FUSED,
                        accuracyM = location.accuracy,
                        ageMs = ageMs,
                        burstEarlyStopMaxAgeMs = burstEarlyStopMaxAgeMs,
                    ),
            )
        }

        telemetry.onCallbackFixAccepted(
            nowElapsedMs = nowElapsedMs,
            activityState = activityTracker.state,
            burst = isInHighAccuracyBurst,
            source = "legacy_evaluate",
            ageMs = ageMs,
            accuracyM = location.accuracy,
            provider = location.provider,
            origin = LocationSourceMode.AUTO_FUSED.telemetryValue,
        )

        return LocationCandidateEvaluation(
            freshFixAtMs = freshFixAtMs,
            acceptedLocation = location,
            acceptedFixAtMs = freshFixAtMs,
            activityTransition = transition,
            shouldEndBurstEarly =
                shouldEndBurstEarly(
                    isInHighAccuracyBurst = isInHighAccuracyBurst,
                    sourceMode = LocationSourceMode.AUTO_FUSED,
                    accuracyM = location.accuracy,
                    ageMs = ageMs,
                    burstEarlyStopMaxAgeMs = burstEarlyStopMaxAgeMs,
                ),
        )
    }

    fun shouldEndBurstEarly(
        isInHighAccuracyBurst: Boolean,
        sourceMode: LocationSourceMode,
        accuracyM: Float,
        ageMs: Long,
        burstEarlyStopMaxAgeMs: Long,
    ): Boolean {
        if (!isInHighAccuracyBurst) return false
        if (!accuracyM.isFinite()) return false
        val accuracyLimitM =
            when (sourceMode) {
                LocationSourceMode.AUTO_FUSED,
                LocationSourceMode.PASSIVE_EXTERNAL,
                -> burstEarlyStopAccuracyM
                LocationSourceMode.WATCH_GPS -> watchGpsBurstEarlyStopAccuracyM
            }
        if (accuracyM > accuracyLimitM) return false
        return ageMs <= burstEarlyStopMaxAgeMs
    }

    private fun analyzeJump(
        previous: Location,
        previousFixAtMs: Long,
        candidate: Location,
        candidateFixAtMs: Long,
    ): JumpDiagnostics {
        val distanceM = candidate.distanceTo(previous)
        val gapMs = (candidateFixAtMs - previousFixAtMs).coerceAtLeast(1_000L)
        val gapSec = gapMs / 1_000f
        val previousSpeedMps = if (previous.hasSpeed()) previous.speed.coerceAtLeast(0f) else 0f
        val candidateSpeedMps = if (candidate.hasSpeed()) candidate.speed.coerceAtLeast(0f) else 0f
        val expectedSpeedMps = maxOf(previousSpeedMps, candidateSpeedMps, BASE_EXPECTED_SPEED_MPS)

        val dynamicDistanceAllowanceM = expectedSpeedMps * gapSec * SPEED_ALLOWANCE_MULTIPLIER
        val previousAccuracyM = previous.accuracy.takeIf { it.isFinite() } ?: 0f
        val candidateAccuracyM = candidate.accuracy.takeIf { it.isFinite() } ?: 0f
        val accuracyAllowanceM = previousAccuracyM + candidateAccuracyM + JUMP_ACCURACY_BUFFER_M
        val maxAllowedDistanceM =
            maxOf(
                MIN_JUMP_ALLOWANCE_DISTANCE_M,
                dynamicDistanceAllowanceM,
                accuracyAllowanceM,
            )

        return JumpDiagnostics(
            jumpMeters = distanceM,
            maxAllowedMeters = maxAllowedDistanceM,
            gapMs = gapMs,
            previousSpeedMps = previousSpeedMps,
            candidateSpeedMps = candidateSpeedMps,
            suspicious = distanceM > maxAllowedDistanceM,
        )
    }

    private fun confirmPendingJump(
        candidate: Location,
        candidateFixAtMs: Long,
    ): JumpConfirmation {
        val pending =
            pendingJumpLocation ?: return JumpConfirmation(
                confirmed = false,
                jumpMeters = 0f,
                confirmRadiusM = 0f,
                pendingGapMs = 0L,
            )
        val pendingAgeMs = (candidateFixAtMs - pendingJumpSeenAtMs).coerceAtLeast(0L)
        if (pendingAgeMs > PENDING_JUMP_CONFIRM_WINDOW_MS) {
            return JumpConfirmation(
                confirmed = false,
                jumpMeters = pending.distanceTo(candidate),
                confirmRadiusM = 0f,
                pendingGapMs = pendingAgeMs,
            )
        }

        val pendingAccuracyM = pending.accuracy.takeIf { it.isFinite() } ?: 0f
        val candidateAccuracyM = candidate.accuracy.takeIf { it.isFinite() } ?: 0f
        val confirmRadiusM =
            maxOf(
                PENDING_JUMP_CONFIRM_MIN_RADIUS_M,
                pendingAccuracyM + candidateAccuracyM + JUMP_ACCURACY_BUFFER_M,
            )
        val jumpMeters = candidate.distanceTo(pending)
        val confirmed = jumpMeters <= confirmRadiusM
        if (confirmed) {
            pendingJumpLocation = null
            pendingJumpSeenAtMs = 0L
        }
        return JumpConfirmation(
            confirmed = confirmed,
            jumpMeters = jumpMeters,
            confirmRadiusM = confirmRadiusM,
            pendingGapMs = pendingAgeMs,
        )
    }

    private companion object {
        private const val BASE_EXPECTED_SPEED_MPS = 1.5f
        private const val SPEED_ALLOWANCE_MULTIPLIER = 2.5f
        private const val MIN_JUMP_ALLOWANCE_DISTANCE_M = 35f
        private const val JUMP_ACCURACY_BUFFER_M = 20f
        private const val PENDING_JUMP_CONFIRM_WINDOW_MS = 15_000L
        private const val PENDING_JUMP_CONFIRM_MIN_RADIUS_M = 30f
    }
}

private data class JumpDiagnostics(
    val jumpMeters: Float,
    val maxAllowedMeters: Float,
    val gapMs: Long,
    val previousSpeedMps: Float,
    val candidateSpeedMps: Float,
    val suspicious: Boolean,
)

private data class JumpConfirmation(
    val confirmed: Boolean,
    val jumpMeters: Float,
    val confirmRadiusM: Float,
    val pendingGapMs: Long,
)
