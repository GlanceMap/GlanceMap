package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_EFFECTIVE_ACCURACY_M
import com.glancemap.glancemapwearos.core.service.location.config.resolveEffectiveWatchGpsAccuracyMeters
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.mapsforge.core.model.LatLong
import com.glancemap.glancemapwearos.core.service.location.config.isKnownWatchGpsAccuracyFloor as platformWatchGpsAccuracyFloor

internal const val RECORDING_TRACK_FILTER_VERSION = 9
internal const val RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M = WATCH_GPS_EFFECTIVE_ACCURACY_M

/**
 * Some watches expose a fixed 125 m accuracy value for otherwise usable direct-GNSS fixes.
 * Keep that raw value in GPX, but do not let it make the live recording filter reject every
 * direct watch-GPS point.
 */
internal fun resolveRecordingFilterAccuracyMeters(
    rawAccuracyMeters: Float?,
    knownWatchGpsAccuracyFloorActive: Boolean,
): Float? {
    val rawAccuracy = rawAccuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: return rawAccuracyMeters
    return resolveEffectiveWatchGpsAccuracyMeters(
        rawAccuracyMeters = rawAccuracy,
        watchGpsActive = knownWatchGpsAccuracyFloorActive,
    )
}

internal fun isKnownWatchGpsAccuracyFloor(accuracyMeters: Float?) = platformWatchGpsAccuracyFloor(accuracyMeters)

internal data class RecordingFixSample(
    val latLong: LatLong,
    val timeMillis: Long,
    val elapsedRealtimeMillis: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
)

internal enum class RecordingFixQualityStatus {
    ACCEPTED,
    HELD,
    REJECTED,
}

internal enum class RecordingFixQualityReason {
    FIRST_FIX,
    GOOD_FIX,
    CONFIRMED_SUSTAINED_MOVEMENT,
    NON_MONOTONIC,
    POOR_ACCURACY,
    IMPLAUSIBLE_JUMP,
    CONFIRMED_RELOCATION,
}

internal data class RecordingFixQualityResult(
    val status: RecordingFixQualityStatus,
    val reason: RecordingFixQualityReason,
) {
    val accepted: Boolean get() = status == RecordingFixQualityStatus.ACCEPTED
}

internal data class RecordingAccuracyPolicySnapshot(
    val sampleCount: Int,
    val baselineMedianMeters: Float?,
    val profileLimitMeters: Float,
    val resolvedLimitMeters: Float,
) {
    val adaptiveLimitActive: Boolean
        get() = resolvedLimitMeters > profileLimitMeters
}

/**
 * Rejects fixes that cannot safely become part of the canonical recording. A single
 * implausible jump is held until the next sampled fix either disproves it or confirms that
 * GPS has genuinely reacquired in a different location.
 */
internal class RecordingFixQualityGate {
    private var lastSeenElapsedRealtimeMillis = Long.MIN_VALUE
    private var lastAccepted: RecordingFixSample? = null
    private var pendingImplausible: RecordingFixSample? = null
    private var sustainedMovementValidUntilElapsedRealtimeMillis = Long.MIN_VALUE
    private val recentAccuracyMeters = mutableListOf<Float>()
    var latestAccuracyPolicySnapshot: RecordingAccuracyPolicySnapshot? = null
        private set

    fun reset() {
        lastSeenElapsedRealtimeMillis = Long.MIN_VALUE
        lastAccepted = null
        pendingImplausible = null
        sustainedMovementValidUntilElapsedRealtimeMillis = Long.MIN_VALUE
        recentAccuracyMeters.clear()
        latestAccuracyPolicySnapshot = null
    }

    @Suppress("ReturnCount")
    fun evaluate(
        candidate: RecordingFixSample,
        activityProfile: String,
    ): RecordingFixQualityResult {
        if (
            lastSeenElapsedRealtimeMillis != Long.MIN_VALUE &&
            candidate.elapsedRealtimeMillis <= lastSeenElapsedRealtimeMillis
        ) {
            return RecordingFixQualityResult(
                status = RecordingFixQualityStatus.REJECTED,
                reason = RecordingFixQualityReason.NON_MONOTONIC,
            )
        }
        lastSeenElapsedRealtimeMillis = candidate.elapsedRealtimeMillis

        val accuracyPolicy = observeAndResolveAccuracyPolicy(candidate, activityProfile)
        latestAccuracyPolicySnapshot = accuracyPolicy
        if (candidate.accuracyMeters.isUnacceptablyPoor(accuracyPolicy.resolvedLimitMeters)) {
            return RecordingFixQualityResult(
                status = RecordingFixQualityStatus.REJECTED,
                reason = RecordingFixQualityReason.POOR_ACCURACY,
            )
        }

        val previous = lastAccepted
        if (previous == null) {
            return accept(candidate, RecordingFixQualityReason.FIRST_FIX)
        }

        if (
            candidate.elapsedRealtimeMillis <= sustainedMovementValidUntilElapsedRealtimeMillis &&
            isPlausibleConfirmedSustainedTransition(previous, candidate, activityProfile)
        ) {
            extendSustainedMovementWindow(candidate)
            return accept(candidate, RecordingFixQualityReason.GOOD_FIX)
        }
        sustainedMovementValidUntilElapsedRealtimeMillis = Long.MIN_VALUE

        val pending = pendingImplausible
        if (pending != null) {
            return evaluateCandidateAfterHeldFix(
                previous = previous,
                pending = pending,
                candidate = candidate,
                activityProfile = activityProfile,
            )
        }

        if (!isPlausibleTransition(previous, candidate, activityProfile)) {
            pendingImplausible = candidate
            return RecordingFixQualityResult(
                status = RecordingFixQualityStatus.HELD,
                reason = RecordingFixQualityReason.IMPLAUSIBLE_JUMP,
            )
        }
        return accept(candidate, RecordingFixQualityReason.GOOD_FIX)
    }

