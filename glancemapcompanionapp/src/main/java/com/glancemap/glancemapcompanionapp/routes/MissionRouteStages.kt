package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.profile.TrailRouteProfile
import java.util.UUID
import kotlin.math.abs

internal fun TrailRouteProfile.suggestStageStops(stageCount: Int): List<Double> {
    val points = cumulativeDistanceMeters.withIndex().distinctBy { it.value }
    require(
        stageCount > 0 && points.size > stageCount && totalDistanceMeters > 0.0,
    ) { "Choose hiking days that each contain some GPX distance." }
    var previous = 0
    return (1 until stageCount).map { stage ->
        val targetTime = estimatedDurationSeconds * stage / stageCount
        val targetDistance = totalDistanceMeters * stage / stageCount
        val last = points.lastIndex - (stageCount - stage)
        val chosen =
            (previous + 1..last).minBy { index ->
                if (estimatedDurationSeconds > 0.0) {
                    abs(cumulativeEstimatedDurationSeconds[points[index].index] - targetTime)
                } else {
                    abs(points[index].value - targetDistance)
                }
            }
        previous = chosen
        points[chosen].value
    }
}

internal fun List<MissionPlanDay>.withLinkedStages(
    adventureId: String,
    routeId: String,
    dayIds: List<String>,
    stops: List<Double>,
    totalDistance: Double,
): List<MissionPlanDay> {
    val hikes = filter { it.adventureId == adventureId && !it.isRestDay }.sortedBy { it.dayNumber }
    val chosen = hikes.filter { it.id in dayIds }
    require(dayIds.isNotEmpty() && chosen.map { it.id } == dayIds) { "Choose hiking days in calendar order." }
    val first = hikes.indexOf(chosen.first())
    require(
        hikes.subList(first, first + chosen.size) == chosen,
    ) { "Choose consecutive hiking days; days off can stay between them." }
    val boundaries = listOf(0.0) + stops + totalDistance
    require(
        boundaries.size == chosen.size + 1 && boundaries.all { it.isFinite() },
    ) { "Each pair of hiking days needs one valid shared stop." }
    require(
        boundaries.zipWithNext().all { (start, end) -> end > start },
    ) { "Stops must follow the GPX without empty stages." }
    val groupId = UUID.randomUUID().toString()
    val unlinked = unlinkStageGroups(chosen.mapNotNull { it.stageGroupId }.toSet())
    return unlinked.map { day ->
        val index = dayIds.indexOf(day.id)
        if (index < 0) {
            day
        } else {
            day.copy(
                routeId = routeId,
                startDistanceMeters = boundaries[index],
                endDistanceMeters = boundaries[index + 1],
                stageGroupId = groupId,
                stopReviewRequired = day.needsStayReview(routeId, boundaries[index + 1], totalDistance),
            )
        }
    }
}

internal fun List<MissionPlanDay>.unlinkStageGroups(
    groupIds: Set<String>,
): List<MissionPlanDay> = map { if (it.stageGroupId in groupIds) it.copy(stageGroupId = null) else it }

/** Both sides of a shared stop change in the same repository transaction. */
internal fun List<MissionPlanDay>.changeLinkedDay(changed: MissionPlanDay): List<MissionPlanDay> {
    val original = first { it.id == changed.id }
    val groupId = original.stageGroupId
    return when {
        groupId == null -> map { if (it.id == changed.id) changed else it }
        original.routeId != changed.routeId || original.isRestDay != changed.isRestDay ->
            unlinkStageGroups(setOf(groupId)).map {
                if (it.id == changed.id) changed.copy(stageGroupId = null) else it
            }
        else -> updateLinkedStop(original, changed, groupId)
    }
}

