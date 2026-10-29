package com.glancemap.trailcore.recording

/** Missing or out-of-order observations never extend confirmation. */
class RecordingGpsConfirmationWindow {
    private var first = 0L
    private var last = 0L
    private var lastSeen = 0L

    fun reset() {
        first = 0L
        last = 0L
        lastSeen = 0L
    }

    fun crossesGap(
        elapsedMillis: Long,
        intervalMillis: Long,
    ): Boolean =
        last > 0L &&
            elapsedMillis > last &&
            elapsedMillis - last > intervalMillis.coerceIn(7_500L, 10_000L) * 2L

    @Suppress("ReturnCount")
    fun observe(
        elapsedMillis: Long,
        qualifies: Boolean,
        intervalMillis: Long,
    ): Long? {
        if (elapsedMillis <= lastSeen || elapsedMillis <= 0L) return null
        lastSeen = elapsedMillis
        if (!qualifies) {
            first = 0L
            last = 0L
            return null
        }
        if (first == 0L || crossesGap(elapsedMillis, intervalMillis)) first = elapsedMillis
        last = elapsedMillis
        return elapsedMillis - first
    }
}

/** A restart requires several fresh, increasing counter observations. */
class RecordingStepResumeEvidence {
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
        if (invalidValue || invalidTime || arrived - measured !in 0L..15_000L) {
            return null
        }
        val previous = lastValue
        if (previous == null || value <= previous || measured - lastTime > 15_000L) {
            baselineValue = value
            baselineTime = measured
            observations = 0
        }
        observations += 1
        lastValue = value
        lastTime = measured
        val elapsed = measured - baselineTime
        return elapsed.takeIf { observations >= 3 && elapsed >= duration && value - (baselineValue ?: value) >= 4f }
    }
}
