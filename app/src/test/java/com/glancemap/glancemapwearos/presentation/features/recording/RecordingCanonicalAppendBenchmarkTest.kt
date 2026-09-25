package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import java.io.File
import java.util.Locale
import kotlin.math.cos

/**
 * Manual characterization benchmark for the existing canonical recording append path.
 *
 * This intentionally exercises production code without changing its implementation. The test
 * writes a tab-separated report under build/ so benchmark output is available after Gradle
 * suppresses test stdout.
 */
class RecordingCanonicalAppendBenchmarkTest {
    @Test
    fun characterizeCanonicalAppendScaling() {
        val report = StringBuilder()
        report.appendLine("shape\tmode\tpoints\trepetitions\tappendMedianMs")
        report.appendLine(
            "\t\t\t\tflushMedianMs\ttotalMedianMs\tfinalCount\tchecksum\tpathMeters\t" +
                "first10MedianMs\tmiddle10MedianMs\tfinal10MedianMs\tfirstPerPointUs\t" +
                "middlePerPointUs\tfinalPerPointUs",
        )

        TRACE_SHAPES.forEach { shape ->
            MODES.forEach { mode ->
                POINT_COUNTS.forEach { pointCount ->
                    val result = benchmark(shape, mode, pointCount)
                    report.appendLine(result.toReportLine())
                }
            }
        }

        val output = File("build/recording-canonical-append-benchmark.tsv")
        output.parentFile?.mkdirs()
        output.writeText(report.toString())
        println(report.toString())
        assertTrue("benchmark report was not written", output.isFile)
    }

    private fun benchmark(
        shape: TraceShape,
        mode: String,
        pointCount: Int,
    ): BenchmarkResult {
        val points = buildTrace(shape, pointCount)
        val options =
            RecordingPointSmoothingOptions(
                mode = mode,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                sampleIntervalSeconds = 3,
            )
        val repetitions = MEASURED_REPETITIONS

        repeat(WARMUP_REPETITIONS) {
            replay(points, options, captureTiming = false)
        }

        val runs =
            (0 until repetitions).map {
                replay(points, options, captureTiming = true)
            }
        val first = runs.first()
        runs.drop(1).forEach { run ->
            assertEquals("non-deterministic final count", first.finalCount, run.finalCount)
            assertEquals("non-deterministic result", first.checksum, run.checksum)
            assertEquals("non-deterministic input count", pointCount, run.finalCount)
        }
        assertEquals(pointCount, first.finalCount)
        assertEquals(pointCount, first.timestamps.size)
        assertTrue(first.timestamps.zipWithNext().all { (before, after) -> after > before })
        assertTrue(first.geometryFinite)
        assertEquals(1, first.segmentCount)

        return BenchmarkResult(
            shape = shape.label,
            mode = mode,
            pointCount = pointCount,
            repetitions = repetitions,
            appendMedianMs = median(runs.map { it.appendNanos }) / NANOS_PER_MILLISECOND,
            flushMedianMs = median(runs.map { it.flushNanos }) / NANOS_PER_MILLISECOND,
            totalMedianMs = median(runs.map { it.totalNanos }) / NANOS_PER_MILLISECOND,
            finalCount = first.finalCount,
            checksum = first.checksum,
            pathMeters = first.pathMeters,
            firstPhaseMedianMs = median(runs.map { it.firstPhaseNanos }) / NANOS_PER_MILLISECOND,
            middlePhaseMedianMs =
                median(runs.map { it.middlePhaseNanos }) / NANOS_PER_MILLISECOND,
            finalPhaseMedianMs = median(runs.map { it.finalPhaseNanos }) / NANOS_PER_MILLISECOND,
        )
    }

    private fun replay(
        points: List<RecordedTracePoint>,
        options: RecordingPointSmoothingOptions,
        captureTiming: Boolean,
    ): ReplayResult {
        var canonical = emptyList<RecordedTracePoint>()
        val pointTimings = LongArray(points.size)
        val totalStart = System.nanoTime()

        points.forEachIndexed { index, point ->
            val appendStart = System.nanoTime()
            canonical = appendCanonicalRecordingPoint(canonical, point, options).points
            pointTimings[index] = if (captureTiming) System.nanoTime() - appendStart else 0L
        }
        val appendNanos = if (captureTiming) pointTimings.sum() else 0L
        val flushStart = System.nanoTime()
        val flushed = flushCanonicalRecordingTail(canonical, options)
        val flushNanos = if (captureTiming) System.nanoTime() - flushStart else 0L
        val totalNanos = if (captureTiming) System.nanoTime() - totalStart else 0L
        val finalPoints = flushed.points
        val phases = phaseNanos(pointTimings)

        return ReplayResult(
            appendNanos = appendNanos,
            flushNanos = flushNanos,
            totalNanos = totalNanos,
            finalCount = finalPoints.size,
            checksum = checksum(finalPoints),
            pathMeters = recordingCanonicalPathDistance(finalPoints),
            timestamps = finalPoints.map { it.timeMillis },
            geometryFinite =
                finalPoints.all { point ->
                    point.latLong.latitude.isFinite() && point.latLong.longitude.isFinite()
                },
            segmentCount = recordedTraceSegments(finalPoints).size,
            firstPhaseNanos = phases.first,
            middlePhaseNanos = phases.second,
            finalPhaseNanos = phases.third,
        )
    }

    private fun phaseNanos(pointTimings: LongArray): Triple<Long, Long, Long> {
        val phaseSize = (pointTimings.size / 10).coerceAtLeast(1)
        val firstEnd = phaseSize
        val middleStart = pointTimings.size * 45 / 100
        val middleEnd = (middleStart + phaseSize).coerceAtMost(pointTimings.size)
        val finalStart = (pointTimings.size - phaseSize).coerceAtLeast(0)
        return Triple(
            pointTimings.slice(0 until firstEnd).sum(),
            pointTimings.slice(middleStart until middleEnd).sum(),
            pointTimings.slice(finalStart until pointTimings.size).sum(),
        )
    }

