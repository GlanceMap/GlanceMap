package com.glancemap.glancemapwearos.presentation.features.navigate.motion

import com.glancemap.glancemapwearos.presentation.features.navigate.moveLatLong
import org.mapsforge.core.model.LatLong
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A visual-only trajectory layered on top of accepted location fixes.
 *
 * Each accepted fix replaces the motion anchor. The difference between that new trajectory and
 * the position already visible to the user is retained as a world-space offset which decays while
 * forward motion continues. This avoids the old stop/correct/restart sequence without changing the
 * GPS request cadence or the coordinates used for recording.
 */
internal class MarkerVisualTrajectory {
    private var anchor: MarkerVisualAnchor? = null
    private var correction: MarkerVisualCorrection? = null

    fun reset(nowElapsedMs: Long? = null): MarkerVisualCorrectionEnd? {
        val interruptedCorrection = nowElapsedMs?.let(::interruptedCorrectionAt)
        anchor = null
        correction = null
        return interruptedCorrection
    }

    fun seed(anchor: MarkerVisualAnchor): MarkerVisualCorrectionEnd? {
        val interruptedCorrection = interruptedCorrectionAt(anchor.fixElapsedMs)
        this.anchor = anchor
        correction = null
        return interruptedCorrection
    }

    fun rebase(
        anchor: MarkerVisualAnchor,
        displayedAtRebase: LatLong,
        nowElapsedMs: Long,
        predictionWindow: MarkerPredictionWindow,
        correctionPlan: MarkerVisualCorrectionPlan,
    ): MarkerVisualCorrectionEnd? {
        val interruptedCorrection = interruptedCorrectionAt(nowElapsedMs)
        val baseAtRebase = projectAnchor(anchor, nowElapsedMs, predictionWindow)
        val correctionDistanceM = markerTrajectoryDistanceMeters(baseAtRebase, displayedAtRebase)
        this.anchor = anchor
        correction =
            if (correctionDistanceM >= MIN_CORRECTION_OFFSET_M && correctionPlan.durationMs > 0L) {
                MarkerVisualCorrection(
                    bearingDeg = markerTrajectoryBearingDegrees(baseAtRebase, displayedAtRebase),
                    distanceM = correctionDistanceM,
                    startedAtElapsedMs = nowElapsedMs,
                    durationMs = correctionPlan.durationMs,
                    reason = correctionPlan.reason,
                    bypassRemovalRateLimit = correctionPlan.bypassRemovalRateLimit,
                    remainingDistanceM = correctionDistanceM,
                    lastSampleAtElapsedMs = nowElapsedMs,
                )
            } else {
                null
            }
        return interruptedCorrection
    }

    fun sample(
        nowElapsedMs: Long,
        predictionWindow: MarkerPredictionWindow,
    ): MarkerVisualTrajectorySample? {
        val currentAnchor = anchor
        return if (currentAnchor == null) {
            null
        } else {
            val context =
                MarkerVisualSampleContext(
                    anchor = currentAnchor,
                    nowElapsedMs = nowElapsedMs,
                    predictionWindow = predictionWindow,
                    baseLatLong = projectAnchor(currentAnchor, nowElapsedMs, predictionWindow),
                    predictedDistanceM = projectedDistanceMeters(currentAnchor, nowElapsedMs, predictionWindow),
                )
            correction?.let { activeCorrection ->
                sampleWithCorrection(context, activeCorrection)
            } ?: context.baseSample()
        }
    }

