package com.glancemap.glancemapwearos.presentation.features.recording

import kotlin.math.roundToInt

data class RecordingHeartRateObservation(
    val atMillis: Long,
    val bpm: Double,
    val source: String,
)

data class RecordingHeartRateWindow(
    val startMillis: Long,
    val endMillis: Long = Long.MAX_VALUE,
    val source: String,
)

data class RecordingHeartRateStatisticsState(
    val weightedBpmMillis: Double = 0.0,
    val coveredMillis: Long = 0L,
    val maxBpm: Double? = null,
    val committedBeforeMillis: Long = Long.MIN_VALUE,
    val recentObservations: List<RecordingHeartRateObservation> = emptyList(),
    val recentWindows: List<RecordingHeartRateWindow> = emptyList(),
)

data class RecordingHeartRateSummary(
    val averageBpm: Int?,
    val maxBpm: Int?,
    val coveredMillis: Long,
)

internal class RecordingHeartRateStatistics(
    initial: RecordingHeartRateStatisticsState = RecordingHeartRateStatisticsState(),
) {
    private var prefix = initial.copy(recentObservations = emptyList(), recentWindows = emptyList())
    private val observations =
        initial.recentObservations.associateBy { it.source to it.atMillis }.toMutableMap()
    private var windows = initial.recentWindows.toList()

    @Synchronized
    fun setActive(
        active: Boolean,
        source: String,
        atMillis: Long,
    ) {
        if (atMillis !in prefix.committedBeforeMillis.coerceAtLeast(0L) until Long.MAX_VALUE) return
        if (!active) {
            windows =
                windows
                    .filter { it.startMillis < atMillis }
                    .map { it.copy(endMillis = minOf(it.endMillis, atMillis)) }
        } else {
            startWindow(source, atMillis)
        }
    }

    private fun startWindow(
        source: String,
        atMillis: Long,
    ) {
        val current = windows.firstOrNull { atMillis >= it.startMillis && atMillis < it.endMillis }
        if (current?.source == source) return
        val end = current?.endMillis ?: windows.firstOrNull { it.startMillis > atMillis }?.startMillis ?: Long.MAX_VALUE
        windows =
            buildList {
                windows.forEach { window ->
                    if (window == current) {
                        if (window.startMillis < atMillis) add(window.copy(endMillis = atMillis))
                    } else {
                        add(window)
                    }
                }
                add(RecordingHeartRateWindow(atMillis, end, source))
            }.sortedBy { it.startMillis }.let(::mergeAdjacentWindows)
    }

    @Synchronized
    fun accept(
        observations: List<RecordingHeartRateObservation>,
        nowMillis: Long,
    ) {
        observations.forEach { observation ->
            val validTime =
                observation.atMillis in 0L..nowMillis &&
                    observation.atMillis >= prefix.committedBeforeMillis
            val validBpm = observation.bpm.isFinite() && observation.bpm > 0.0
            if (validTime && validBpm) {
                // A repeated source timestamp is a duplicate, not a replacement measurement.
                this.observations.putIfAbsent(observation.source to observation.atMillis, observation)
            }
        }
        commitBeforeBuffer(nowMillis)
    }

    @Synchronized
    fun snapshot(nowMillis: Long): RecordingHeartRateStatisticsState {
        commitBeforeBuffer(nowMillis)
        return prefix.copy(recentObservations = observations.values.toList(), recentWindows = windows.toList())
    }

    @Synchronized
    fun summary(nowMillis: Long): RecordingHeartRateSummary {
        commitBeforeBuffer(nowMillis)
        val recent = integrate(prefix.committedBeforeMillis.coerceAtLeast(0L), nowMillis, includeEnd = true)
        val covered = prefix.coveredMillis + recent.coveredMillis
        val weighted = prefix.weightedBpmMillis + recent.weightedBpmMillis
        return RecordingHeartRateSummary(
            averageBpm = if (covered > 0L) (weighted / covered).roundToInt() else null,
            maxBpm = maximum(prefix.maxBpm, recent.maxBpm)?.roundToInt(),
            coveredMillis = covered,
        )
    }

    private fun commitBeforeBuffer(nowMillis: Long) {
        val cutoff = (nowMillis - REORDER_BUFFER_MILLIS).coerceAtLeast(0L)
        if (cutoff <= prefix.committedBeforeMillis) return
        val committed = integrate(prefix.committedBeforeMillis.coerceAtLeast(0L), cutoff)
        prefix =
            prefix.copy(
                weightedBpmMillis = prefix.weightedBpmMillis + committed.weightedBpmMillis,
                coveredMillis = prefix.coveredMillis + committed.coveredMillis,
                maxBpm = maximum(prefix.maxBpm, committed.maxBpm),
                committedBeforeMillis = cutoff,
            )
        // Keep one carry sample per source when its fifteen-second tail crosses the watermark.
        val carries =
            observations.values
                .filter { it.atMillis < cutoff && cutoff - it.atMillis < MAX_HOLD_MILLIS }
                .groupBy { it.source }
                .mapValues { (_, values) -> values.maxBy { it.atMillis } }
        observations.entries.removeAll { (_, value) -> value.atMillis < cutoff && carries[value.source] != value }
        windows = windows.filter { it.endMillis > cutoff }
    }

    private fun integrate(
        fromMillis: Long,
        toMillis: Long,
        includeEnd: Boolean = false,
    ): Integral {
        var weighted = 0.0
        var covered = 0L
        var maximum: Double? = null
        val bySource = observations.values.groupBy { it.source }
        windows.forEach { window ->
            if (window.endMillis <= fromMillis || window.startMillis > toMillis) return@forEach
            val samples =
                bySource[window.source]
                    .orEmpty()
                    .filter {
                        it.atMillis >= window.startMillis && it.atMillis < window.endMillis && it.atMillis <= toMillis
                    }.sortedBy { it.atMillis }
            samples.forEachIndexed { index, sample ->
                val start = maxOf(fromMillis, sample.atMillis)
                val holdEnd = sample.atMillis + minOf(MAX_HOLD_MILLIS, Long.MAX_VALUE - sample.atMillis)
                val end =
                    minOf(
                        toMillis,
                        window.endMillis,
                        holdEnd,
                        samples.getOrNull(index + 1)?.atMillis ?: Long.MAX_VALUE,
                    )
                val coveredHere = end > start
                if (coveredHere) {
                    covered += end - start
                    weighted += sample.bpm * (end - start)
                }
                val observedHere = sample.atMillis >= fromMillis && (sample.atMillis < toMillis || includeEnd)
                if (coveredHere || observedHere) {
                    maximum = maximum(maximum, sample.bpm)
                }
            }
        }
        return Integral(weighted, covered, maximum)
    }

    private fun maximum(
        first: Double?,
        second: Double?,
    ): Double? =
        if (first == null) {
            second
        } else if (second == null) {
            first
        } else {
            maxOf(first, second)
        }

    private fun mergeAdjacentWindows(values: List<RecordingHeartRateWindow>): List<RecordingHeartRateWindow> =
        values.fold(mutableListOf()) { result, window ->
            val previous = result.lastOrNull()
            if (previous?.endMillis == window.startMillis && previous.source == window.source) {
                result[result.lastIndex] = previous.copy(endMillis = window.endMillis)
            } else {
                result.add(window)
            }
            result
        }

    private data class Integral(
        val weightedBpmMillis: Double,
        val coveredMillis: Long,
        val maxBpm: Double?,
    )

    private companion object {
        const val MAX_HOLD_MILLIS = 15_000L

        // shortcut: older late samples/boundaries cannot revise committed history; increase for longer device batching.
        const val REORDER_BUFFER_MILLIS = 10 * 60_000L
    }
}
