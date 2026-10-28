package com.glancemap.glancemapwearos.presentation.features.recording

/** A missing or out-of-order observation never extends a confirmation period. */
internal class RecordingGpsConfirmationWindow {
    private var first = 0L
    private var last = 0L
    private var lastSeen = 0L

    val lastObservationElapsedMillis: Long get() = lastSeen
    val hasCandidate: Boolean get() = first > 0L

    fun reset() {
        first = 0L
        last = 0L
        lastSeen = 0L
    }

    fun crossesGap(
        elapsedMillis: Long,
        expectedIntervalMillis: Long,
    ): Boolean = last > 0L && elapsedMillis > last && elapsedMillis - last > gpsEvidenceGapLimit(expectedIntervalMillis)

    fun observe(
        elapsedMillis: Long,
        qualifies: Boolean,
        expectedIntervalMillis: Long,
    ): Long? = observeWithLimit(elapsedMillis, qualifies, gpsEvidenceGapLimit(expectedIntervalMillis))

    @Suppress("ReturnCount")
    fun observeWithLimit(
        elapsedMillis: Long,
        qualifies: Boolean,
        maximumGapMillis: Long,
    ): Long? {
        if (elapsedMillis <= lastSeen || elapsedMillis <= 0L) return null
        lastSeen = elapsedMillis
        if (!qualifies) {
            first = 0L
            last = 0L
            return null
        }
        if (first == 0L || (last > 0L && elapsedMillis - last > maximumGapMillis)) first = elapsedMillis
        last = elapsedMillis
        return elapsedMillis - first
    }
}

internal fun gpsEvidenceGapLimit(interval: Long): Long = interval.coerceIn(7_500L, 10_000L) * 2L

internal class RecordingStepResumeEvidence {
    private var baselineValue: Float? = null
    private var baselineTime = 0L
    private var lastValue: Float? = null
    private var lastTime = 0L
    private var observations = 0

    fun reset() {
        baselineValue = null
        baselineTime = 0L
        lastValue = null
        lastTime = 0L
        observations = 0
    }

    @Suppress("ReturnCount")
    fun observe(
        value: Float,
        measured: Long,
        arrived: Long,
        eligible: Boolean,
        duration: Long,
    ): Long? {
        if (!eligible) {
            reset()
            return null
        }
        val invalidValue = !value.isFinite() || value < 0f
        val invalidTime = measured <= lastTime || measured <= 0L
        if (invalidValue || invalidTime || arrived - measured !in 0L..SENSOR_FRESHNESS_MS) {
            return null
        }
        val previous = lastValue
        if (previous == null || value <= previous || measured - lastTime > SENSOR_FRESHNESS_MS) {
            baselineValue = value
            baselineTime = measured
            observations = 0
        }
        observations += 1
        lastValue = value
        lastTime = measured
        val elapsed = measured - baselineTime
        return elapsed.takeIf {
            observations >= 3 && elapsed >= duration && value - (baselineValue ?: value) >= MIN_RESTART_STEPS
        }
    }
}

internal class RecordingBikeResumeEvidence {
    private val wheel = RecordingGpsConfirmationWindow()

    fun reset() = wheel.reset()

    @Suppress("ReturnCount")
    fun observe(
        speed: Float?,
        measured: Long,
        arrived: Long,
        eligible: Boolean,
        duration: Long,
    ): Long? {
        if (!eligible) {
            reset()
            return null
        }
        if (measured <= 0L || arrived - measured !in 0L..SENSOR_FRESHNESS_MS || speed == null) return null
        val elapsed =
            wheel.observeWithLimit(
                measured,
                speed.isFinite() && speed in BIKE_RESUME_SPEED_MPS..50f,
                SENSOR_FRESHNESS_MS,
            )
        return elapsed?.takeIf { it >= duration }
    }
}

private const val SENSOR_FRESHNESS_MS = 15_000L
private const val MIN_RESTART_STEPS = 4f
private const val BIKE_RESUME_SPEED_MPS = 1.8f