private fun List<MissionPlanDay>.updateLinkedStop(
    original: MissionPlanDay,
    changed: MissionPlanDay,
    groupId: String,
): List<MissionPlanDay> {
    val group = filter { it.stageGroupId == groupId }.sortedBy { it.dayNumber }
    val index = group.indexOfFirst { it.id == original.id }
    val previous = group.getOrNull(index - 1)
    val next = group.getOrNull(index + 1)
    val end = requireNotNull(changed.endDistanceMeters) { "Linked stages need a finish. Use Plan across days." }
    validateMovedStop(original, changed, previous, next, end)
    return map { day ->
        when (day.id) {
            changed.id ->
                changed.copy(
                    stopReviewRequired =
                        changed.stopReviewRequired ||
                            (original.endDistanceMeters != end && day.hasPlannedStay()),
                )
            previous?.id ->
                day.copy(
                    endDistanceMeters = changed.startDistanceMeters,
                    stopReviewRequired =
                        day.stopReviewRequired ||
                            (day.endDistanceMeters != changed.startDistanceMeters && day.hasPlannedStay()),
                )
            next?.id -> day.copy(startDistanceMeters = end)
            else -> day
        }
    }
}

private fun validateMovedStop(
    original: MissionPlanDay,
    changed: MissionPlanDay,
    previous: MissionPlanDay?,
    next: MissionPlanDay?,
    end: Double,
) {
    require(
        previous != null || changed.startDistanceMeters == original.startDistanceMeters,
    ) { "The first stage starts at the GPX start." }
    require(next != null || end == original.endDistanceMeters) { "The final stage finishes at the GPX end." }
    require(
        previous == null || changed.startDistanceMeters > previous.startDistanceMeters,
    ) { "This stop would empty the previous day." }
    require(next == null || end < requireNotNull(next.endDistanceMeters)) { "This stop would empty the next day." }
}

internal fun List<MissionPlanDay>.validateStageLinks() {
    filter { it.stageGroupId != null }.groupBy { it.stageGroupId }.values.forEach { stages ->
        val ordered = stages.sortedBy { it.dayNumber }
        require(
            stages.all { !it.isRestDay && it.routeId != null && it.adventureId == ordered.first().adventureId },
        ) { "Linked stages must be hiking days in the same adventure with a GPX." }
        require(stages.map { it.routeId }.distinct().size == 1) { "Linked stages must use the same source GPX." }
        require(ordered.all { it.endDistanceMeters != null }) { "A linked stage is missing its GPX finish." }
        require(
            ordered.zipWithNext().all { (a, b) -> a.endDistanceMeters == b.startDistanceMeters },
        ) { "A linked stage does not start where the previous hiking day finishes." }
        val hikingIds =
            filter { it.adventureId == ordered.first().adventureId && !it.isRestDay }
                .sortedBy { it.dayNumber }
                .map { it.id }
        val start = hikingIds.indexOf(ordered.first().id)
        require(
            hikingIds.subList(start, start + ordered.size) == ordered.map { it.id },
        ) { "Linked stages must follow consecutive hiking days." }
    }
}

internal fun MissionPlanDay.hasPlannedStay(): Boolean =
    !overnight.isNullOrBlank() ||
        booking == MissionBooking.BOOKED ||
        accommodation in listOf(MissionOvernight.REFUGE, MissionOvernight.CAMPSITE, MissionOvernight.OTHER)

/** A day off inherits the moved stop too; its refuge booking must be reviewed. */
internal fun List<MissionPlanDay>.reviewMovedRestStays(before: List<MissionPlanDay>): List<MissionPlanDay> {
    fun List<MissionPlanDay>.previousHike(day: MissionPlanDay): Pair<String?, Double?>? =
        filter { it.adventureId == day.adventureId && it.dayNumber < day.dayNumber && !it.isRestDay }
            .maxByOrNull { it.dayNumber }
            ?.let { it.routeId to it.endDistanceMeters }
    return map { day ->
        if (day.isRestDay && day.hasPlannedStay() && before.previousHike(day) != previousHike(day)) {
            day.copy(stopReviewRequired = true)
        } else {
            day
        }
    }
}

internal fun MissionPlanDay.needsStayReview(
    routeId: String,
    endDistance: Double,
    totalDistance: Double,
): Boolean {
    if (stopReviewRequired) return true
    val moved = this.routeId != routeId || (endDistanceMeters ?: totalDistance) != endDistance
    return moved && hasPlannedStay()
}
