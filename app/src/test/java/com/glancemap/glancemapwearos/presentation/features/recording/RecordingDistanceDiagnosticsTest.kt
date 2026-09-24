package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import kotlin.math.cos

class RecordingDistanceDiagnosticsTest {
    @Test
    fun identicalSimpleGeometryProducesNearZeroDifference() {
        val points = listOf(point(0.0), point(10.0), point(20.0))
        val diagnostics = RecordingDistanceDiagnostics()
        val activityDistance = recordingCanonicalPathDistance(points)

        val comparison =
            buildRecordingDistanceComparison(
                activityDistanceMeters = activityDistance,
                diagnostics = diagnostics,
                canonicalPoints = points,
            )

        assertEquals(activityDistance, comparison.canonicalGeometryMeters, 0.01)
        assertEquals(0.0, comparison.activityMinusCanonicalMeters, 0.01)
        assertEquals(0.0, comparison.activityVsCanonicalPercent ?: Double.NaN, 0.01)
    }

    @Test
    fun smoothingChangesCanonicalGeometryWithoutChangingActivityGeometry() {
        val raw = noisyTrack()
        val off = finalizeCanonical(raw, SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF)
        val adaptive = finalizeCanonical(raw, SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE)
        val offActivity = watchGpsDistance(raw)
        val adaptiveActivity = watchGpsDistance(raw)

        assertNotEquals(
            recordingCanonicalPathDistance(off),
            recordingCanonicalPathDistance(adaptive),
            0.01,
        )
        assertEquals(offActivity, adaptiveActivity, 0.0001)
    }

    @Test
    fun continuityRecoveryCapIsAttributedSeparately() {
        val geometry = RecordingWatchGpsDistanceGeometry()
        val diagnostics = RecordingDistanceDiagnostics()
        val before = point(0.0, timeMillis = 0L)
        val recovered = point(300.0, timeMillis = 20_000L)
        val after = point(310.0, timeMillis = 23_000L)

        geometry.append(before, false, HIKE, 3)
        geometry.append(recovered, true, HIKE, 3)
        geometry.append(after, false, HIKE, 3)?.let { segment ->
            diagnostics.record(segment, estimate(segment))
        }
        geometry.flush()?.let { segment ->
            diagnostics.record(segment, estimate(segment))
        }

        assertTrue(diagnostics.gpsGapRecoverySegmentCount >= 1)
        assertTrue(diagnostics.continuityCapCount >= 1)
        assertTrue(diagnostics.continuityCappedMeters > 0.0)
        assertTrue(diagnostics.watchGpsRawGeometryMeters > diagnostics.continuityCappedMeters)
    }

    @Test
    fun canonicalGeometryDoesNotConnectAcrossSegmentBoundary() {
        val points =
            listOf(
                point(0.0),
                point(10.0),
                point(100.0).copy(startsNewSegment = true),
                point(110.0),
            )

        assertEquals(20.0, recordingCanonicalPathDistance(points), 0.5)
    }

    @Test
    fun finalTailFlushIsIncludedInCanonicalComparison() {
        val options = smoothingOptions(SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE)
        var canonical = emptyList<RecordedTracePoint>()
        noisyTrack().forEach { rawPoint ->
            canonical = appendCanonicalRecordingPoint(canonical, rawPoint, options).points
        }
        val beforeFlush = recordingCanonicalPathDistance(canonical)
        val flushed = flushCanonicalRecordingTail(canonical, options)
        val comparison =
            buildRecordingDistanceComparison(
                activityDistanceMeters = beforeFlush,
                diagnostics = RecordingDistanceDiagnostics(),
                canonicalPoints = flushed.points,
            )

        assertTrue(flushed.points.all { it.trajectoryFinalized })
        assertEquals(recordingCanonicalPathDistance(flushed.points), comparison.canonicalGeometryMeters, 0.0)
        assertTrue(comparison.canonicalGeometryMeters > 0.0)
    }

    @Test
    fun zeroCanonicalDistanceLeavesPercentageUnavailable() {
        val comparison =
            buildRecordingDistanceComparison(
                activityDistanceMeters = 4.0,
                diagnostics = RecordingDistanceDiagnostics(),
                canonicalPoints = listOf(point(0.0), point(0.0)),
            )

        assertEquals(0.0, comparison.canonicalGeometryMeters, 0.0)
        assertNull(comparison.activityVsCanonicalPercent)
    }

    @Test
    fun instrumentationDoesNotChangeUiDistanceOrGpxCoordinates() {
        val points = listOf(point(0.0), point(10.0))
        val state = TraceRecordingUiState(distanceMeters = 123.0, points = points)
        val comparison =
            buildRecordingDistanceComparison(
                activityDistanceMeters = state.distanceMeters,
                diagnostics = RecordingDistanceDiagnostics(),
                canonicalPoints = state.points,
            )
        val gpx =
            encodeRecordedTraceAsGpx(title = "test", points = state.points)
                .toString(Charsets.UTF_8)

        assertEquals(123.0, state.distanceMeters, 0.0)
        assertEquals(123.0, comparison.activityDistanceMeters, 0.0)
        assertTrue(gpx.contains("lat=\"45.00000000\""))
        assertTrue(gpx.contains("lat=\"45.00008983\""))
    }

    private fun finalizeCanonical(
        points: List<RecordedTracePoint>,
        mode: String,
    ): List<RecordedTracePoint> {
        val options = smoothingOptions(mode)
        var canonical = emptyList<RecordedTracePoint>()
        points.forEach { point ->
            canonical = appendCanonicalRecordingPoint(canonical, point, options).points
        }
        return flushCanonicalRecordingTail(canonical, options).points
    }

    private fun watchGpsDistance(points: List<RecordedTracePoint>): Double {
        val geometry = RecordingWatchGpsDistanceGeometry()
        var distance = 0.0
        points.forEach { point ->
            geometry.append(point, false, HIKE, 3)?.let { distance += estimate(it).distanceMeters }
        }
        geometry.flush()?.let { distance += estimate(it).distanceMeters }
        return distance
    }

    private fun estimate(segment: RecordingWatchGpsDistanceSegment): RecordingDistanceEstimate =
        estimateRecordingDistanceDelta(
            RecordingDistanceInput(
                geometricDeltaMeters = segment.geometricDeltaMeters,
                previous = segment.previous,
                current = segment.current,
                elapsedSincePreviousMs = segment.elapsedSincePreviousMs,
                activityProfile = HIKE,
                isContinuityRecovery = segment.isContinuityRecovery,
            ),
        )

    private fun smoothingOptions(mode: String) =
        RecordingPointSmoothingOptions(
            mode = mode,
            activityProfile = HIKE,
            sampleIntervalSeconds = 3,
        )

    private fun noisyTrack(): List<RecordedTracePoint> =
        listOf(0.0, 8.0, -8.0, 8.0, -8.0, 8.0, -8.0, 0.0).mapIndexed { index, lateralMeters ->
            point(index * 10.0, lateralMeters = lateralMeters, timeMillis = index * 3_000L)
        }

    private fun point(
        xMeters: Double,
        lateralMeters: Double = 0.0,
        timeMillis: Long = (xMeters / 10.0 * 3_000L).toLong(),
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong =
                LatLong(
                    45.0 + xMeters / 111_320.0,
                    6.0 + lateralMeters / (111_320.0 * cos(Math.toRadians(45.0))),
                ),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = 12f,
            speedMps = 1.2f,
        )

    private companion object {
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
    }
}
