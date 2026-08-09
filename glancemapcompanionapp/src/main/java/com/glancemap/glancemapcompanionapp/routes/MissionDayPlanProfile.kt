@file:Suppress("MaxLineLength", "ReturnCount")

package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.profile.TrailRouteProfile
import kotlin.math.roundToInt

/** A bounded GPX elevation/time profile for one planned day, kept separate from forecast data. */
data class MissionDayPlanProfile(
    val totalDistanceMeters: Double,
    val estimatedDurationSeconds: Double,
    val points: List<MissionDayPlanProfilePoint>,
)

data class MissionDayPlanProfilePoint(
    val distanceFromDayStartMeters: Double,
    val estimatedOffsetSeconds: Double,
    val elevationMeters: Double?,
)

fun RouteLibraryRouteDetails.missionDayPlanProfile(day: MissionPlanDay): MissionDayPlanProfile {
    val window = missionPlanBriefing(day)
    if (window.distanceMeters <= 0.0) {
        return MissionDayPlanProfile(
            totalDistanceMeters = 0.0,
            estimatedDurationSeconds = 0.0,
            points = emptyList(),
        )
    }
    val startPosition = profile.positionAtDistance(window.startDistanceMeters)
    val rawPoints =
        buildList {
            add(startPosition)
            profile.points.indices
                .filter { index -> profile.cumulativeDistanceMeters[index] in window.startDistanceMeters..window.endDistanceMeters }
                .forEach { index ->
                    add(
                        ProfilePosition(
                            distanceMeters = profile.cumulativeDistanceMeters[index],
                            estimatedDurationSeconds = profile.cumulativeEstimatedDurationSeconds[index],
                            elevationMeters = profile.points[index].elevationMeters,
                        ),
                    )
                }
            add(profile.positionAtDistance(window.endDistanceMeters))
        }.distinctBy(ProfilePosition::distanceMeters)

    return MissionDayPlanProfile(
        totalDistanceMeters = window.distanceMeters,
        estimatedDurationSeconds = window.estimatedDurationSeconds,
        points =
            rawPoints
                .downsample(MAX_PROFILE_POINTS)
                .map { position ->
                    MissionDayPlanProfilePoint(
                        distanceFromDayStartMeters = position.distanceMeters - window.startDistanceMeters,
                        estimatedOffsetSeconds = position.estimatedDurationSeconds - startPosition.estimatedDurationSeconds,
                        elevationMeters = position.elevationMeters,
                    )
                },
    )
}

private fun TrailRouteProfile.positionAtDistance(targetDistanceMeters: Double): ProfilePosition {
    if (points.isEmpty()) return ProfilePosition(0.0, 0.0, null)
    if (points.size == 1) {
        return ProfilePosition(
            distanceMeters = 0.0,
            estimatedDurationSeconds = 0.0,
            elevationMeters = points.first().elevationMeters,
        )
    }
    val target = targetDistanceMeters.coerceIn(0.0, totalDistanceMeters)
    val segmentIndex =
        (0 until points.lastIndex)
            .firstOrNull { index -> target <= cumulativeDistanceMeters[index + 1] }
            ?: points.lastIndex - 1
    val startDistance = cumulativeDistanceMeters[segmentIndex]
    val endDistance = cumulativeDistanceMeters[segmentIndex + 1]
    val fraction = if (endDistance <= startDistance) 0.0 else (target - startDistance) / (endDistance - startDistance)
    return ProfilePosition(
        distanceMeters = target,
        estimatedDurationSeconds =
            cumulativeEstimatedDurationSeconds[segmentIndex] +
                fraction * (cumulativeEstimatedDurationSeconds[segmentIndex + 1] - cumulativeEstimatedDurationSeconds[segmentIndex]),
        elevationMeters = points[segmentIndex].interpolatedElevationTo(points[segmentIndex + 1], fraction),
    )
}

private fun com.glancemap.trailcore.profile.TrailPoint.interpolatedElevationTo(
    other: com.glancemap.trailcore.profile.TrailPoint,
    fraction: Double,
): Double? {
    val startElevation = elevationMeters
    val endElevation = other.elevationMeters
    return when {
        startElevation != null && endElevation != null -> startElevation + fraction * (endElevation - startElevation)
        startElevation != null -> startElevation
        else -> endElevation
    }
}

private fun List<ProfilePosition>.downsample(maximumPointCount: Int): List<ProfilePosition> {
    if (size <= maximumPointCount) return this
    val step = lastIndex.toDouble() / (maximumPointCount - 1)
    return (0 until maximumPointCount)
        .map { index -> this[(index * step).roundToInt()] }
        .distinctBy(ProfilePosition::distanceMeters)
}

private data class ProfilePosition(
    val distanceMeters: Double,
    val estimatedDurationSeconds: Double,
    val elevationMeters: Double?,
)

private const val MAX_PROFILE_POINTS = 240
