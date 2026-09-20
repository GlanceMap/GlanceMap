package com.glancemap.glancemapcompanionapp.livetracking

import android.location.Location
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class LiveTrackingLocationFix(
    val latitude: Double,
    val longitude: Double,
    val epochMilliseconds: Long,
    val elapsedRealtimeNanos: Long?,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float? = null,
)

internal enum class LiveTrackingLocationQualityResult {
    ACCEPT,
    SUSPECT,
    REJECT,
}

internal enum class LiveTrackingSuspectResolution {
    NONE,
    WAITING,
    CONFIRMED,
    REJECTED,
}

internal enum class LiveTrackingSpeedEvidence {
    CORROBORATED,
    CONTRADICTED,
    UNAVAILABLE,
}

internal data class LiveTrackingLocationQualityDecision(
    val result: LiveTrackingLocationQualityResult,
    val reason: String,
    val accuracyMeters: Float?,
    val fixAgeMillis: Long?,
    val distanceFromPreviousMeters: Double?,
    val impliedSpeedMetersPerSecond: Double?,
    val timeDeltaFromPreviousAcceptedFixMillis: Long? = null,
    val suspectResolution: LiveTrackingSuspectResolution = LiveTrackingSuspectResolution.NONE,
    val speedAccuracyMetersPerSecond: Float? = null,
    val effectiveJumpThresholdMeters: Double? = null,
    val poorAccuracy: Boolean = false,
    val speedEvidence: LiveTrackingSpeedEvidence = LiveTrackingSpeedEvidence.UNAVAILABLE,
)

internal data class LiveTrackingLocationDiagnostics(
    val fixAgeMillis: Long?,
    val distanceFromPreviousMeters: Double?,
    val impliedSpeedMetersPerSecond: Double?,
    val qualityResult: LiveTrackingLocationQualityResult,
    val qualityReason: String,
    val timeDeltaFromPreviousAcceptedFixMillis: Long? = null,
    val suspectResolution: LiveTrackingSuspectResolution = LiveTrackingSuspectResolution.NONE,
    val speedAccuracyMetersPerSecond: Float? = null,
    val effectiveJumpThresholdMeters: Double? = null,
    val poorAccuracy: Boolean = false,
    val speedEvidence: LiveTrackingSpeedEvidence = LiveTrackingSpeedEvidence.UNAVAILABLE,
)

internal fun LiveTrackingLocationQualityDecision.toDiagnostics(): LiveTrackingLocationDiagnostics =
    LiveTrackingLocationDiagnostics(
        fixAgeMillis = fixAgeMillis,
        distanceFromPreviousMeters = distanceFromPreviousMeters,
        impliedSpeedMetersPerSecond = impliedSpeedMetersPerSecond,
        qualityResult = result,
        qualityReason = reason,
        timeDeltaFromPreviousAcceptedFixMillis = timeDeltaFromPreviousAcceptedFixMillis,
        suspectResolution = suspectResolution,
        speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
        effectiveJumpThresholdMeters = effectiveJumpThresholdMeters,
        poorAccuracy = poorAccuracy,
        speedEvidence = speedEvidence,
    )

internal class LiveTrackingLocationQualityGate {
    private var lastAcceptedFix: LiveTrackingLocationFix? = null
    private var pendingFix: PendingFix? = null
    private var freshInitialFixEstablished = false

    internal val hasFreshInitialFix: Boolean
        get() = freshInitialFixEstablished

