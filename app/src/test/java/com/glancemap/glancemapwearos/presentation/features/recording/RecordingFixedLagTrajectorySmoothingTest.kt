package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingFixedLagTrajectorySmoothingTest {
    @Test
    fun straightNoisyTrackBecomesSmoother() {
        val raw =
            listOf(0.0, 3.5, -3.0, 4.0, -3.5, 3.0, -4.0, 3.5, -3.0, 3.0, -2.5, 2.0, 0.0)
                .mapIndexed { index, y -> point(x = index * 10.0, y = y, timeMillis = index * 3_000L) }

        val replay = replay(raw, HIKE)

        assertTrue(replay.diagnostics.evaluatedPointCount > 0)
        assertTrue(replay.diagnostics.adjustedPointCount > 0)
        assertTrue(lateralError(replay.points) < lateralError(raw) * 0.72)
    }

    @Test
    fun sustainedLateralDriftIsReduced() {
        val raw =
            listOf(0.0, 0.5, 2.0, 4.5, 7.0, 8.5, 9.0, 8.5, 7.0, 4.5, 2.0, 0.5, 0.0)
                .mapIndexed { index, y -> point(x = index * 10.0, y = y, timeMillis = index * 3_000L) }

        val replay = replay(raw, HIKE)

        assertTrue(
            "raw=${lateralError(raw)} smoothed=${lateralError(replay.points)} diagnostics=${replay.diagnostics}",
            lateralError(replay.points) < lateralError(raw),
        )
        assertTrue(replay.diagnostics.maximumAdjustmentMeters > 0.0)
    }

    @Test
    fun rightAngleCornerIsProtected() {
        val raw =
            listOf(
                point(0.0, 0.0, 0L),
                point(10.0, 0.0, 3_000L),
                point(20.0, 0.0, 6_000L),
                point(30.0, 0.0, 9_000L),
                point(30.0, 10.0, 12_000L),
                point(30.0, 20.0, 15_000L),
                point(30.0, 30.0, 18_000L),
                point(30.0, 40.0, 21_000L),
            )

        val replay = replay(raw, HIKE)

        assertTrue(replay.diagnostics.barrierCount >= 1)
        assertTrue(replay.diagnostics.turnProtectedPointCount >= 1)
        assertTrue(haversineMeters(replay.points[3].latLong, raw[3].latLong) < 0.5)
        assertTrue(recordingCanonicalPathDistance(replay.points) > 65.0)
    }

    @Test
    fun repeatedSwitchbacksAreNotShortcut() {
        val raw =
            listOf(
                point(0.0, 0.0, 0L), point(10.0, 0.0, 3_000L), point(20.0, 0.0, 6_000L),
                point(20.0, 10.0, 9_000L), point(20.0, 20.0, 12_000L), point(10.0, 20.0, 15_000L),
                point(0.0, 20.0, 18_000L), point(0.0, 30.0, 21_000L), point(0.0, 40.0, 24_000L),
                point(10.0, 40.0, 27_000L), point(20.0, 40.0, 30_000L), point(20.0, 50.0, 33_000L),
                point(20.0, 60.0, 36_000L),
            )

        val replay = replay(raw, HIKE)

        assertTrue(replay.diagnostics.barrierCount >= 3)
        assertTrue(haversineMeters(replay.points[2].latLong, raw[2].latLong) < 0.5)
        assertTrue(haversineMeters(replay.points[6].latLong, raw[6].latLong) < 0.5)
        assertTrue(recordingCanonicalPathDistance(replay.points) >= recordingCanonicalPathDistance(raw) * 0.96)
    }

    @Test
    fun isolatedSpikeIsReducedWhenTailFinalizes() {
        val raw =
            listOf(
                point(0.0, 0.0, 0L, accuracyMeters = 5f),
                point(10.0, 18.0, 10_000L, accuracyMeters = 7f),
                point(20.0, 0.0, 20_000L, accuracyMeters = 5f),
                point(30.0, 0.0, 30_000L, accuracyMeters = 5f),
            )

        val replay = replay(raw, HIKE, sampleIntervalSeconds = 10)

        assertTrue(haversineMeters(replay.points[1].latLong, latLongFromMeters(10.0, 0.0)) < 6.0)
    }

    @Test
    fun variableGpsCadenceUsesTimeBoundedTail() {
        val raw =
            listOf(0L, 1_000L, 4_000L, 9_000L, 16_000L, 19_000L, 28_000L, 31_000L)
                .mapIndexed { index, timeMillis ->
                    point(
                        x = index * 10.0,
                        y = if (index % 2 == 0) 3.0 else -3.0,
                        timeMillis = timeMillis,
                    )
                }

        val replay = replay(raw, HIKE, sampleIntervalSeconds = 5)

        assertEquals(raw.size, replay.points.size)
        assertTrue(replay.diagnostics.adjustedPointCount > 0)
    }

    @Test
    fun longGapFlushesAndResetsTheEstimator() {
        val options = options(HIKE, sampleIntervalSeconds = 3)
        var canonical = emptyList<RecordedTracePoint>()
        listOf(
            point(0.0, 0.0, 0L),
            point(10.0, 4.0, 3_000L),
            point(20.0, -4.0, 6_000L),
        ).forEach { point ->
            canonical = appendCanonicalRecordingPoint(canonical, point, options).points
        }

        val afterGap =
            appendCanonicalRecordingPoint(
                existingPoints = canonical,
                point = point(30.0, 0.0, 120_000L),
                options = options,
            )

        assertEquals(1, afterGap.trajectoryDiagnostics.gapResetCount)
        assertEquals(4, afterGap.points.size)
        assertTrue(afterGap.points.dropLast(1).all { point -> point.trajectoryFinalized })
        assertTrue(haversineMeters(afterGap.points.last().latLong, latLongFromMeters(30.0, 0.0)) < 0.1)
    }

    @Test
    fun finalProvisionalTailIsFlushed() {
        val options = options(HIKE, sampleIntervalSeconds = 3)
        var canonical = emptyList<RecordedTracePoint>()
        listOf(0.0, 3.5, -3.5, 4.0, -4.0, 3.0).forEachIndexed { index, y ->
            canonical =
                appendCanonicalRecordingPoint(
                    existingPoints = canonical,
                    point = point(index * 10.0, y, index * 3_000L),
                    options = options,
                ).points
        }

        val flushed = flushCanonicalRecordingTail(canonical, options)

        assertEquals(canonical.size, flushed.points.size)
        assertTrue(flushed.trajectoryDiagnostics.evaluatedPointCount > 0)
        assertTrue(flushed.adjustedPointCount > 0)
        assertTrue(flushed.points.all { point -> point.trajectoryFinalized })
    }

    @Test
    fun hikeAndBikeUseTheSameEstimatorWithProfilePolicies() {
        val raw =
            listOf(0.0, 4.0, -3.0, 4.0, -3.0, 3.0, -2.0, 0.0)
                .mapIndexed { index, y -> point(index * 12.0, y, index * 2_000L) }

        val hike = replay(raw, HIKE)
        val bike = replay(raw, BIKE)

        assertTrue(hike.diagnostics.adjustedPointCount > 0)
        assertTrue(bike.diagnostics.adjustedPointCount > 0)
        assertTrue(lateralError(hike.points) < lateralError(raw))
        assertTrue(lateralError(bike.points) < lateralError(raw))
    }

    private fun replay(
        raw: List<RecordedTracePoint>,
        activityProfile: String,
        sampleIntervalSeconds: Int = 3,
    ): ReplayResult {
        val options = options(activityProfile, sampleIntervalSeconds)
        var points = emptyList<RecordedTracePoint>()
        var diagnostics = RecordingTrajectorySmoothingDiagnostics()
        raw.forEach { point ->
            val append = appendCanonicalRecordingPoint(points, point, options)
            points = append.points
            diagnostics = diagnostics.plus(append.trajectoryDiagnostics)
        }
        val flush = flushCanonicalRecordingTail(points, options)
        return ReplayResult(
            points = flush.points,
            diagnostics = diagnostics.plus(flush.trajectoryDiagnostics),
        )
    }

    private fun options(
        activityProfile: String,
        sampleIntervalSeconds: Int,
    ) =
        RecordingPointSmoothingOptions(
            mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
            activityProfile = activityProfile,
            sampleIntervalSeconds = sampleIntervalSeconds,
        )

    private fun point(
        x: Double,
        y: Double,
        timeMillis: Long,
        accuracyMeters: Float = 14f,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = latLongFromMeters(x, y),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = accuracyMeters,
            speedMps = 1.2f,
        )

    private fun lateralError(points: List<RecordedTracePoint>): Double =
        points.sumOf { point ->
            haversineMeters(point.latLong, latLongFromMeters(x = point.latLong.toLocalMeters(TEST_ORIGIN).x, y = 0.0))
        } / points.size.coerceAtLeast(1)

    private fun latLongFromMeters(
        x: Double,
        y: Double,
    ): LatLong =
        LocalMeters(x, y).toLatLong(TEST_ORIGIN)

    private data class ReplayResult(
        val points: List<RecordedTracePoint>,
        val diagnostics: RecordingTrajectorySmoothingDiagnostics,
    )

    private companion object {
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
        val TEST_ORIGIN = LatLong(45.0, 6.0)
    }
}