    private fun sampleWithCorrection(
        context: MarkerVisualSampleContext,
        activeCorrection: MarkerVisualCorrection,
    ): MarkerVisualTrajectorySample {
        val progress = advanceCorrection(context, activeCorrection)
        return if (progress.remainingDistanceM < MIN_CORRECTION_OFFSET_M) {
            correction = null
            context.baseSample(
                correctionReason = activeCorrection.reason,
                correctionAgeMs = progress.elapsedMs,
                settledCorrectionDurationMs = progress.elapsedMs,
            )
        } else {
            MarkerVisualTrajectorySample(
                latLong =
                    moveLatLong(
                        start = context.baseLatLong,
                        bearing = activeCorrection.bearingDeg,
                        distanceMeters = progress.remainingDistanceM,
                    ),
                baseLatLong = context.baseLatLong,
                predictedDistanceM = context.predictedDistanceM,
                correctionRemainingM = progress.remainingDistanceM,
                correctionActive = context.nowElapsedMs <= progress.windowEndElapsedMs,
                correctionReason = activeCorrection.reason,
                correctionAgeMs = progress.elapsedMs,
                settledCorrectionDurationMs = null,
            )
        }
    }

    private fun advanceCorrection(
        context: MarkerVisualSampleContext,
        activeCorrection: MarkerVisualCorrection,
    ): MarkerVisualCorrectionProgress {
        val nowElapsedMs = context.nowElapsedMs
        val elapsedMs = (nowElapsedMs - activeCorrection.startedAtElapsedMs).coerceAtLeast(0L)
        val fraction =
            (elapsedMs.toDouble() / activeCorrection.durationMs.coerceAtLeast(1L).toDouble())
                .coerceIn(0.0, 1.0)
        val scheduledRemainingDistanceM =
            (activeCorrection.distanceM * (1.0 - smoothStep(fraction))).toFloat()
        val correctionWindowEndElapsedMs = context.anchor.fixElapsedMs + context.predictionWindow.stopAtMs
        val rateLimitedSampleAtElapsedMs =
            minOf(nowElapsedMs, correctionWindowEndElapsedMs)
        val previousRateLimitedSampleAtElapsedMs =
            minOf(activeCorrection.lastSampleAtElapsedMs, correctionWindowEndElapsedMs)
        val sampleIntervalMs =
            (rateLimitedSampleAtElapsedMs - previousRateLimitedSampleAtElapsedMs).coerceAtLeast(0L)
        val projectedBaseAdvanceM =
            (
                projectedDistanceMeters(context.anchor, rateLimitedSampleAtElapsedMs, context.predictionWindow) -
                    projectedDistanceMeters(
                        context.anchor,
                        previousRateLimitedSampleAtElapsedMs,
                        context.predictionWindow,
                    )
            ).coerceAtLeast(0f)
        val rateLimitedRemainingDistanceM =
            if (activeCorrection.bypassRemovalRateLimit) {
                scheduledRemainingDistanceM
            } else {
                val maximumRemovalM =
                    maximumCorrectionRemovalMeters(
                        anchor = context.anchor,
                        correction = activeCorrection,
                        projectedBaseAdvanceM = projectedBaseAdvanceM,
                        sampleIntervalMs = sampleIntervalMs,
                    )
                (activeCorrection.remainingDistanceM - maximumRemovalM).coerceAtLeast(0f)
            }
        val remainingDistanceM =
            maxOf(scheduledRemainingDistanceM, rateLimitedRemainingDistanceM)
                .coerceAtMost(activeCorrection.remainingDistanceM)
        activeCorrection.remainingDistanceM = remainingDistanceM
        activeCorrection.lastSampleAtElapsedMs =
            maxOf(activeCorrection.lastSampleAtElapsedMs, nowElapsedMs)
        return MarkerVisualCorrectionProgress(
            elapsedMs = elapsedMs,
            remainingDistanceM = remainingDistanceM,
            windowEndElapsedMs = correctionWindowEndElapsedMs,
        )
    }

    fun hasActiveCorrection(
        nowElapsedMs: Long,
        predictionWindow: MarkerPredictionWindow,
    ): Boolean {
        val currentAnchor = anchor ?: return false
        return correction != null &&
            nowElapsedMs <= currentAnchor.fixElapsedMs + predictionWindow.stopAtMs
    }

    fun currentAnchor(): MarkerVisualAnchor? = anchor

    private fun interruptedCorrectionAt(nowElapsedMs: Long): MarkerVisualCorrectionEnd? =
        correction?.let { activeCorrection ->
            MarkerVisualCorrectionEnd(
                reason = activeCorrection.reason,
                durationMs = (nowElapsedMs - activeCorrection.startedAtElapsedMs).coerceAtLeast(0L),
            )
        }
}