    // The explicit order keeps stale, invalid, confirmed, and quarantined fixes easy to audit.
    @Suppress("LongMethod", "ReturnCount")
    fun evaluate(
        fix: LiveTrackingLocationFix,
        nowElapsedRealtimeNanos: Long,
        nowEpochMilliseconds: Long,
        startupStaleReason: String = "stale_startup_callback",
    ): LiveTrackingLocationQualityDecision {
        val ageMillis = liveTrackingLocationAgeMillis(fix, nowElapsedRealtimeNanos, nowEpochMilliseconds)
        if (ageMillis == null) {
            return decision(
                result = LiveTrackingLocationQualityResult.REJECT,
                reason = "unknown_fix_age",
                accuracyMeters = fix.accuracyMeters,
                ageMillis = null,
            )
        }
        val (maxAllowedFixAgeMillis, staleFixReason) = currentFixAgeRule(startupStaleReason)
        if (ageMillis > maxAllowedFixAgeMillis) {
            return decision(
                result = LiveTrackingLocationQualityResult.REJECT,
                reason = staleFixReason,
                accuracyMeters = fix.accuracyMeters,
                ageMillis = ageMillis,
            )
        }
        if (!fix.hasValidCoordinates()) {
            return decision(
                result = LiveTrackingLocationQualityResult.REJECT,
                reason = "invalid_coordinates",
                accuracyMeters = fix.accuracyMeters,
                ageMillis = ageMillis,
            )
        }

        val previous = lastAcceptedFix
        if (previous == null) {
            return accept(fix, ageMillis, "first_fix")
        }

        val metrics = movementMetrics(previous, fix)
        val hadPendingFix = pendingFix != null
        pendingFix?.let { pending ->
            if (isNear(previous, fix, RECOVERY_RADIUS_METERS)) {
                pendingFix = null
                return accept(
                    fix,
                    ageMillis,
                    "returned_to_previous_area",
                    metrics,
                    LiveTrackingSuspectResolution.REJECTED,
                )
            }
            if (isNear(pending.fix, fix, CONFIRMATION_RADIUS_METERS)) {
                val candidateConfirmations = pending.confirmations + 1
                val movementConfirmed = confirmsMovement(pending.fix, fix)
                if (movementConfirmed || candidateConfirmations >= MIN_PENDING_AREA_CONFIRMATIONS) {
                    pendingFix = null
                    return accept(
                        fix,
                        ageMillis,
                        if (movementConfirmed) "confirmed_suspect_movement" else "confirmed_suspect_area",
                        metrics,
                        LiveTrackingSuspectResolution.CONFIRMED,
                    )
                }
                pendingFix = PendingFix(fix = fix, confirmations = candidateConfirmations)
                return decision(
                    result = LiveTrackingLocationQualityResult.SUSPECT,
                    reason = "repeated_suspect_area",
                    accuracyMeters = fix.accuracyMeters,
                    ageMillis = ageMillis,
                    metrics = metrics,
                    suspectResolution = LiveTrackingSuspectResolution.WAITING,
                )
            }
        }

        if (metrics.isSuspicious()) {
            pendingFix = PendingFix(fix = fix, confirmations = 1)
            return decision(
                result = LiveTrackingLocationQualityResult.SUSPECT,
                reason = "inconsistent_jump",
                accuracyMeters = fix.accuracyMeters,
                ageMillis = ageMillis,
                metrics = metrics,
                suspectResolution = LiveTrackingSuspectResolution.WAITING,
            )
        }

        pendingFix = null
        return accept(
            fix,
            ageMillis,
            "consistent_fix",
            metrics,
            if (hadPendingFix) LiveTrackingSuspectResolution.REJECTED else LiveTrackingSuspectResolution.NONE,
        )
    }

    private fun currentFixAgeRule(startupStaleReason: String): Pair<Long, String> =
        if (freshInitialFixEstablished) {
            MAX_LIVE_TRACKING_FIX_AGE_MILLIS to "stale_fix"
        } else {
            MAX_STARTUP_LIVE_TRACKING_FIX_AGE_MILLIS to startupStaleReason
        }

    private fun accept(
        fix: LiveTrackingLocationFix,
        ageMillis: Long?,
        reason: String,
        metrics: MovementMetrics? = null,
        suspectResolution: LiveTrackingSuspectResolution = LiveTrackingSuspectResolution.NONE,
    ): LiveTrackingLocationQualityDecision {
        freshInitialFixEstablished = true
        lastAcceptedFix = fix
        pendingFix = null
        return decision(
            result = LiveTrackingLocationQualityResult.ACCEPT,
            reason = reason,
            accuracyMeters = fix.accuracyMeters,
            ageMillis = ageMillis,
            metrics = metrics,
            suspectResolution = suspectResolution,
        )
    }

