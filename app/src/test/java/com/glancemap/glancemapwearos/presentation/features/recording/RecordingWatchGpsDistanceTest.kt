package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
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

        val bridgeDistance = watchGpsRecordingGeometryDeltaMeters(beforePause, resumed)
        val resumedDistance = watchGpsRecordingGeometryDeltaMeters(previous = null, current = resumed)

        assertEquals(1, recordedTraceSegments(listOf(beforePause, resumed)).size)
        assertEquals(0.0, resumedDistance, 0.0)
        assertEquals(true, bridgeDistance > 0.0)
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
            canonical = appendCanonicalRecordingPoint(canonical, point(index, if (index % 2 == 0) 3.0 else -3.0), options).points
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

    private fun distanceForMode(mode: String): Double {
        var canonical = emptyList<RecordedTracePoint>()
        var previousWatchGpsPoint: RecordedTracePoint? = null
        var distanceMeters = 0.0
        listOf(0.0, 3.5, -3.0, 4.0, -3.5, 3.0, -4.0, 3.5, -3.0, 3.0, -2.5, 2.0, 0.0).forEachIndexed { index, lateralMeters ->
            val current = point(index, lateralMeters)
            canonical =
                appendCanonicalRecordingPoint(
                    existingPoints = canonical,
                    point = current,
                    options =
                        RecordingPointSmoothingOptions(
                            mode = mode,
                            activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                            sampleIntervalSeconds = 3,
                        ),
                ).points
            val estimate =
                estimateRecordingDistanceDelta(
                    geometricDeltaMeters = watchGpsRecordingGeometryDeltaMeters(previousWatchGpsPoint, current),
                    previous = previousWatchGpsPoint,
                    current = current,
                    elapsedSincePreviousMs =
                        previousWatchGpsPoint?.let { previous -> current.timeMillis - previous.timeMillis } ?: 0L,
                    activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                    isContinuityRecovery = false,
                )
            distanceMeters += estimate.distanceMeters
            previousWatchGpsPoint = current
        }
        return distanceMeters
    }

    private fun point(
        index: Int,
        lateralMeters: Double,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong =
                LatLong(
                    45.0 + index * 10.0 / 111_320.0,
                    6.0 + lateralMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(45.0))),
                ),
            elevationMeters = null,
            timeMillis = index * 3_000L,
            accuracyMeters = 12f,
            speedMps = 1.2f,
        )
}
