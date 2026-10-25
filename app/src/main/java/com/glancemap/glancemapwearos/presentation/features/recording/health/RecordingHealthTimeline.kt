package com.glancemap.glancemapwearos.presentation.features.recording.health

internal data class RecordingHealthDelivery(
    val historical: List<RecordingHealthSample>,
    val live: List<RecordingHealthSample>,
)

internal class RecordingHealthTimeline(
    private val freshnessMillis: Long = 15_000L,
    private val duplicateCapacity: Int = 4_096,
) {
    private val seen = LinkedHashSet<Pair<RecordingHealthMetric, Long>>()
    private val latestLiveTimes = mutableMapOf<RecordingHealthMetric, Long>()

    init {
        require(freshnessMillis >= 0L)
        require(duplicateCapacity > 0)
    }

    fun accept(
        samples: List<RecordingHealthSample>,
        arrivalElapsedRealtimeMillis: Long,
    ): RecordingHealthDelivery {
        val historical =
            samples
                .filter { it.isValidAt(arrivalElapsedRealtimeMillis) }
                .sortedBy { it.measuredElapsedRealtimeMillis }
                .filter { sample ->
                    seen.add(sample.kind to sample.measuredElapsedRealtimeMillis).also { added ->
                        if (added && seen.size > duplicateCapacity) {
                            seen.iterator().apply {
                                next()
                                remove()
                            }
                        }
                    }
                }
        val live =
            historical
                .groupBy { it.kind }
                .values
                .mapNotNull { values ->
                    val newest = values.last()
                    val measured = newest.measuredElapsedRealtimeMillis
                    if (
                        arrivalElapsedRealtimeMillis - measured <= freshnessMillis &&
                        measured > (latestLiveTimes[newest.kind] ?: -1L)
                    ) {
                        latestLiveTimes[newest.kind] = measured
                        newest
                    } else {
                        null
                    }
                }
        return RecordingHealthDelivery(historical, live)
    }

    private fun RecordingHealthSample.isValidAt(arrival: Long): Boolean {
        if (!value.isFinite() ||
            measuredElapsedRealtimeMillis !in 0L..arrival ||
            measuredWallTimeMillis <= 0L
        ) {
            return false
        }
        val intervalValid = intervalStartElapsedRealtimeMillis?.let { it in 0L..measuredElapsedRealtimeMillis } ?: true
        return intervalValid &&
            when (kind) {
                RecordingHealthMetric.HEART_RATE -> value > 0.0
                RecordingHealthMetric.STEPS_TOTAL, RecordingHealthMetric.CADENCE, RecordingHealthMetric.STEP_DELTA ->
                    value >=
                        0.0
            }
    }
}
