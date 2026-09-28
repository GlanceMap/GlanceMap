package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingTrackSmoothingEquivalenceTest {
    @Test
    fun boundedImplementationMatchesPreRefactorSignatures() {
        FIXTURES.forEach { fixture ->
            assertEquals(fixture.name, BASELINES.getValue(fixture.name), replay(fixture))
        }
    }

    @Test
    fun backwardTailDiscoveryUsesOnlyTheProvisionalSuffix() {
        val points =
            tailPoints().mapIndexed { index, point ->
                point.copy(trajectoryFinalized = index < 970)
            }
        val adaptiveOptions =
            RecordingPointSmoothingOptions(
                mode = ADAPTIVE,
                activityProfile = HIKE,
                sampleIntervalSeconds = 3,
            )
        val strongOptions =
            RecordingPointSmoothingOptions(
                mode = STRONG,
                activityProfile = HIKE,
                sampleIntervalSeconds = 3,
            )

        assertEquals(993, recordingTrajectoryTailStartIndex(points, adaptiveOptions))
        assertEquals(990, recordingTrajectoryTailStartIndex(points, strongOptions))
        assertEquals(
            points.size,
            recordingTrajectoryTailStartIndex(
                points.map { it.copy(trajectoryFinalized = true) },
                adaptiveOptions,
            ),
        )
    }

    @Test
    fun boundedBarrierSetMatchesTheRelevantFullHistoryBarriers() {
        FIXTURES.forEach { fixture ->
            val points = fixturePoints(fixture.shape, fixture.pointCount)
            val options =
                RecordingPointSmoothingOptions(
                    mode = fixture.mode,
                    activityProfile = fixture.activityProfile,
                    sampleIntervalSeconds = 3,
                )
            val firstFinalizeIndex = points.size - 22
            val endFinalizeExclusive = points.size - 8
            val relevantRange =
                recordingTrajectoryRelevantBarrierCenterRange(
                    firstFinalizeIndex = firstFinalizeIndex,
                    endFinalizeExclusive = endFinalizeExclusive,
                    options = options,
                )

            val fullHistory = recordingTrajectoryTurnBarriers(points, options)
            val bounded = recordingTrajectoryTurnBarriers(points, options, relevantRange)

            assertEquals(fullHistory.filterTo(mutableSetOf()) { it in relevantRange }, bounded)
        }
    }

    private fun replay(fixture: Fixture): ReplaySignature {
        val options =
            RecordingPointSmoothingOptions(
                mode = fixture.mode,
                activityProfile = fixture.activityProfile,
                sampleIntervalSeconds = 3,
            )
        var points = emptyList<RecordedTracePoint>()
        var operationHash = FNV_OFFSET_BASIS
        fixturePoints(fixture.shape, fixture.pointCount).forEach { point ->
            val append = appendCanonicalRecordingPoint(points, point, options)
            operationHash = mixAppend(operationHash, append)
            points = append.points
        }
        val flushed = flushCanonicalRecordingTail(points, options)
        operationHash = mixAppend(operationHash, flushed)
        val gpx = encodeRecordedTraceAsGpx(title = "Equivalence", points = flushed.points)
        return ReplaySignature(
            pointsHash = hashPoints(flushed.points),
            operationHash = operationHash.toULong().toString(16),
            gpxHash = hashBytes(gpx),
        )
    }

    private fun mixAppend(
        initialHash: Long,
        result: RecordingCanonicalAppendResult,
    ): Long {
        var hash = initialHash
        hash = mix(hash, quantizeMeters(result.distanceDeltaMeters))
        hash = mix(hash, result.adjustedPointCount.toLong())
        hash = mix(hash, quantizeMeters(result.adjustmentMeters))
        hash = mix(hash, quantizeMeters(result.maximumAdjustmentMeters))
        hash = mix(hash, if (result.confirmedReversalCorrected) 1L else 0L)
        hash = mix(hash, result.straightDriftCorrectedPointCount.toLong())
        with(result.trajectoryDiagnostics) {
            hash = mix(hash, evaluatedPointCount.toLong())
            hash = mix(hash, adjustedPointCount.toLong())
            hash = mix(hash, quantizeMeters(totalAdjustmentMeters))
            hash = mix(hash, quantizeMeters(maximumAdjustmentMeters))
            hash = mix(hash, turnProtectedPointCount.toLong())
            hash = mix(hash, barrierCount.toLong())
            hash = mix(hash, gapResetCount.toLong())
        }
        return hash
    }

    private fun hashPoints(points: List<RecordedTracePoint>): String {
        var hash = FNV_OFFSET_BASIS
        points.forEach { point ->
            hash = mix(hash, quantizeCoordinates(point.latLong.latitude))
            hash = mix(hash, quantizeCoordinates(point.latLong.longitude))
            hash = mix(hash, point.timeMillis)
            hash = mix(hash, if (point.startsNewSegment) 1L else 0L)
            hash = mixString(hash, point.segmentStartReason)
            hash = mix(hash, if (point.trajectoryFinalized) 1L else 0L)
        }
        return hash.toULong().toString(16)
    }

    private fun hashBytes(bytes: ByteArray): String {
        var hash = FNV_OFFSET_BASIS
        bytes.forEach { value -> hash = mix(hash, value.toLong()) }
        return hash.toULong().toString(16)
    }

    private fun quantizeMeters(value: Double): Long = (value * METERS_HASH_SCALE).toLong()

    private fun quantizeCoordinates(value: Double): Long = (value * COORDINATE_HASH_SCALE).toLong()

    private fun mixString(
        initialHash: Long,
        value: String?,
    ): Long {
        var hash = mix(initialHash, value?.length?.toLong() ?: -1L)
        value?.forEach { character -> hash = mix(hash, character.code.toLong()) }
        return hash
    }

    private fun mix(
        hash: Long,
        value: Long,
    ): Long {
        var mixed = hash xor value
        mixed *= FNV_PRIME
        return mixed xor (mixed ushr 32)
    }

    private fun fixturePoints(
        shape: Shape,
        pointCount: Int,
    ): List<RecordedTracePoint> =
        (0 until pointCount).map { index ->
            val progress = index * STEP_METERS
            val (x, y) =
                when (shape) {
                    Shape.STRAIGHT -> progress to STRAIGHT_NOISE[index % STRAIGHT_NOISE.size]
                    Shape.TURN_HEAVY -> turnHeavyCoordinate(index)
                }
            RecordedTracePoint(
                latLong = LocalMeters(x, y).toLatLong(TEST_ORIGIN),
                elevationMeters = null,
                timeMillis = index * 3_000L,
                accuracyMeters = 8.0f + (index % 3),
                speedMps = 2.6f,
            )
        }

    private fun tailPoints(): List<RecordedTracePoint> =
        (0 until 1_000).map { index ->
            RecordedTracePoint(
                latLong = LocalMeters(index * 10.0, 0.0).toLatLong(TEST_ORIGIN),
                elevationMeters = null,
                timeMillis = index * 3_000L,
                accuracyMeters = 8f,
                speedMps = 2f,
            )
        }

    private fun turnHeavyCoordinate(index: Int): Pair<Double, Double> {
        val segment = (index / TURN_LENGTH) % 4
        val offset = index % TURN_LENGTH
        val coordinate = offset * STEP_METERS
        return when (segment) {
            0 -> coordinate to TURN_NOISE[index % TURN_NOISE.size]
            1 -> (TURN_LENGTH - 1) * STEP_METERS to (coordinate + TURN_NOISE[index % TURN_NOISE.size])
            2 -> (TURN_LENGTH - 1 - offset) * STEP_METERS to ((TURN_LENGTH - 1) * STEP_METERS)
            else -> 0.0 to ((TURN_LENGTH - 1 - offset) * STEP_METERS)
        }
    }

    private data class Fixture(
        val name: String,
        val shape: Shape,
        val pointCount: Int,
        val activityProfile: String,
        val mode: String,
    )

    private data class ReplaySignature(
        val pointsHash: String,
        val operationHash: String,
        val gpxHash: String,
    )

    private enum class Shape {
        STRAIGHT,
        TURN_HEAVY,
    }

    private companion object {
        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1_099_511_628_211L
        const val STEP_METERS = 6.0
        const val TURN_LENGTH = 80
        const val METERS_HASH_SCALE = 1_000_000.0
        const val COORDINATE_HASH_SCALE = 10_000_000_000.0
        val TEST_ORIGIN = LatLong(45.0, 6.0)
        val STRAIGHT_NOISE =
            listOf(0.0, 3.5, -3.0, 4.0, -3.5, 3.0, -4.0, 3.5, -3.0, 3.0, -2.5, 2.0)
        val TURN_NOISE = listOf(0.0, 1.5, -1.0, 2.0, -1.5, 1.0)
        val FIXTURES =
            listOf(
                Fixture("hike_adaptive_straight_1000", Shape.STRAIGHT, 1_000, HIKE, ADAPTIVE),
                Fixture("hike_strong_turn_heavy_1000", Shape.TURN_HEAVY, 1_000, HIKE, STRONG),
                Fixture("bike_adaptive_straight_10000", Shape.STRAIGHT, 10_000, BIKE, ADAPTIVE),
                Fixture("bike_strong_turn_heavy_10000", Shape.TURN_HEAVY, 10_000, BIKE, STRONG),
            )
        val BASELINES =
            mapOf(
                "hike_adaptive_straight_1000" to
                    ReplaySignature("223685eb8e7c93a5", "830cca25f10576d6", "9c79ee8da490c872"),
                "hike_strong_turn_heavy_1000" to
                    ReplaySignature("d9adaef54fd66e81", "29563e763bb591ca", "9a80034f25c81e0d"),
                "bike_adaptive_straight_10000" to
                    ReplaySignature("a895451a9a7aed03", "ab7bd226284784be", "d8b9efcde911d702"),
                "bike_strong_turn_heavy_10000" to
                    ReplaySignature("f9662edb2dc5ce3a", "5159039011e6244a", "d7925817169ccbf"),
            )
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
        const val ADAPTIVE = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE
        const val STRONG = SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG
    }
}