    private fun buildTrace(
        shape: TraceShape,
        pointCount: Int,
    ): List<RecordedTracePoint> =
        (0 until pointCount).map { index ->
            val progress = index * STEP_METERS
            val (x, y) =
                when (shape) {
                    TraceShape.STRAIGHT ->
                        progress to STRAIGHT_NOISE[index % STRAIGHT_NOISE.size]
                    TraceShape.TURN_HEAVY -> {
                        val segment = (index / TURN_LENGTH) % 4
                        val offset = index % TURN_LENGTH
                        val coordinate = offset * STEP_METERS
                        when (segment) {
                            0 -> coordinate to TURN_NOISE[index % TURN_NOISE.size]
                            1 ->
                                (TURN_LENGTH - 1) * STEP_METERS to
                                    (coordinate + TURN_NOISE[index % TURN_NOISE.size])
                            2 ->
                                ((TURN_LENGTH - 1 - offset) * STEP_METERS) to
                                    ((TURN_LENGTH - 1) * STEP_METERS)
                            else ->
                                0.0 to
                                    ((TURN_LENGTH - 1 - offset) * STEP_METERS)
                        }
                    }
                }
            RecordedTracePoint(
                latLong = localMetersToLatLong(x, y),
                elevationMeters = null,
                timeMillis = index * 3_000L,
                accuracyMeters = 8.0f + (index % 3),
                speedMps = 2.6f,
            )
        }

    private fun localMetersToLatLong(
        x: Double,
        y: Double,
    ): LatLong {
        val originLatitude = 45.0
        val originLongitude = 6.0
        val latitude = originLatitude + y / METERS_PER_DEGREE
        val longitude = originLongitude + x / (METERS_PER_DEGREE * cos(Math.toRadians(originLatitude)))
        return LatLong(latitude, longitude)
    }

    private fun checksum(points: List<RecordedTracePoint>): String {
        var hash = FNV_OFFSET_BASIS
        points.forEach { point ->
            hash = mix(hash, java.lang.Double.doubleToLongBits(point.latLong.latitude))
            hash = mix(hash, java.lang.Double.doubleToLongBits(point.latLong.longitude))
            hash = mix(hash, point.timeMillis)
            hash = mix(hash, if (point.startsNewSegment) 1L else 0L)
            hash = mix(hash, if (point.trajectoryFinalized) 1L else 0L)
        }
        return hash.toULong().toString(16)
    }

    private fun mix(
        hash: Long,
        value: Long,
    ): Long {
        var mixed = hash xor value
        mixed *= FNV_PRIME
        mixed = mixed xor (mixed ushr 32)
        return mixed
    }

    private fun median(
        values: List<Long>,
    ): Double = values.sorted()[values.size / 2].toDouble()

    private data class ReplayResult(
        val appendNanos: Long,
        val flushNanos: Long,
        val totalNanos: Long,
        val finalCount: Int,
        val checksum: String,
        val pathMeters: Double,
        val timestamps: List<Long>,
        val geometryFinite: Boolean,
        val segmentCount: Int,
        val firstPhaseNanos: Long,
        val middlePhaseNanos: Long,
        val finalPhaseNanos: Long,
    )

    private data class BenchmarkResult(
        val shape: String,
        val mode: String,
        val pointCount: Int,
        val repetitions: Int,
        val appendMedianMs: Double,
        val flushMedianMs: Double,
        val totalMedianMs: Double,
        val finalCount: Int,
        val checksum: String,
        val pathMeters: Double,
        val firstPhaseMedianMs: Double,
        val middlePhaseMedianMs: Double,
        val finalPhaseMedianMs: Double,
    ) {
        fun toReportLine(): String =
            listOf(
                shape,
                mode,
                pointCount,
                repetitions,
                appendMedianMs,
                flushMedianMs,
                totalMedianMs,
                finalCount,
                checksum,
                pathMeters,
                firstPhaseMedianMs,
                middlePhaseMedianMs,
                finalPhaseMedianMs,
                firstPhaseMedianMs * 1_000.0 / (pointCount / 10).coerceAtLeast(1),
                middlePhaseMedianMs * 1_000.0 / (pointCount / 10).coerceAtLeast(1),
                finalPhaseMedianMs * 1_000.0 / (pointCount / 10).coerceAtLeast(1),
            ).joinToString(separator = "\t") { value ->
                when (value) {
                    is Double -> String.format(Locale.US, "%.3f", value)
                    else -> value.toString()
                }
            }
    }

    private enum class TraceShape(
        val label: String,
    ) {
        STRAIGHT("straight"),
        TURN_HEAVY("turn-heavy"),
    }

    private companion object {
        const val WARMUP_REPETITIONS = 1
        const val MEASURED_REPETITIONS = 3
        const val STEP_METERS = 6.0
        const val TURN_LENGTH = 80
        const val METERS_PER_DEGREE = 111_320.0
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1_099_511_628_211L
        val POINT_COUNTS = listOf(100, 1_000, 10_000)
        val MODES =
            listOf(
                SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
            )
        val TRACE_SHAPES = TraceShape.entries.toList()
        val STRAIGHT_NOISE = listOf(0.0, 1.4, -1.1, 1.8, -0.8, 1.0, -1.5, 0.5)
        val TURN_NOISE = listOf(0.0, 0.6, -0.4, 0.5, -0.3, 0.2)
    }
}