    private fun LiveTrackingLocationFix.hasValidCoordinates(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0

    private fun confirmsMovement(
        previous: LiveTrackingLocationFix,
        current: LiveTrackingLocationFix,
    ): Boolean {
        val distance = distanceMeters(previous, current)
        val reportedSpeed = current.speedMetersPerSecond
        return distance >= MIN_CONFIRMATION_MOVEMENT_METERS ||
            (reportedSpeed != null && reportedSpeed >= MIN_REPORTED_MOVING_SPEED_METERS_PER_SECOND)
    }

    private fun isNear(
        first: LiveTrackingLocationFix,
        second: LiveTrackingLocationFix,
        minimumRadiusMeters: Double,
    ): Boolean = distanceMeters(first, second) <= uncertaintyRadius(first, second, minimumRadiusMeters)

    private fun uncertaintyRadius(
        first: LiveTrackingLocationFix,
        second: LiveTrackingLocationFix,
        minimumRadiusMeters: Double,
    ): Double {
        val combinedAccuracy =
            (first.accuracyMeters ?: 0f).toDouble() + (second.accuracyMeters ?: 0f).toDouble()
        return max(
            minimumRadiusMeters,
            min(MAX_UNCERTAINTY_RADIUS_METERS, combinedAccuracy * 2.0),
        )
    }

    private fun movementMetrics(
        previous: LiveTrackingLocationFix,
        current: LiveTrackingLocationFix,
    ): MovementMetrics {
        val distance = distanceMeters(previous, current)
        val deltaMillis = locationTimestampDeltaMillis(previous, current)
        val impliedSpeed = deltaMillis?.takeIf { it > 0 }?.let { distance / (it / 1000.0) }
        val speedEvidence =
            if (impliedSpeed == null || current.speedMetersPerSecond == null) {
                LiveTrackingSpeedEvidence.UNAVAILABLE
            } else {
                val difference = abs(impliedSpeed - current.speedMetersPerSecond)
                val tolerance = max(MIN_SPEED_DIFFERENCE_METERS_PER_SECOND, impliedSpeed * SPEED_TOLERANCE)
                when {
                    difference <= tolerance -> LiveTrackingSpeedEvidence.CORROBORATED
                    current.speedAccuracyMetersPerSecond != null &&
                        difference > max(tolerance, current.speedAccuracyMetersPerSecond * 2.0) ->
                        LiveTrackingSpeedEvidence.CONTRADICTED
                    else -> LiveTrackingSpeedEvidence.UNAVAILABLE
                }
            }
        return MovementMetrics(
            distanceMeters = distance,
            timeDeltaMillis = deltaMillis,
            impliedSpeedMetersPerSecond = impliedSpeed,
            speedAccuracyMetersPerSecond = current.speedAccuracyMetersPerSecond,
            effectiveJumpThresholdMeters = MIN_SUSPICIOUS_JUMP_METERS,
            poorAccuracy = current.accuracyMeters?.toDouble()?.let { it >= POOR_ACCURACY_METERS } ?: false,
            speedEvidence = speedEvidence,
        )
    }

    @Suppress("ReturnCount")
    private fun MovementMetrics.isSuspicious(): Boolean {
        val largeJump = distanceMeters >= effectiveJumpThresholdMeters
        if (!largeJump) return false
        val speed = impliedSpeedMetersPerSecond ?: return true
        if (speed < MIN_SUSPICIOUS_SPEED_METERS_PER_SECOND &&
            !(poorAccuracy && speedEvidence != LiveTrackingSpeedEvidence.CORROBORATED)
        ) {
            return false
        }
        return speedEvidence != LiveTrackingSpeedEvidence.CORROBORATED
    }

    @Suppress("LongParameterList")
    private fun decision(
        result: LiveTrackingLocationQualityResult,
        reason: String,
        accuracyMeters: Float?,
        ageMillis: Long?,
        metrics: MovementMetrics? = null,
        suspectResolution: LiveTrackingSuspectResolution = LiveTrackingSuspectResolution.NONE,
    ): LiveTrackingLocationQualityDecision =
        LiveTrackingLocationQualityDecision(
            result = result,
            reason = reason,
            accuracyMeters = accuracyMeters,
            fixAgeMillis = ageMillis,
            distanceFromPreviousMeters = metrics?.distanceMeters,
            impliedSpeedMetersPerSecond = metrics?.impliedSpeedMetersPerSecond,
            timeDeltaFromPreviousAcceptedFixMillis = metrics?.timeDeltaMillis,
            suspectResolution = suspectResolution,
            speedAccuracyMetersPerSecond = metrics?.speedAccuracyMetersPerSecond,
            effectiveJumpThresholdMeters = metrics?.effectiveJumpThresholdMeters,
            poorAccuracy = metrics?.poorAccuracy ?: false,
            speedEvidence = metrics?.speedEvidence ?: LiveTrackingSpeedEvidence.UNAVAILABLE,
        )

    private data class MovementMetrics(
        val distanceMeters: Double,
        val timeDeltaMillis: Long?,
        val impliedSpeedMetersPerSecond: Double?,
        val speedAccuracyMetersPerSecond: Float?,
        val effectiveJumpThresholdMeters: Double,
        val poorAccuracy: Boolean,
        val speedEvidence: LiveTrackingSpeedEvidence,
    )

    private data class PendingFix(
        val fix: LiveTrackingLocationFix,
        val confirmations: Int,
    )

    private companion object {
        // Two minutes keeps delayed cached fixes out of the live stream while allowing normal callback jitter.
        const val MAX_LIVE_TRACKING_FIX_AGE_MILLIS = 2 * 60 * 1000L

        // Accuracy never raises this floor; large jumps need timestamp/speed evidence.
        const val MIN_SUSPICIOUS_JUMP_METERS = 400.0
        const val MIN_SUSPICIOUS_SPEED_METERS_PER_SECOND = 8.0
        const val MIN_SPEED_DIFFERENCE_METERS_PER_SECOND = 5.0
        const val SPEED_TOLERANCE = 0.5

        // This is confidence evidence for large jumps only, never a rejection threshold.
        const val POOR_ACCURACY_METERS = 100.0
        const val RECOVERY_RADIUS_METERS = 150.0
        const val CONFIRMATION_RADIUS_METERS = 100.0

        // Three fresh fixes in the candidate area let a real relocation converge even if the user stops there.
        const val MIN_PENDING_AREA_CONFIRMATIONS = 3
        const val MIN_CONFIRMATION_MOVEMENT_METERS = 25.0
        const val MIN_REPORTED_MOVING_SPEED_METERS_PER_SECOND = 2.0f
        const val MAX_UNCERTAINTY_RADIUS_METERS = 250.0
    }
}

internal fun isFreshLiveTrackingCachedLocation(
    fix: LiveTrackingLocationFix,
    nowElapsedRealtimeNanos: Long,
    nowEpochMilliseconds: Long,
): Boolean =
    liveTrackingLocationAgeMillis(fix, nowElapsedRealtimeNanos, nowEpochMilliseconds)
        ?.let { it <= MAX_STARTUP_LIVE_TRACKING_FIX_AGE_MILLIS }
        ?: false

internal fun liveTrackingRescueCooldownRemainingMillis(
    lastRequestElapsedRealtimeNanos: Long?,
    nowElapsedRealtimeNanos: Long,
    cooldownMillis: Long,
): Long? {
    val lastRequest = lastRequestElapsedRealtimeNanos ?: return null
    val elapsedMillis =
        ((nowElapsedRealtimeNanos - lastRequest).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
    return (cooldownMillis - elapsedMillis).takeIf { it > 0L }
}

internal fun Location.toLiveTrackingLocationFix(): LiveTrackingLocationFix =
    LiveTrackingLocationFix(
        latitude = latitude,
        longitude = longitude,
        epochMilliseconds = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos.takeIf { it > 0L },
        accuracyMeters = accuracy.takeIf { hasAccuracy() && it.isFinite() && it >= 0f },
        speedMetersPerSecond = speed.takeIf { hasSpeed() && it.isFinite() && it >= 0f },
        speedAccuracyMetersPerSecond =
            speedAccuracyMetersPerSecond.takeIf {
                hasSpeedAccuracy() && it.isFinite() && it >= 0f
            },
    )

// The fallback order is intentional: monotonic age wins, wall-clock age is only a fallback.
@Suppress("ReturnCount")
internal fun liveTrackingLocationAgeMillis(
    fix: LiveTrackingLocationFix,
    nowElapsedRealtimeNanos: Long,
    nowEpochMilliseconds: Long,
): Long? {
    val elapsedRealtimeNanos = fix.elapsedRealtimeNanos
    if (elapsedRealtimeNanos != null && elapsedRealtimeNanos > 0L && nowElapsedRealtimeNanos >= elapsedRealtimeNanos) {
        return ((nowElapsedRealtimeNanos - elapsedRealtimeNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
    }
    if (fix.epochMilliseconds > 0L && nowEpochMilliseconds >= fix.epochMilliseconds) {
        return (nowEpochMilliseconds - fix.epochMilliseconds).coerceAtLeast(0L)
    }
    return null
}

private fun locationTimestampDeltaMillis(
    previous: LiveTrackingLocationFix,
    current: LiveTrackingLocationFix,
): Long? {
    val previousElapsed = previous.elapsedRealtimeNanos
    val currentElapsed = current.elapsedRealtimeNanos
    if (previousElapsed != null && currentElapsed != null && currentElapsed > previousElapsed) {
        return (currentElapsed - previousElapsed) / NANOS_PER_MILLISECOND
    }
    return (current.epochMilliseconds - previous.epochMilliseconds).takeIf { it > 0L }
}

private fun distanceMeters(
    first: LiveTrackingLocationFix,
    second: LiveTrackingLocationFix,
): Double {
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val latitudeDelta = secondLatitude - firstLatitude
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
    val haversine =
        sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
    return EARTH_RADIUS_METERS * 2.0 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MAX_STARTUP_LIVE_TRACKING_FIX_AGE_MILLIS = 30_000L
