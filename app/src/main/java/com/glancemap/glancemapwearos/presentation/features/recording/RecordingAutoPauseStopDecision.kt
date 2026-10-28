package com.glancemap.glancemapwearos.presentation.features.recording

import org.mapsforge.core.model.LatLong

internal enum class RecordingPauseStepEvidence { UNKNOWN, BASELINE, UNCHANGED, ADVANCED }

/** A delayed cumulative count cannot establish when the missing steps happened. */
internal class RecordingPauseStepTracker {
    private var lastCount: Int? = null
    private var lastTime = 0L
    private var lastSource: String? = null

    fun reset() {
        lastCount = null
        lastTime = 0L
        lastSource = null
    }

    @Suppress("ReturnCount")
    fun observe(
        count: Int?,
        measuredMillis: Long,
        fixMillis: Long,
        source: String,
    ): RecordingPauseStepEvidence {
        if (count == null || count < 0) return RecordingPauseStepEvidence.UNKNOWN
        if (measuredMillis <= 0L || fixMillis - measuredMillis !in 0L..STEP_FRESHNESS_MS) {
            return RecordingPauseStepEvidence.UNKNOWN
        }
        if (source == lastSource && measuredMillis < lastTime) return RecordingPauseStepEvidence.UNKNOWN
        if (source == lastSource && measuredMillis == lastTime) {
            return if (count == lastCount) RecordingPauseStepEvidence.UNCHANGED else RecordingPauseStepEvidence.UNKNOWN
        }
        val previous = lastCount
        val validBaseline = canCompare(count, source)
        val result =
            when {
                !validBaseline -> RecordingPauseStepEvidence.BASELINE
                count > checkNotNull(previous) -> RecordingPauseStepEvidence.ADVANCED
                else -> RecordingPauseStepEvidence.UNCHANGED
            }
        lastCount = count
        lastTime = measuredMillis
        lastSource = source
        return result
    }

    private fun canCompare(
        count: Int,
        source: String,
    ): Boolean =
        source == lastSource &&
            lastCount != null &&
            count >= checkNotNull(lastCount)
}

internal class RecordingPausePositionAnchor {
    private var anchor: LatLong? = null

    fun reset() {
        anchor = null
    }

    fun distanceMeters(
        previous: LatLong,
        current: LatLong,
        gapRestart: Boolean,
    ): Double {
        val previousDistance = haversineMeters(previous, current)
        val anchorDistance = anchor?.takeUnless { gapRestart }?.let { haversineMeters(it, current) } ?: 0.0
        return maxOf(previousDistance, anchorDistance)
    }

    fun update(
        current: LatLong,
        stationary: Boolean,
        durationMillis: Long?,
        ignoredTimestamp: Boolean,
    ) {
        if (ignoredTimestamp) return
        if (!stationary) {
            anchor = null
        } else if (durationMillis == 0L) {
            anchor = current
        }
    }
}

internal enum class RecordingStopReason {
    POOR_ACCURACY,
    OUTSIDE_RADIUS,
    FRESH_STEPS,
    MOVING_SPEED,
    STATIONARY,
    UNCERTAIN_SPEED,
}

internal data class RecordingStopDecision(
    val stationary: Boolean,
    val reason: RecordingStopReason,
)

@Suppress("LongParameterList")
internal fun recordingAutoPauseStopDecision(
    bike: Boolean,
    reliable: Boolean,
    distanceMeters: Double,
    radiusMeters: Double,
    speedMps: Float?,
    speedAccuracyMps: Float?,
    stopSpeedMps: Float,
    resumeSpeedMps: Float,
    steps: RecordingPauseStepEvidence,
): RecordingStopDecision {
    val speedReason = recordingStopSpeedReason(bike, speedMps, speedAccuracyMps, stopSpeedMps, resumeSpeedMps)
    val reason =
        when {
            !reliable -> RecordingStopReason.POOR_ACCURACY
            !distanceMeters.isFinite() || distanceMeters > radiusMeters -> RecordingStopReason.OUTSIDE_RADIUS
            !bike && steps == RecordingPauseStepEvidence.ADVANCED -> RecordingStopReason.FRESH_STEPS
            else -> speedReason
        }
    return RecordingStopDecision(
        stationary = reason == RecordingStopReason.STATIONARY || reason == RecordingStopReason.UNCERTAIN_SPEED,
        reason = reason,
    )
}

private fun recordingStopSpeedReason(
    bike: Boolean,
    speedMps: Float?,
    speedAccuracyMps: Float?,
    stopSpeedMps: Float,
    resumeSpeedMps: Float,
): RecordingStopReason {
    val speed = speedMps?.takeIf { it.isFinite() && it >= 0f }
    val accuracy = speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f }
    val uncertain =
        !bike &&
            speed != null &&
            accuracy != null &&
            isUncertainSlowSpeed(speed, accuracy, stopSpeedMps, resumeSpeedMps)
    return when {
        uncertain -> RecordingStopReason.UNCERTAIN_SPEED
        speed != null && speed > stopSpeedMps -> RecordingStopReason.MOVING_SPEED
        else -> RecordingStopReason.STATIONARY
    }
}

private fun isUncertainSlowSpeed(
    speed: Float,
    accuracy: Float,
    stop: Float,
    resume: Float,
): Boolean = speed > stop && speed < resume && speed - accuracy <= stop

private const val STEP_FRESHNESS_MS = 15_000L

internal data class RecordingAutoPauseStopSummary(
    val reasons: Map<RecordingStopReason, Int>,
    val resets: Int,
    val gapRestarts: Int,
    val outOfOrder: Int,
)

/** Aggregates decisions instead of emitting a line for every location fix. */
internal class RecordingAutoPauseStopTelemetry {
    private val reasons = mutableMapOf<RecordingStopReason, Int>()
    private var resets = 0
    private var gapRestarts = 0
    private var outOfOrder = 0
    private var lastReport = 0L

    fun reset() {
        reasons.clear()
        resets = 0
        gapRestarts = 0
        outOfOrder = 0
        lastReport = 0L
    }

    @Suppress("LongParameterList")
    fun observe(
        elapsedMillis: Long,
        reason: RecordingStopReason,
        timerReset: Boolean,
        gapRestart: Boolean,
        confirming: Boolean,
        ignoredTimestamp: Boolean = false,
    ): Boolean {
        reasons[reason] = (reasons[reason] ?: 0) + 1
        if (timerReset) resets++
        if (gapRestart) gapRestarts++
        if (ignoredTimestamp) outOfOrder++
        val elapsed = elapsedMillis - lastReport
        val shouldReport = confirming || lastReport == 0L || elapsed >= 30_000L || (timerReset && elapsed >= 10_000L)
        if (shouldReport) lastReport = maxOf(lastReport, elapsedMillis)
        return shouldReport
    }

    fun takeSummary(): RecordingAutoPauseStopSummary {
        val summary = RecordingAutoPauseStopSummary(reasons.toMap(), resets, gapRestarts, outOfOrder)
        reasons.clear()
        resets = 0
        gapRestarts = 0
        outOfOrder = 0
        return summary
    }
}