internal data class MarkerVisualAnchor(
    val latLong: LatLong,
    val fixElapsedMs: Long,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float?,
)

internal data class MarkerPredictionWindow(
    val startDelayMs: Long,
    val fullSpeedUntilMs: Long,
    val stopAtMs: Long,
)

internal data class MarkerVisualCorrectionPlan(
    val durationMs: Long,
    val reason: String = "gps_correction",
    val bypassRemovalRateLimit: Boolean = false,
)

internal data class MarkerVisualCorrectionEnd(
    val reason: String,
    val durationMs: Long,
)

internal data class MarkerVisualTrajectorySample(
    val latLong: LatLong,
    val baseLatLong: LatLong,
    val predictedDistanceM: Float,
    val correctionRemainingM: Float,
    val correctionActive: Boolean,
    val correctionReason: String?,
    val correctionAgeMs: Long?,
    val settledCorrectionDurationMs: Long?,
)

private data class MarkerVisualCorrection(
    val bearingDeg: Float,
    val distanceM: Float,
    val startedAtElapsedMs: Long,
    val durationMs: Long,
    val reason: String,
    val bypassRemovalRateLimit: Boolean,
    var remainingDistanceM: Float,
    var lastSampleAtElapsedMs: Long,
)

private data class MarkerVisualSampleContext(
    val anchor: MarkerVisualAnchor,
    val nowElapsedMs: Long,
    val predictionWindow: MarkerPredictionWindow,
    val baseLatLong: LatLong,
    val predictedDistanceM: Float,
) {
    fun baseSample(
        correctionReason: String? = null,
        correctionAgeMs: Long? = null,
        settledCorrectionDurationMs: Long? = null,
    ): MarkerVisualTrajectorySample =
        MarkerVisualTrajectorySample(
            latLong = baseLatLong,
            baseLatLong = baseLatLong,
            predictedDistanceM = predictedDistanceM,
            correctionRemainingM = 0f,
            correctionActive = false,
            correctionReason = correctionReason,
            correctionAgeMs = correctionAgeMs,
            settledCorrectionDurationMs = settledCorrectionDurationMs,
        )
}

private data class MarkerVisualCorrectionProgress(
    val elapsedMs: Long,
    val remainingDistanceM: Float,
    val windowEndElapsedMs: Long,
)

/**
 * Limits offset removal so a prediction that was ahead of the next fix decelerates instead of
 * visibly reversing. Cross-track and trailing corrections are also capped to avoid a sharp snap.
 */
private fun maximumCorrectionRemovalMeters(
    anchor: MarkerVisualAnchor,
    correction: MarkerVisualCorrection,
    projectedBaseAdvanceM: Float,
    sampleIntervalMs: Long,
): Float {
    val nominalSpeedMps = anchor.speedMps.coerceAtLeast(0f)
    val motionBearingDeg = anchor.bearingDeg
    return if (motionBearingDeg == null || nominalSpeedMps < MIN_FORWARD_PRESERVATION_SPEED_MPS) {
        MIN_STATIONARY_CORRECTION_VELOCITY_MPS * sampleIntervalMs / 1_000f
    } else {
        val maximumVelocityRemovalM = MAX_CORRECTION_VELOCITY_MPS * sampleIntervalMs / 1_000f
        val generalRemovalM =
            (projectedBaseAdvanceM * GENERAL_CORRECTION_SPEED_FRACTION)
                .coerceIn(0f, maximumVelocityRemovalM)
        val relativeBearingRad =
            Math.toRadians(
                ((((correction.bearingDeg - motionBearingDeg) + 540f) % 360f) - 180f).toDouble(),
            )
        val forwardOffsetFraction = cos(relativeBearingRad).toFloat()
        if (forwardOffsetFraction <= 0f) {
            generalRemovalM
        } else {
            val forwardSafeRemovalM =
                projectedBaseAdvanceM * FORWARD_CORRECTION_SPEED_FRACTION / forwardOffsetFraction
            minOf(generalRemovalM, forwardSafeRemovalM).coerceAtLeast(0f)
        }
    }
}