    private fun accept(
        candidate: RecordingFixSample,
        reason: RecordingFixQualityReason,
    ): RecordingFixQualityResult {
        lastAccepted = candidate
        pendingImplausible = null
        return RecordingFixQualityResult(
            status = RecordingFixQualityStatus.ACCEPTED,
            reason = reason,
        )
    }

    private fun extendSustainedMovementWindow(candidate: RecordingFixSample) {
        sustainedMovementValidUntilElapsedRealtimeMillis =
            candidate.elapsedRealtimeMillis + RECORDING_FIX_SUSTAINED_CONFIRMATION_WINDOW_MS
    }

    /**
     * Watch vendors do not report horizontal accuracy in exactly the same way. Keep the
     * conservative profile limit for a normal session, but learn a higher ceiling when
     * several consecutive fixes show that the device consistently reports a wider radius.
     * A hard ceiling still prevents a no-fix/coarse location from entering the recording.
     */
    private fun observeAndResolveAccuracyPolicy(
        candidate: RecordingFixSample,
        activityProfile: String,
    ): RecordingAccuracyPolicySnapshot {
        val profileLimit = recordingFixProfileAccuracyLimitMeters(activityProfile)
        val hardLimit = recordingFixHardAccuracyLimitMeters(activityProfile)
        candidate.accuracyMeters
            ?.takeIf { it.isFinite() && it >= 0f && it <= hardLimit }
            ?.let { accuracy ->
                recentAccuracyMeters += accuracy
                while (recentAccuracyMeters.size > RECORDING_FIX_ACCURACY_BASELINE_WINDOW) {
                    recentAccuracyMeters.removeAt(0)
                }
            }
        val median =
            recentAccuracyMeters
                .takeIf { it.size >= RECORDING_FIX_ACCURACY_BASELINE_MIN_SAMPLES }
                ?.sorted()
                ?.let { it[it.size / 2] }
        val resolvedLimit =
            if (recentAccuracyMeters.size < RECORDING_FIX_ACCURACY_BASELINE_MIN_SAMPLES || median == null) {
                profileLimit
            } else {
                maxOf(
                    profileLimit,
                    median * RECORDING_FIX_ACCURACY_BASELINE_FACTOR + RECORDING_FIX_ACCURACY_BASELINE_MARGIN_M,
                ).coerceAtMost(hardLimit)
            }
        return RecordingAccuracyPolicySnapshot(
            sampleCount = recentAccuracyMeters.size,
            baselineMedianMeters = median,
            profileLimitMeters = profileLimit,
            resolvedLimitMeters = resolvedLimit,
        )
    }

    @Suppress("ReturnCount")
    private fun evaluateCandidateAfterHeldFix(
        previous: RecordingFixSample,
        pending: RecordingFixSample,
        candidate: RecordingFixSample,
        activityProfile: String,
    ): RecordingFixQualityResult {
        if (isPlausibleTransition(previous, candidate, activityProfile)) {
            pendingImplausible = null
            return accept(candidate, RecordingFixQualityReason.GOOD_FIX)
        }
        if (isConfirmedSustainedMovement(previous, pending, candidate, activityProfile)) {
            extendSustainedMovementWindow(candidate)
            return accept(candidate, RecordingFixQualityReason.CONFIRMED_SUSTAINED_MOVEMENT)
        }
        if (isPlausibleTransition(pending, candidate, activityProfile)) {
            pendingImplausible = null
            return accept(
                candidate = candidate,
                reason = RecordingFixQualityReason.CONFIRMED_RELOCATION,
            )
        }
        pendingImplausible = candidate
        return RecordingFixQualityResult(
            status = RecordingFixQualityStatus.HELD,
            reason = RecordingFixQualityReason.IMPLAUSIBLE_JUMP,
        )
    }
}

