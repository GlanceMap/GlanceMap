package com.glancemap.glancemapwearos.core.service.location.config

import kotlin.math.abs

/**
 * Learns a placeholder horizontal accuracy that a watch GNSS chip repeats unchanged.
 *
 * Real accuracy estimates vary from fix to fix. Some watches instead report one fixed value
 * for every direct fix (OnePlus OPWWE251: 125 m). Another brand may use a different number,
 * so a large value repeated exactly over several fixes is treated like the known 125 m one.
 * Only direct watch-GPS (`gps` provider) fixes are observed: a fused fix can legitimately
 * repeat a coarse network value while the user stands still indoors.
 */
internal object WatchGpsConstantAccuracy {
    @Volatile
    var learnedAccuracyM: Float? = null
        private set

    private var candidateAccuracyM: Float? = null
    private var candidateCount = 0
    private var candidateFirstElapsedMs = 0L

    /** Returns true when this observation newly learned a placeholder value. */
    @Synchronized
    fun observe(
        accuracyM: Float,
        elapsedRealtimeMs: Long,
    ): Boolean {
        if (!accuracyM.isFinite() || accuracyM < WATCH_GPS_CONSTANT_ACCURACY_MIN_M) {
            candidateAccuracyM = null
            candidateCount = 0
            return false
        }
        val candidate = candidateAccuracyM
        if (candidate != null && abs(candidate - accuracyM) <= WATCH_GPS_CONSTANT_ACCURACY_TOLERANCE_M) {
            candidateCount += 1
        } else {
            candidateAccuracyM = accuracyM
            candidateCount = 1
            candidateFirstElapsedMs = elapsedRealtimeMs
        }
        val learned =
            candidateCount >= WATCH_GPS_CONSTANT_ACCURACY_MIN_FIXES &&
                elapsedRealtimeMs - candidateFirstElapsedMs >= WATCH_GPS_CONSTANT_ACCURACY_MIN_SPAN_MS &&
                learnedAccuracyM != candidateAccuracyM
        if (learned) learnedAccuracyM = candidateAccuracyM
        return learned
    }

    fun matches(accuracyM: Float): Boolean {
        val learned = learnedAccuracyM ?: return false
        return abs(learned - accuracyM) <= WATCH_GPS_CONSTANT_ACCURACY_TOLERANCE_M
    }

    @Synchronized
    fun reset() {
        learnedAccuracyM = null
        candidateAccuracyM = null
        candidateCount = 0
        candidateFirstElapsedMs = 0L
    }
}

internal const val WATCH_GPS_CONSTANT_ACCURACY_MIN_M = 50f
internal const val WATCH_GPS_CONSTANT_ACCURACY_TOLERANCE_M = 0.05f
internal const val WATCH_GPS_CONSTANT_ACCURACY_MIN_FIXES = 5
internal const val WATCH_GPS_CONSTANT_ACCURACY_MIN_SPAN_MS = 20_000L
