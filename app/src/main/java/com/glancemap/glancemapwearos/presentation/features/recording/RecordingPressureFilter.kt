package com.glancemap.glancemapwearos.presentation.features.recording

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp

/** A monotonic-time, bounded pressure preprocessor shared by live recording and replay. */
data class RecordingPressureSample(
    val pressureHpa: Double,
    val elapsedRealtimeMillis: Long,
)

internal data class RecordingPressureSnapshot(
    val filteredPressureHpa: Double,
    val elapsedRealtimeMillis: Long,
    val observationCount: Long,
    val outlierCount: Long,
    val restartCount: Long,
    val isFresh: Boolean,
    val recentSampleCount: Int,
    val recentSpanMillis: Long,
    val maximumRecentGapMillis: Long,
)

internal data class RecordingPressureDiagnostics(
    val observationCount: Long,
    val invalidCount: Long,
    val outlierCount: Long,
    val restartCount: Long,
    val lastRawPressureHpa: Double?,
    val lastFilteredPressureHpa: Double?,
)

/**
 * Retains only a short, sensor-time history. A time-window median rejects isolated pressure
 * spikes before a time-aware IIR makes the accepted signal suitable for elevation deltas.
 */
internal class RecordingPressureFilter {
    private val acceptedSamples = ArrayDeque<RecordingPressureSample>()
    private var filteredPressureHpa: Double? = null
    private var lastAcceptedElapsedRealtimeMillis: Long? = null
    private var lastObservedElapsedRealtimeMillis: Long? = null
    private var observationCount = 0L
    private var invalidCount = 0L
    private var outlierCount = 0L
    private var restartCount = 0L
    private var lastRawPressureHpa: Double? = null

    @Suppress("ReturnCount")
    @Synchronized
    fun observe(sample: RecordingPressureSample) {
        val pressure = sample.pressureHpa
        val elapsedRealtimeMillis = sample.elapsedRealtimeMillis
        if (
            !pressure.isFinite() ||
            pressure !in MIN_VALID_PRESSURE_HPA..MAX_VALID_PRESSURE_HPA ||
            elapsedRealtimeMillis < 0L
        ) {
            invalidCount += 1
            return
        }
        val previousObserved = lastObservedElapsedRealtimeMillis
        if (previousObserved != null && elapsedRealtimeMillis <= previousObserved) {
            invalidCount += 1
            return
        }
        lastObservedElapsedRealtimeMillis = elapsedRealtimeMillis
        lastRawPressureHpa = pressure
        observationCount += 1

        val previousAccepted = lastAcceptedElapsedRealtimeMillis
        if (previousAccepted != null && elapsedRealtimeMillis - previousAccepted > PRESSURE_RESTART_GAP_MS) {
            restartCount += 1
            acceptedSamples.clear()
            filteredPressureHpa = null
            lastAcceptedElapsedRealtimeMillis = null
        }
        discardExpiredSamples(elapsedRealtimeMillis)
        if (isOutlier(pressure, elapsedRealtimeMillis)) {
            outlierCount += 1
            return
        }
        val previousFiltered = filteredPressureHpa
        val previousFilteredAt = lastAcceptedElapsedRealtimeMillis
        filteredPressureHpa =
            if (previousFiltered == null || previousFilteredAt == null) {
                pressure
            } else {
                val elapsedSeconds = (elapsedRealtimeMillis - previousFilteredAt).coerceAtLeast(1L) / 1_000.0
                val alpha = 1.0 - exp(-elapsedSeconds / PRESSURE_IIR_TIME_CONSTANT_SECONDS)
                previousFiltered + (pressure - previousFiltered) * alpha
            }
        acceptedSamples.addLast(sample)
        while (acceptedSamples.size > MAX_ACCEPTED_PRESSURE_SAMPLES) {
            acceptedSamples.removeFirst()
        }
        lastAcceptedElapsedRealtimeMillis = elapsedRealtimeMillis
    }

