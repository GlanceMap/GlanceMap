package com.glancemap.glancemapwearos.presentation.features.recording.health

import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.RECORDING_SENSOR_MAX_AGE_MS
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.RecordingSensorMetrics
import java.util.TreeMap
import kotlin.math.roundToInt

internal class RecordingHealthPointAssociation(
    private val startedAtMillis: Long,
) {
    private data class Window(
        val start: Long,
        var end: Long = Long.MAX_VALUE,
    )

    private val timeline = RecordingHealthTimeline()
    private val samples = mutableMapOf<RecordingHealthMetric, TreeMap<Long, RecordingHealthSample>>()
    private val live = mutableMapOf<RecordingHealthMetric, RecordingHealthSample>()
    private val windows = mutableMapOf<RecordingHealthMetric, MutableList<Window>>()
    private var owned = emptySet<RecordingHealthMetric>()
    private var paused = false
    private var stepOffset = 0
    private var backendStartedAtMillis = startedAtMillis
    private var affectedStart = Long.MAX_VALUE
    private var affectedEnd = Long.MIN_VALUE
    val knownSteps: Int get() =
        live[RecordingHealthMetric.STEPS_TOTAL]?.value?.coerceIn(0.0, Int.MAX_VALUE.toDouble())?.roundToInt()
            ?: stepOffset

    fun setStepOffset(steps: Int) {
        stepOffset = steps.coerceAtLeast(0)
    }

    fun beginBackend(
        steps: Int,
        atMillis: Long,
    ) {
        live.clear()
        setStepOffset(steps)
        backendStartedAtMillis = atMillis
    }

    fun setOwnership(
        kinds: Set<RecordingHealthMetric>,
        atMillis: Long,
    ) {
        owned = kinds
        updateWindows(atMillis)
    }

    fun setPaused(
        value: Boolean,
        atMillis: Long,
    ) {
        paused = value
        updateWindows(atMillis)
    }

    private fun updateWindows(atMillis: Long) {
        val active = if (paused) emptySet() else owned
        RecordingHealthMetric.entries.forEach { kind ->
            val entries = windows.getOrPut(kind) { mutableListOf() }
            val open = entries.lastOrNull()?.takeIf { it.end == Long.MAX_VALUE }
            if (kind !in active) {
                open?.end = atMillis
            } else if (open == null) {
                val firstBuffered =
                    if (entries.isEmpty() || entries.last().end <= backendStartedAtMillis) {
                        samples[kind]?.ceilingKey(backendStartedAtMillis)
                    } else {
                        null
                    }
                val start = minOf(atMillis, firstBuffered ?: atMillis)
                entries += Window(start)
                samples[kind]?.tailMap(start)?.takeIf { it.isNotEmpty() }?.let { pending ->
                    affectedStart = minOf(affectedStart, pending.firstKey())
                    affectedEnd = maxOf(affectedEnd, pending.lastKey() + RECORDING_SENSOR_MAX_AGE_MS)
                }
            }
        }
    }

    fun accept(
        incoming: List<RecordingHealthSample>,
        arrivalElapsedMillis: Long,
    ) {
        val adjusted =
            incoming.filter { it.measuredWallTimeMillis >= startedAtMillis }.map { sample ->
                if (sample.kind == RecordingHealthMetric.STEPS_TOTAL) {
                    sample.copy(value = sample.value + stepOffset)
                } else {
                    sample
                }
            }
        val delivery = timeline.accept(adjusted, arrivalElapsedMillis)
        delivery.live.forEach { live[it.kind] = it }
        delivery.historical.forEach { sample ->
            if (sample.kind == RecordingHealthMetric.STEP_DELTA) return@forEach
            samples.getOrPut(sample.kind) { TreeMap() }[sample.measuredWallTimeMillis] = sample
            affectedStart = minOf(affectedStart, sample.measuredWallTimeMillis)
            affectedEnd = maxOf(affectedEnd, sample.measuredWallTimeMillis + RECORDING_SENSOR_MAX_AGE_MS)
        }
    }

    fun overlay(
        raw: RecordingSensorMetrics,
        kinds: Set<RecordingHealthMetric> = owned,
        forExport: Boolean = false,
    ): RecordingSensorMetrics {
        fun currentSample(kind: RecordingHealthMetric): RecordingHealthSample? {
            val currentWindow = windows[kind]?.lastOrNull()?.takeIf { it.end == Long.MAX_VALUE }
            return live[kind]?.takeIf {
                forExport || (currentWindow != null && windowAt(kind, it.measuredWallTimeMillis) === currentWindow)
            }
        }

        fun value(kind: RecordingHealthMetric): Int? {
            val sample = if (kind == RecordingHealthMetric.STEPS_TOTAL) live[kind] else currentSample(kind)
            return sample?.value?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() }?.roundToInt()
        }

        fun at(kind: RecordingHealthMetric) = currentSample(kind)?.measuredWallTimeMillis ?: 0L
        var merged = raw
        if (RecordingHealthMetric.HEART_RATE in kinds) {
            merged =
                merged.copy(
                    heartRateBpm = value(RecordingHealthMetric.HEART_RATE),
                    heartRateUpdatedAtMillis = at(RecordingHealthMetric.HEART_RATE),
                    heartRateFromBluetooth = false,
                )
        }
        if (RecordingHealthMetric.STEPS_TOTAL in kinds) {
            merged =
                merged.copy(
                    stepCount = value(RecordingHealthMetric.STEPS_TOTAL),
                    stepCountUpdatedAtMillis = at(RecordingHealthMetric.STEPS_TOTAL),
                    stepCountFromBluetooth = false,
                )
        }
        if (RecordingHealthMetric.CADENCE in kinds) {
            merged =
                merged.copy(
                    cadenceSpm = value(RecordingHealthMetric.CADENCE),
                    cadenceUpdatedAtMillis = at(RecordingHealthMetric.CADENCE),
                    cadenceFromBluetooth = false,
                )
        }
        return merged
    }

    fun backfill(points: List<RecordedTracePoint>): List<RecordedTracePoint> {
        var updated: MutableList<RecordedTracePoint>? = null
        points.forEachIndexed { index, point ->
            if (point.timeMillis !in affectedStart..affectedEnd) return@forEachIndexed

            fun value(kind: RecordingHealthMetric): Int? {
                val window = windowAt(kind, point.timeMillis)
                return samples[kind]
                    ?.floorEntry(point.timeMillis)
                    ?.value
                    ?.takeIf {
                        point.timeMillis - it.measuredWallTimeMillis <= RECORDING_SENSOR_MAX_AGE_MS &&
                            window != null &&
                            window === windowAt(kind, it.measuredWallTimeMillis)
                    }?.value
                    ?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() }
                    ?.roundToInt()
            }
            val next =
                point.copy(
                    heartRateBpm = point.heartRateBpm ?: value(RecordingHealthMetric.HEART_RATE),
                    stepCount = point.stepCount ?: value(RecordingHealthMetric.STEPS_TOTAL),
                    cadenceSpm = point.cadenceSpm ?: value(RecordingHealthMetric.CADENCE),
                )
            if (next != point) {
                if (updated == null) updated = points.toMutableList()
                updated[index] = next
            }
        }
        affectedStart = Long.MAX_VALUE
        affectedEnd = Long.MIN_VALUE
        val pruneBefore =
            samples.values
                .mapNotNull { it.lastKeyOrNull() }
                .maxOrNull()
                ?.minus(HISTORY_RETENTION_MS)
        if (pruneBefore != null) samples.values.forEach { it.headMap(pruneBefore).clear() }
        return updated ?: points
    }

    private fun windowAt(
        kind: RecordingHealthMetric,
        atMillis: Long,
    ): Window? = windows[kind]?.lastOrNull { atMillis >= it.start && atMillis < it.end }

    private fun TreeMap<Long, RecordingHealthSample>.lastKeyOrNull(): Long? = if (isEmpty()) null else lastKey()
}

private const val HISTORY_RETENTION_MS = 10L * 60L * 1_000L
