package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.profile.TrailRouteProfile
import com.glancemap.trailcore.profile.TrailWindow
import com.glancemap.trailcore.profile.windowBetweenDistances
import kotlin.math.max
import kotlin.math.min

/**
 * A transparent journey projection for one mission day. Every event is derived from imported GPX
 * waypoints or recorded GPX elevation; it deliberately does not infer terrain hazards or water.
 */
data class MissionDayTimeline(
    val window: TrailWindow,
    val events: List<MissionDayTimelineEvent>,
)

data class MissionDayTimelineEvent(
    val type: MissionDayTimelineEventType,
    val title: String,
    val detail: String? = null,
    val distanceFromDayStartMeters: Double,
    val estimatedOffsetSeconds: Double,
    val ascentMeters: Double? = null,
)

enum class MissionDayTimelineEventType {
    START,
    CLIMB,
    WAYPOINT,
    FINISH,
}

fun RouteLibraryRouteDetails.missionDayTimeline(day: MissionPlanDay): MissionDayTimeline {
    val dayWindow = missionPlanBriefing(day)
    val dayStart = dayWindow.startDistanceMeters
    val dayEnd = dayWindow.endDistanceMeters
    val events =
        buildList {
            add(
                MissionDayTimelineEvent(
                    type = MissionDayTimelineEventType.START,
                    title = "Start",
                    distanceFromDayStartMeters = 0.0,
                    estimatedOffsetSeconds = 0.0,
                ),
            )
            addAll(profile.missionDayClimbEvents(dayStart, dayEnd))
            addAll(
                waypoints
                    .asSequence()
                    .filter { waypoint -> waypoint.distanceFromStartMeters > dayStart }
                    .filter { waypoint -> waypoint.distanceFromStartMeters < dayEnd }
                    .take(MAX_WAYPOINT_EVENTS)
                    .map { waypoint ->
                        MissionDayTimelineEvent(
                            type = MissionDayTimelineEventType.WAYPOINT,
                            title = waypoint.title,
                            detail = waypoint.description,
                            distanceFromDayStartMeters = waypoint.distanceFromStartMeters - dayStart,
                            estimatedOffsetSeconds =
                                profile
                                    .windowBetweenDistances(dayStart, waypoint.distanceFromStartMeters)
                                    .estimatedDurationSeconds,
                        )
                    }.toList(),
            )
            add(
                MissionDayTimelineEvent(
                    type = MissionDayTimelineEventType.FINISH,
                    title = "Finish",
                    distanceFromDayStartMeters = dayWindow.distanceMeters,
                    estimatedOffsetSeconds = dayWindow.estimatedDurationSeconds,
                ),
            )
        }.sortedWith(
            compareBy<MissionDayTimelineEvent>(MissionDayTimelineEvent::distanceFromDayStartMeters)
                .thenBy { event -> event.type.timelineOrder() },
        )
    return MissionDayTimeline(window = dayWindow, events = events)
}

@Suppress("CyclomaticComplexMethod")
private fun TrailRouteProfile.missionDayClimbEvents(
    dayStartDistanceMeters: Double,
    dayEndDistanceMeters: Double,
): List<MissionDayTimelineEvent> {
    if (dayEndDistanceMeters <= dayStartDistanceMeters) return emptyList()

    val climbs = mutableListOf<DistanceRange>()
    var activeStart: Double? = null
    var activeEnd: Double? = null

    fun finishActiveClimb() {
        val start = activeStart
        val end = activeEnd
        if (start != null && end != null && end > start) {
            climbs += DistanceRange(start, end)
        }
        activeStart = null
        activeEnd = null
    }

    for (index in 0 until points.lastIndex) {
        val from = points[index]
        val to = points[index + 1]
        val segmentStart = cumulativeDistanceMeters[index]
        val segmentEnd = cumulativeDistanceMeters[index + 1]
        val fromElevation = from.elevationMeters
        val toElevation = to.elevationMeters
        val overlapsDay = segmentEnd > dayStartDistanceMeters && segmentStart < dayEndDistanceMeters
        val rises =
            !to.startsNewSegment &&
                fromElevation != null &&
                toElevation != null &&
                toElevation > fromElevation

        if (!overlapsDay || !rises) {
            finishActiveClimb()
            continue
        }

        val clippedStart = max(segmentStart, dayStartDistanceMeters)
        val clippedEnd = min(segmentEnd, dayEndDistanceMeters)
        if (activeStart == null) activeStart = clippedStart
        activeEnd = clippedEnd
    }
    finishActiveClimb()

    return climbs
        .asSequence()
        .map { climb ->
            val climbWindow = windowBetweenDistances(climb.startDistanceMeters, climb.endDistanceMeters)
            climb to climbWindow
        }.filter { (_, window) -> window.ascentMeters >= MINIMUM_CLIMB_EVENT_ASCENT_METERS }
        .take(MAX_CLIMB_EVENTS)
        .map { (climb, window) ->
            MissionDayTimelineEvent(
                type = MissionDayTimelineEventType.CLIMB,
                title = "Climb",
                detail = "${window.ascentMeters.roundedMeters()} ascent",
                distanceFromDayStartMeters = climb.startDistanceMeters - dayStartDistanceMeters,
                estimatedOffsetSeconds =
                    windowBetweenDistances(dayStartDistanceMeters, climb.startDistanceMeters)
                        .estimatedDurationSeconds,
                ascentMeters = window.ascentMeters,
            )
        }.toList()
}

private data class DistanceRange(
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
)

private fun MissionDayTimelineEventType.timelineOrder(): Int =
    when (this) {
        MissionDayTimelineEventType.START -> 0
        MissionDayTimelineEventType.CLIMB -> 1
        MissionDayTimelineEventType.WAYPOINT -> 2
        MissionDayTimelineEventType.FINISH -> 3
    }

private fun Double.roundedMeters(): String = "${toInt()} m"

private const val MAX_CLIMB_EVENTS = 4
private const val MAX_WAYPOINT_EVENTS = 8
private const val MINIMUM_CLIMB_EVENT_ASCENT_METERS = 40.0