    @Suppress("ReturnCount")
    @Synchronized
    fun snapshotAt(elapsedRealtimeMillis: Long): RecordingPressureSnapshot? {
        val filtered = filteredPressureHpa ?: return null
        val acceptedAt = lastAcceptedElapsedRealtimeMillis ?: return null
        val ageMillis = elapsedRealtimeMillis - acceptedAt
        var previousElapsedRealtimeMillis: Long? = null
        var maximumGapMillis = 0L
        acceptedSamples.forEach { sample ->
            previousElapsedRealtimeMillis?.let { previous ->
                maximumGapMillis = maxOf(maximumGapMillis, sample.elapsedRealtimeMillis - previous)
            }
            previousElapsedRealtimeMillis = sample.elapsedRealtimeMillis
        }
        return RecordingPressureSnapshot(
            filteredPressureHpa = filtered,
            elapsedRealtimeMillis = acceptedAt,
            observationCount = observationCount,
            outlierCount = outlierCount,
            restartCount = restartCount,
            isFresh = ageMillis in 0..PRESSURE_STALE_AFTER_MS,
            recentSampleCount = acceptedSamples.size,
            recentSpanMillis =
                acceptedSamples.firstOrNull()?.let { first ->
                    acceptedAt - first.elapsedRealtimeMillis
                } ?: 0L,
            maximumRecentGapMillis = maximumGapMillis,
        )
    }

    @Synchronized
    fun reset() {
        acceptedSamples.clear()
        filteredPressureHpa = null
        lastAcceptedElapsedRealtimeMillis = null
        lastObservedElapsedRealtimeMillis = null
        observationCount = 0L
        invalidCount = 0L
        outlierCount = 0L
        restartCount = 0L
        lastRawPressureHpa = null
    }

    @Synchronized
    fun diagnostics(): RecordingPressureDiagnostics =
        RecordingPressureDiagnostics(
            observationCount = observationCount,
            invalidCount = invalidCount,
            outlierCount = outlierCount,
            restartCount = restartCount,
            lastRawPressureHpa = lastRawPressureHpa,
            lastFilteredPressureHpa = filteredPressureHpa,
        )

    private fun discardExpiredSamples(nowElapsedRealtimeMillis: Long) {
        while (
            acceptedSamples.firstOrNull()?.let {
                nowElapsedRealtimeMillis - it.elapsedRealtimeMillis > PRESSURE_HISTORY_WINDOW_MS
            } == true
        ) {
            acceptedSamples.removeFirst()
        }
    }

    private fun isOutlier(
        pressureHpa: Double,
        elapsedRealtimeMillis: Long,
    ): Boolean {
        if (acceptedSamples.size < MIN_SAMPLES_FOR_OUTLIER_CHECK) return false
        val values = acceptedSamples.map(RecordingPressureSample::pressureHpa).sorted()
        val median = values.median()
        val medianAbsoluteDeviation = values.map { abs(it - median) }.sorted().median()
        val historyAgeSeconds =
            acceptedSamples.first().let {
                (elapsedRealtimeMillis - it.elapsedRealtimeMillis).coerceAtLeast(0L) / 1_000.0
            }
        val allowedDeviation =
            maxOf(
                MIN_PRESSURE_OUTLIER_DEVIATION_HPA,
                medianAbsoluteDeviation * MAD_OUTLIER_MULTIPLIER +
                    historyAgeSeconds * MAX_EXPECTED_PRESSURE_CHANGE_HPA_PER_SECOND,
            )
        return abs(pressureHpa - median) > allowedDeviation
    }
}

private fun List<Double>.median(): Double = this[(size - 1) / 2]

private const val MIN_VALID_PRESSURE_HPA = 300.0
private const val MAX_VALID_PRESSURE_HPA = 1_100.0
private const val PRESSURE_HISTORY_WINDOW_MS = 20_000L
private const val PRESSURE_RESTART_GAP_MS = 12_000L
private const val PRESSURE_STALE_AFTER_MS = 7_000L
private const val MAX_ACCEPTED_PRESSURE_SAMPLES = 24
private const val MIN_SAMPLES_FOR_OUTLIER_CHECK = 4
private const val PRESSURE_IIR_TIME_CONSTANT_SECONDS = 2.5
private const val MIN_PRESSURE_OUTLIER_DEVIATION_HPA = 0.18
private const val MAD_OUTLIER_MULTIPLIER = 4.5
private const val MAX_EXPECTED_PRESSURE_CHANGE_HPA_PER_SECOND = 0.10