private fun projectAnchor(
    anchor: MarkerVisualAnchor,
    nowElapsedMs: Long,
    predictionWindow: MarkerPredictionWindow,
): LatLong {
    val distanceM = projectedDistanceMeters(anchor, nowElapsedMs, predictionWindow)
    val bearingDeg = anchor.bearingDeg
    if (distanceM <= 0f || bearingDeg == null) return anchor.latLong
    return moveLatLong(
        start = anchor.latLong,
        bearing = bearingDeg,
        distanceMeters = distanceM,
    )
}

/** Integrates a constant velocity which eases linearly to zero near the prediction horizon. */
private fun projectedDistanceMeters(
    anchor: MarkerVisualAnchor,
    nowElapsedMs: Long,
    predictionWindow: MarkerPredictionWindow,
): Float {
    val ageMs = (nowElapsedMs - anchor.fixElapsedMs).coerceAtLeast(0L)
    return if (
        anchor.speedMps <= 0f ||
        anchor.bearingDeg == null ||
        ageMs <= predictionWindow.startDelayMs
    ) {
        0f
    } else {
        integrateProjectedDistanceMeters(anchor.speedMps, ageMs, predictionWindow)
    }
}

private fun integrateProjectedDistanceMeters(
    speedMps: Float,
    ageMs: Long,
    predictionWindow: MarkerPredictionWindow,
): Float {
    val effectiveAgeMs = ageMs - predictionWindow.startDelayMs
    val fullSpeedUntilMs =
        (predictionWindow.fullSpeedUntilMs - predictionWindow.startDelayMs)
            .coerceAtLeast(0L)
    val stopAtMs =
        (predictionWindow.stopAtMs - predictionWindow.startDelayMs)
            .coerceAtLeast(fullSpeedUntilMs)
    val boundedAgeMs = effectiveAgeMs.coerceAtMost(stopAtMs)
    val fullSpeedDistanceSeconds = minOf(boundedAgeMs, fullSpeedUntilMs) / 1_000.0
    val easedDistanceSeconds =
        if (boundedAgeMs <= fullSpeedUntilMs || stopAtMs <= fullSpeedUntilMs) {
            0.0
        } else {
            val easeDurationMs = stopAtMs - fullSpeedUntilMs
            val easeElapsedMs = boundedAgeMs - fullSpeedUntilMs
            val easeFraction = (easeElapsedMs.toDouble() / easeDurationMs.toDouble()).coerceIn(0.0, 1.0)
            (easeDurationMs / 1_000.0) *
                (easeFraction - 0.5 * easeFraction * easeFraction)
        }
    return (speedMps * (fullSpeedDistanceSeconds + easedDistanceSeconds)).toFloat()
}

private fun smoothStep(fraction: Double): Double = fraction * fraction * (3.0 - 2.0 * fraction)

private fun markerTrajectoryBearingDegrees(
    from: LatLong,
    to: LatLong,
): Float {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    var bearing = Math.toDegrees(atan2(y, x)).toFloat() % 360f
    if (bearing < 0f) bearing += 360f
    return bearing
}

private fun markerTrajectoryDistanceMeters(
    from: LatLong,
    to: LatLong,
): Float {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val a =
        sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
    return (EARTH_RADIUS_METERS * 2.0 * asin(sqrt(a))).toFloat()
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val MIN_CORRECTION_OFFSET_M = 0.02f
private const val MIN_FORWARD_PRESERVATION_SPEED_MPS = 0.35f
private const val GENERAL_CORRECTION_SPEED_FRACTION = 0.75f
private const val FORWARD_CORRECTION_SPEED_FRACTION = 0.7f
private const val MIN_STATIONARY_CORRECTION_VELOCITY_MPS = 0.75f
private const val MAX_CORRECTION_VELOCITY_MPS = 4f
