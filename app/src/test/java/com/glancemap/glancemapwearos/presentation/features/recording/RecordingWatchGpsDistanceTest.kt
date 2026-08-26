package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingWatchGpsDistanceTest {
    @Test
    fun watchGpsDistanceIsIndependentFromSavedTrackSmoothingMode() {
        val distances =
            listOf(
                SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
            ).map(::distanceForMode)

        assertEquals(distances.first(), distances[1], 0.0001)
        assertEquals(distances.first(), distances[2], 0.0001)
    }

    @Test
    fun resetWatchGpsDistanceContinuityExcludesVisualPauseConnector() {
        val beforePause = point(index = 0, lateralMeters = 0.0)
        val resumed =
            point(index = 2, lateralMeters = 0.0).copy(
                startsNewSegment = true,
                segmentStartReason = RecordingSegmentStartReason.MANUAL_PAUSE,
            )
        val geometry = RecordingWatchGpsDistanceGeometry()
        geometry.append(
            beforePause,
            isContinuityRecovery = false,
            activityProfile = HIKE,
            sampleIntervalSeconds = 3,
        )
        geometry.reset()

        assertEquals(1, recordedTraceSegments(listOf(beforePause, resumed)).size)
        assertEquals(
            null,
            geometry.append(
                resumed,
                isContinuityRecovery = false,
                activityProfile = HIKE,
                sampleIntervalSeconds = 3,
            ),
        )
        assertEquals(null, geometry.flush())
    }

    @Test
    fun alternatingLateralZigZagAddsMateriallyLessDistanceThanRawPolyline() {
        val points =
            listOf(0.0, 8.0, -8.0, 8.0, -8.0, 8.0, -8.0, 8.0, -8.0, 8.0, -8.0, 8.0, 0.0)
                .mapIndexed(::point)

        val rawDistance = naiveDistance(points)
        val filteredDistance = watchGpsDistance(points)
        val forwardProgress = haversineMeters(points.first().latLong, points.last().latLong)

        assertTrue(filteredDistance < rawDistance * 0.90)
        assertTrue(filteredDistance >= forwardProgress * 0.90)
    }

    @Test
    fun straightCleanMovementRemainsEssentiallyUnchanged() {
        val points = (0..6).map { index -> point(index, lateralMeters = 0.0) }

        assertEquals(naiveDistance(points), watchGpsDistance(points), 0.01)
    }

    @Test
    fun sparseCadenceWatchGpsDistanceKeepsOriginalGeometryAndIgnoresSavedMode() {
        listOf(HIKE, BIKE).forEach { activityProfile ->
            listOf(60, 120).forEach { sampleIntervalSeconds ->
                val points = sparseStraightTrack(sampleIntervalSeconds)
                val distances =
                    listOf(OFF, ADAPTIVE, STRONG).map { mode ->
                        distanceForMode(
                            mode = mode,
                            points = points,
                            activityProfile = activityProfile,
                            sampleIntervalSeconds = sampleIntervalSeconds,
                        )
                    }

                assertTrue(distances.first() > 0.0)
                distances.forEach { distance ->
                    assertEquals(naiveDistance(points), distance, 0.01)
                }
            }
        }
    }

    @Test
    fun recoverySegmentStillUsesExistingDistanceCap() {
        val geometry = RecordingWatchGpsDistanceGeometry()
        val before = point(index = 0, lateralMeters = 0.0)
        val recovered = point(index = 18, lateralMeters = 0.0).copy(timeMillis = 20_000L)
        val after = point(index = 19, lateralMeters = 0.0).copy(timeMillis = 23_000L)

        geometry.append(before, isContinuityRecovery = false, activityProfile = HIKE, sampleIntervalSeconds = 3)
        geometry.append(recovered, isContinuityRecovery = true, activityProfile = HIKE, sampleIntervalSeconds = 3)
        val segment =
            geometry.append(after, isContinuityRecovery = false, activityProfile = HIKE, sampleIntervalSeconds = 3)
        assertNotNull(segment)
        val estimate = estimate(segment!!)

        assertTrue(estimate.capped)
        assertTrue(estimate.distanceMeters < segment.geometricDeltaMeters)
    }

    @Test
    fun pauseBoundaryFlushesSavedSmoothingBeforeTheResumedTail() {
        val options =
            RecordingPointSmoothingOptions(
                mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                sampleIntervalSeconds = 3,
            )
        var canonical = emptyList<RecordedTracePoint>()
        (0..4).forEach { index ->
            canonical =
                appendCanonicalRecordingPoint(
                    canonical,
                    point(index, if (index % 2 == 0) 3.0 else -3.0),
                    options,
                ).points
        }
        canonical =
            appendCanonicalRecordingPoint(
                canonical,
                point(5, 0.0).copy(
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.MANUAL_PAUSE,
                ),
                options,
            ).points
        val finalizedBeforePause = canonical.dropLast(1)
        val finalizedCoordinates = finalizedBeforePause.map { it.latLong }

        (6..9).forEach { index ->
            canonical = appendCanonicalRecordingPoint(canonical, point(index, 4.0), options).points
        }

        assertEquals(finalizedCoordinates, canonical.take(finalizedCoordinates.size).map { it.latLong })
        assertEquals(true, finalizedBeforePause.all { it.trajectoryFinalized })
    }

    private fun distanceForMode(
        mode: String,
        points: List<RecordedTracePoint> = modeIndependenceTrack(),
        activityProfile: String = HIKE,
        sampleIntervalSeconds: Int = 3,
    ): Double {
        var canonical = emptyList<RecordedTracePoint>()
        val geometry = RecordingWatchGpsDistanceGeometry()
        var distanceMeters = 0.0
        points.forEach { current ->
            canonical =
                appendCanonicalRecordingPoint(
                    existingPoints = canonical,
                    point = current,
                    options =
                        RecordingPointSmoothingOptions(
                            mode = mode,
                            activityProfile = activityProfile,
                            sampleIntervalSeconds = sampleIntervalSeconds,
                        ),
                ).points
            geometry
                .append(
                    current,
                    isContinuityRecovery = false,
                    activityProfile = activityProfile,
                    sampleIntervalSeconds = sampleIntervalSeconds,
                )?.let { segment -> distanceMeters += estimate(segment, activityProfile).distanceMeters }
        }
        geometry.flush()?.let { segment -> distanceMeters += estimate(segment, activityProfile).distanceMeters }
        return distanceMeters
    }

    private fun modeIndependenceTrack(): List<RecordedTracePoint> =
        listOf(0.0, 3.5, -3.0, 4.0, -3.5, 3.0, -4.0, 3.5, -3.0, 3.0, -2.5, 2.0, 0.0)
            .mapIndexed(::point)

    private fun sparseStraightTrack(sampleIntervalSeconds: Int): List<RecordedTracePoint> =
        (0..3).map { index ->
            point(index, lateralMeters = 0.0, sampleIntervalSeconds = sampleIntervalSeconds)
        }

    private fun watchGpsDistance(points: List<RecordedTracePoint>): Double {
        val geometry = RecordingWatchGpsDistanceGeometry()
        var distanceMeters = 0.0
        points.forEach { point ->
            geometry
                .append(point, isContinuityRecovery = false, activityProfile = HIKE, sampleIntervalSeconds = 3)
                ?.let { segment -> distanceMeters += estimate(segment).distanceMeters }
        }
        geometry.flush()?.let { segment -> distanceMeters += estimate(segment).distanceMeters }
        return distanceMeters
    }

    private fun naiveDistance(points: List<RecordedTracePoint>): Double =
        points.zipWithNext().sumOf { (before, after) ->
            haversineMeters(before.latLong, after.latLong)
        }

    private fun estimate(
        segment: RecordingWatchGpsDistanceSegment,
        activityProfile: String = HIKE,
    ): RecordingDistanceEstimate =
        estimateRecordingDistanceDelta(
            RecordingDistanceInput(
                geometricDeltaMeters = segment.geometricDeltaMeters,
                previous = segment.previous,
                current = segment.current,
                elapsedSincePreviousMs = segment.elapsedSincePreviousMs,
                activityProfile = activityProfile,
                isContinuityRecovery = segment.isContinuityRecovery,
            ),
        )

    private fun point(
        index: Int,
        lateralMeters: Double,
        sampleIntervalSeconds: Int = 3,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong =
                LatLong(
                    45.0 + index * 10.0 / 111_320.0,
                    6.0 + lateralMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(45.0))),
                ),
            elevationMeters = null,
            timeMillis = index * sampleIntervalSeconds * 1_000L,
            accuracyMeters = 12f,
            speedMps = 1.2f,
        )

    private companion object {
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
        const val ADAPTIVE = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE
        const val OFF = SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF
        const val STRONG = SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG
    }
}