private fun isPlausibleTransition(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
    activityProfile: String,
): Boolean {
    val elapsedSeconds =
        (candidate.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1_000.0
    if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0.0) return false
    val maximumSpeed = recordingTransitionMaximumSpeedMps(candidate, activityProfile)
    val uncertaintyAllowance = recordingTransitionUncertaintyAllowance(previous, candidate)
    val maximumDistance = maximumSpeed * elapsedSeconds + uncertaintyAllowance
    return haversineMeters(previous.latLong, candidate.latLong) <= maximumDistance
}

/**
 * Keep a modest activity-safe motion floor for sparse GPS delivery, but never turn the speed
 * limit into the profile maximum. Combined with the bounded accuracy allowance below, this
 * rejects short poor-accuracy jumps without splitting normal 8–10 second hiking fixes.
 */
internal fun recordingTransitionMaximumSpeedMps(
    candidate: RecordingFixSample,
    activityProfile: String,
): Double {
    val fallbackSpeed =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_FIX_FALLBACK_BIKE_SPEED_MPS
        } else {
            RECORDING_FIX_FALLBACK_HIKE_SPEED_MPS
        }
    val reportedSpeed =
        candidate.speedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: return fallbackSpeed
    val speedAccuracy =
        candidate.speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
    val speedAccuracyIsReliable =
        speedAccuracy == null ||
            speedAccuracy <=
            maxOf(
                RECORDING_FIX_MAX_TRUSTED_SPEED_ACCURACY_MPS,
                reportedSpeed * RECORDING_FIX_MAX_RELATIVE_SPEED_ACCURACY,
            )
    if (!speedAccuracyIsReliable) return fallbackSpeed
    val speedUncertainty =
        (speedAccuracy ?: 0.0).coerceAtMost(RECORDING_FIX_MAX_SPEED_UNCERTAINTY_ALLOWANCE_MPS)
    return (reportedSpeed + speedUncertainty)
        .coerceAtLeast(fallbackSpeed)
        .coerceAtMost(recordingFixProfileSpeedLimit(activityProfile) * RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR)
}

/**
 * Two independent GPS accuracy circles must not be added in full. That makes a 20–30 m fix
 * able to create a 25 m detour even when the watch reports normal walking speed. A bounded
 * fraction leaves room for normal GPS noise while requiring unusually large moves to be
 * confirmed by a following fix.
 */
internal fun recordingTransitionUncertaintyAllowance(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
): Double =
    (
        maxOf(
            previous.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M),
            candidate.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M),
        ) * RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR
    ) + RECORDING_FIX_BASE_ALLOWANCE_M

private fun Float?.isUnacceptablyPoor(maximumAccuracyMeters: Float): Boolean {
    val accuracy = this?.takeIf { it.isFinite() && it >= 0f } ?: return false
    return accuracy > maximumAccuracyMeters
}

internal fun recordingFixProfileAccuracyLimitMeters(activityProfile: String): Float =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        RECORDING_FIX_MAX_BIKE_ACCURACY_M
    } else {
        RECORDING_FIX_MAX_HIKE_ACCURACY_M
    }

private fun recordingFixHardAccuracyLimitMeters(activityProfile: String): Float =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        RECORDING_FIX_HARD_MAX_BIKE_ACCURACY_M
    } else {
        RECORDING_FIX_HARD_MAX_HIKE_ACCURACY_M
    }

private const val RECORDING_FIX_FALLBACK_HIKE_SPEED_MPS = 3.0
private const val RECORDING_FIX_FALLBACK_BIKE_SPEED_MPS = 10.0
internal const val RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR = 1.25
private const val RECORDING_FIX_MAX_HIKE_ACCURACY_M = 35f
private const val RECORDING_FIX_MAX_BIKE_ACCURACY_M = 50f
private const val RECORDING_FIX_HARD_MAX_HIKE_ACCURACY_M = 100f
private const val RECORDING_FIX_HARD_MAX_BIKE_ACCURACY_M = 120f
private const val RECORDING_FIX_ACCURACY_BASELINE_WINDOW = 9
private const val RECORDING_FIX_ACCURACY_BASELINE_MIN_SAMPLES = 5
private const val RECORDING_FIX_ACCURACY_BASELINE_FACTOR = 1.75f
private const val RECORDING_FIX_ACCURACY_BASELINE_MARGIN_M = 3f
internal const val RECORDING_FIX_FALLBACK_ACCURACY_M = 12.0
internal const val RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR = 0.35
internal const val RECORDING_FIX_BASE_ALLOWANCE_M = 2.5
internal const val RECORDING_FIX_MAX_TRUSTED_SPEED_ACCURACY_MPS = 2.5
internal const val RECORDING_FIX_MAX_RELATIVE_SPEED_ACCURACY = 0.5
internal const val RECORDING_FIX_MAX_SPEED_UNCERTAINTY_ALLOWANCE_MPS = 2.5
