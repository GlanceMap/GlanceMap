package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingFixedLagTrajectorySmoothingTest {
    @Test
    fun noisyStraightTrackGetsCleaner() {
        val raw = noisyStraightTrack()

        val adaptive = replay(raw, mode = ADAPTIVE)

        assertTrue(adaptive.diagnostics.evaluatedPointCount > 0)
        assertTrue(adaptive.diagnostics.adjustedPointCount > 0)
        assertTrue(lateralError(adaptive.points) < lateralError(raw) * 0.75)
    }

    @Test
    fun sustainedSideDriftIsReduced() {
        val raw = sustainedSideDriftTrack()

        val adaptive = replay(raw, mode = ADAPTIVE)

        assertTrue(
            "adaptive=${lateralError(adaptive.points)}, raw=${lateralError(raw)}",
            lateralError(adaptive.points) < lateralError(raw),
        )
        assertTrue(adaptive.diagnostics.maximumAdjustmentMeters > 0.0)
    }

    @Test
    fun alternatingZigZagDoesNotCreateRepeatedHardTurnBarriers() {
        val raw =
            listOf(0.0, 7.0, -7.0, 7.0, -7.0, 7.0, -7.0, 7.0, -7.0, 0.0)
                .mapIndexed { index, y -> point(x = index * 10.0, y = y, timeMillis = index * 3_000L) }

        val adaptive = replay(raw, mode = ADAPTIVE)

        assertEquals(0, adaptive.diagnostics.barrierCount)
        assertTrue(lateralError(adaptive.points) < lateralError(raw) * 0.80)
    }

    @Test
    fun rightAngleCornerIsProtected() {
        val raw = rightAngleCorner()

        val adaptive = replay(raw, mode = ADAPTIVE)

        assertTrue(adaptive.diagnostics.barrierCount >= 1)
        assertTrue(adaptive.diagnostics.turnProtectedPointCount >= 1)
        assertTrue(haversineMeters(adaptive.points[3].latLong, raw[3].latLong) < 0.5)
        assertTrue(recordingCanonicalPathDistance(adaptive.points) > 65.0)
    }

    @Test
    fun alpineSwitchbacksAreNotShortcut() {
        val raw = alpineSwitchbacks()

        val adaptive = replay(raw, mode = ADAPTIVE)

        assertTrue(adaptive.diagnostics.barrierCount >= 1)
        assertTrue(haversineMeters(adaptive.points[2].latLong, raw[2].latLong) < 0.5)
        assertTrue(haversineMeters(adaptive.points[6].latLong, raw[6].latLong) < 0.5)
        assertTrue(recordingCanonicalPathDistance(adaptive.points) >= recordingCanonicalPathDistance(raw) * 0.96)
    }

    @Test
    fun triangularDetourThatRejoinsIsCorrected() {
        val raw =
            listOf(
                point(-20.0, 0.0, 0L),
                point(-10.0, 0.0, 3_000L),
                point(0.0, 0.0, 6_000L),
                point(10.0, 10.0, 9_000L),
                point(20.0, 0.0, 12_000L),
                point(30.0, 0.0, 15_000L),
                point(40.0, 0.0, 18_000L),
            )

        val adaptive = replay(raw, mode = ADAPTIVE)

        assertTrue(haversineMeters(adaptive.points[3].latLong, latLongFromMeters(10.0, 0.0)) < 5.0)
        assertTrue(recordingCanonicalPathDistance(adaptive.points) < recordingCanonicalPathDistance(raw))
    }

    @Test
    fun realSwitchbackIsNotClassifiedAsDetour() {
        val raw =
            listOf(
                point(0.0, 0.0, 0L), point(10.0, 0.0, 3_000L), point(20.0, 0.0, 6_000L),
                point(20.0, 10.0, 9_000L), point(20.0, 20.0, 12_000L), point(10.0, 20.0, 15_000L),
                point(0.0, 20.0, 18_000L), point(0.0, 30.0, 21_000L), point(0.0, 40.0, 24_000L),
            )

        val adaptive = replay(raw, mode = ADAPTIVE)

        assertTrue(adaptive.diagnostics.barrierCount >= 1)
        assertTrue(haversineMeters(adaptive.points[2].latLong, raw[2].latLong) < 0.5)
        assertTrue(haversineMeters(adaptive.points[4].latLong, raw[4].latLong) < 0.5)
        assertTrue(recordingCanonicalPathDistance(adaptive.points) >= recordingCanonicalPathDistance(raw) * 0.97)
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

        val adaptive = replay(raw, mode = ADAPTIVE, sampleIntervalSeconds = 5)

        assertEquals(raw.size, adaptive.points.size)
        assertTrue(adaptive.diagnostics.adjustedPointCount > 0)
    }

    @Test
    fun longGapFlushesAndResetsTheEstimator() {
        val options = options(mode = ADAPTIVE, sampleIntervalSeconds = 3)
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
        val options = options(mode = ADAPTIVE, sampleIntervalSeconds = 3)
        var canonical = emptyList<RecordedTracePoint>()
        noisyStraightTrack().take(6).forEach { point ->
            canonical = appendCanonicalRecordingPoint(canonical, point, options).points
        }

        val flushed = flushCanonicalRecordingTail(canonical, options)

        assertEquals(canonical.size, flushed.points.size)
        assertTrue(flushed.trajectoryDiagnostics.evaluatedPointCount > 0)
        assertTrue(flushed.adjustedPointCount > 0)
        assertTrue(flushed.points.all { point -> point.trajectoryFinalized })
    }

    @Test
    fun adaptiveIsCleanerThanOff() {
        val raw = noisyStraightTrack()

        val off = replay(raw, mode = OFF)
        val adaptive = replay(raw, mode = ADAPTIVE)

        assertTrue(lateralError(adaptive.points) < lateralError(off.points))
        assertTrue(off.points.zip(raw).all { (saved, original) -> haversineMeters(saved.latLong, original.latLong) < 0.1 })
    }

    @Test
    fun strongIsCleanerThanAdaptive() {
        val raw = sustainedSideDriftTrack()

        val adaptive = replay(raw, mode = ADAPTIVE)
        val strong = replay(raw, mode = STRONG)

        assertTrue(
            "strong=${lateralError(strong.points)}, adaptive=${lateralError(adaptive.points)}",
            lateralError(strong.points) < lateralError(adaptive.points),
        )
    }

    @Test
    fun strongStillPreservesProtectedCorners() {
        val raw = rightAngleCorner()

        val strong = replay(raw, mode = STRONG)

        assertTrue(strong.diagnostics.barrierCount >= 1)
        assertTrue(haversineMeters(strong.points[3].latLong, raw[3].latLong) < 0.5)
        assertTrue(recordingCanonicalPathDistance(strong.points) > 65.0)
    }

    private fun replay(
        raw: List<RecordedTracePoint>,
        activityProfile: String = HIKE,
        mode: String,
        sampleIntervalSeconds: Int = 3,
    ): ReplayResult {
        val options = options(mode = mode, activityProfile = activityProfile, sampleIntervalSeconds = sampleIntervalSeconds)
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
        mode: String,
        activityProfile: String = HIKE,
        sampleIntervalSeconds: Int,
    ) =
        RecordingPointSmoothingOptions(
            mode = mode,
            activityProfile = activityProfile,
            sampleIntervalSeconds = sampleIntervalSeconds,
        )

    private fun noisyStraightTrack(): List<RecordedTracePoint> =
        listOf(0.0, 3.5, -3.0, 4.0, -3.5, 3.0, -4.0, 3.5, -3.0, 3.0, -2.5, 2.0, 0.0)
            .mapIndexed { index, y -> point(x = index * 10.0, y = y, timeMillis = index * 3_000L) }

    private fun sustainedSideDriftTrack(): List<RecordedTracePoint> =
        listOf(0.0, 0.5, 2.0, 4.5, 7.0, 8.5, 9.0, 8.5, 7.0, 4.5, 2.0, 0.5, 0.0)
            .mapIndexed { index, y ->
                point(
                    x = index * 10.0,
                    y = y,
                    timeMillis = index * 3_000L,
                    accuracyMeters = if (index in 3..9) 28f else 5f,
                )
            }

    private fun rightAngleCorner(): List<RecordedTracePoint> =
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

    private fun alpineSwitchbacks(): List<RecordedTracePoint> =
        listOf(
            point(0.0, 0.0, 0L), point(10.0, 0.0, 3_000L), point(20.0, 0.0, 6_000L),
            point(20.0, 10.0, 9_000L), point(20.0, 20.0, 12_000L), point(10.0, 20.0, 15_000L),
            point(0.0, 20.0, 18_000L), point(0.0, 30.0, 21_000L), point(0.0, 40.0, 24_000L),
            point(10.0, 40.0, 27_000L), point(20.0, 40.0, 30_000L), point(20.0, 50.0, 33_000L),
            point(20.0, 60.0, 36_000L),
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
        const val ADAPTIVE = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE
        const val OFF = SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF
        const val STRONG = SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG
        val TEST_ORIGIN = LatLong(45.0, 6.0)
    }
}
