package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import com.glancemap.trailcore.recording.RecordingElevationProfilePoint
import com.glancemap.trailcore.recording.averageWalkingCadence
import com.glancemap.trailcore.recording.buildRecordingElevationProfileCumulative
import com.glancemap.trailcore.recording.estimateRecordingCalories
import com.glancemap.trailcore.recording.fastestRecordingPointSegmentSpeedMps
import com.glancemap.trailcore.recording.recordingHaversineMeters

internal fun phoneRecordingTrackStatistics(
    recording: PhoneRecordingUiState,
    settings: PhoneGeneralSettings,
): RecordedActivityStatistics {
    val points = recording.points
    val profile = if (recording.session.isActive) recording.activityProfile else settings.activityProfile
    val segmentLengths =
        DoubleArray((points.size - 1).coerceAtLeast(0)) { index ->
            if (points[index + 1].startsNewSegment) {
                0.0
            } else {
                recordingHaversineMeters(points[index].location, points[index + 1].location)
            }
        }
    val cumulativeDistances = DoubleArray(points.size)
    for (index in 1 until points.size) {
        cumulativeDistances[index] = cumulativeDistances[index - 1] + segmentLengths[index - 1]
    }
    val elevation =
        if (points.any { it.elevationMeters != null }) {
            buildRecordingElevationProfileCumulative(
                points.map { RecordingElevationProfilePoint(it.elevationMeters, it.startsNewSegment) },
                segmentLengths,
                cumulativeDistances,
            )
        } else {
            null
        }
    val calories =
        if (points.size >= 2) {
            estimateRecordingCalories(
                points = points,
                userWeightKg = settings.userWeightKg,
                backpackWeightKg = settings.backpackWeightKg,
                bikeWeightKg = settings.bikeWeightKg,
                activityProfile = profile.storageValue,
            )
        } else {
            null
        }
    return RecordedActivityStatistics(
        activityProfile = profile.storageValue,
        pointCount = points.size,
        elevationGainMeters = elevation?.first?.lastOrNull(),
        elevationLossMeters = elevation?.second?.lastOrNull(),
        fastestSpeedMps = points.fastestRecordingPointSegmentSpeedMps(),
        caloriesGrossKcal = calories?.grossKcal,
        caloriesActiveKcal = calories?.activeKcal,
        caloriesRestingKcal = calories?.restingKcal,
    )
}

internal fun phoneRecordingLiveStatistics(
    recording: PhoneRecordingUiState,
    track: RecordedActivityStatistics,
    nowMillis: Long,
    stepCount: Int? = null,
): RecordedActivityStatistics {
    val start = recording.session.startedAtMillis ?: return track
    val activeSeconds = recording.session.elapsedMillis(nowMillis) / 1_000.0
    return track.copy(
        distanceMeters = recording.distanceMeters,
        durationSeconds = activeSeconds,
        totalDurationSeconds = (nowMillis - start).coerceAtLeast(0L) / 1_000.0,
        averageSpeedMps = activeSeconds.takeIf { it > 0.0 }?.let { recording.distanceMeters / it },
        stepCount = stepCount,
        averageCadenceSpm =
            if (recording.activityProfile == PhoneActivityProfile.HIKE) {
                averageWalkingCadence(stepCount, recording.session.elapsedMillis(nowMillis))
            } else {
                null
            },
    )
}
