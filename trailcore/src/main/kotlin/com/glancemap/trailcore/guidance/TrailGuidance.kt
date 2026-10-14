package com.glancemap.trailcore.guidance

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.RouteProjectionConfig
import com.glancemap.trailcore.geo.buildCumulativeDistancesMeters
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.geo.initialBearingDegrees
import com.glancemap.trailcore.geo.projectPointOntoRoute
import com.glancemap.trailcore.profile.TrailPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TrailGuidanceCommand {
    CONTINUE,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    FINISH,
}

enum class TrailGuidanceInstructionSource {
    GPX_GEOMETRY,
    BROUTER_HINT,
}

data class TrailGuidanceInstruction(
    val command: TrailGuidanceCommand,
    val message: String,
    val location: GeoPoint,
    val trackPointIndex: Int,
    val distanceFromStartMeters: Double,
    val turnAngleDegrees: Float?,
    val source: TrailGuidanceInstructionSource = TrailGuidanceInstructionSource.GPX_GEOMETRY,
)

data class TrailGuidanceSession(
    val trackId: String,
    val trackTitle: String,
    val trackPoints: List<TrailPoint>,
    val cumulativeDistancesMeters: List<Double>,
    val totalDistanceMeters: Double,
    val instructions: List<TrailGuidanceInstruction>,
    val startReached: Boolean = false,
    val reversed: Boolean = false,
    val routeOffsetMeters: Double = 0.0,
    val cumulativeAscentMeters: List<Double> = emptyList(),
    val cumulativeDescentMeters: List<Double> = emptyList(),
)

enum class TrailGuidanceMode {
    WAITING_FOR_LOCATION,
    TO_START,
    FOLLOW_ROUTE,
    FINISHED,
}

enum class TrailGuidanceTerrainDirection {
    UPHILL,
    DOWNHILL,
    FLAT,
}

/** Terrain of the route immediately after the next maneuver. */
data class TrailGuidanceTerrainPreview(
    val direction: TrailGuidanceTerrainDirection,
    val elevationChangeMeters: Double,
    val distanceMeters: Double,
)

/** Terrain retained briefly after a maneuver, so the rider can confirm the chosen branch. */
data class TrailGuidanceTerrainConfirmation(
    val maneuver: TrailGuidanceCommand,
    val terrain: TrailGuidanceTerrainPreview,
)

data class TrailGuidanceState(
    val active: Boolean,
    val mode: TrailGuidanceMode,
    val trackTitle: String?,
    val nextInstruction: TrailGuidanceInstruction?,
    val distanceToInstructionMeters: Double?,
    val distanceToStartMeters: Double?,
    val bearingToStartDegrees: Float?,
    val distanceToRouteMeters: Double?,
    val bearingToRouteDegrees: Float?,
    val distanceRemainingMeters: Double?,
    val routeProgressFraction: Float?,
    val offRoute: Boolean,
    val distanceFromStartMeters: Double? = null,
    val followingInstruction: TrailGuidanceInstruction? = null,
    val distanceToFollowingInstructionMeters: Double? = null,
    val estimatedRemainingSeconds: Long? = null,
    val remainingAscentMeters: Double? = null,
    val remainingDescentMeters: Double? = null,
    val currentAltitudeMeters: Double? = null,
    val nextSegmentTerrain: TrailGuidanceTerrainPreview? = null,
    val recentManeuverTerrain: TrailGuidanceTerrainConfirmation? = null,
    val alertSessionKey: String? = null,
    val alertGpsDeliveryIntervalMs: Long? = null,
    val projectionSegmentsScanned: Int? = null,
    val originalRouteDistanceFromStartMeters: Double? = null,
)

data class TrailGuidanceProjection(
    val segmentIndex: Int,
    val t: Double,
    val distanceFromStartMeters: Double,
    val distanceToRouteMeters: Double,
)

data class TrailGuidanceOffRouteConfirmationState(
    val offRoute: Boolean = false,
    val outsideSampleCount: Int = 0,
    val recoverySampleCount: Int = 0,
)

data class TrailGuidanceTuning(
    val startReachedDistanceMeters: Double = 35.0,
    val offRouteDistanceMeters: Double = 70.0,
    val finishDistanceMeters: Double = 25.0,
    val finishArrivalDistanceMeters: Double = 60.0,
    val instructionConfirmationDistanceMeters: Double = 12.0,
    val instructionLookAheadMeters: Double = 18.0,
    val instructionLookBehindMeters: Double = 18.0,
    val minInstructionSpacingMeters: Double = 40.0,
    val minInstructionAngleDegrees: Double = 25.0,
)

@Suppress("LongParameterList") // Keep the existing guidance builder API while adding profile distances.
fun buildTrailGuidanceSession(
    trackId: String,
    trackTitle: String,
    trackPoints: List<TrailPoint>,
    startReached: Boolean = false,
    reversed: Boolean = false,
    tuning: TrailGuidanceTuning = TrailGuidanceTuning(),
    cumulativeDistancesMeters: List<Double>? = null,
    routeOffsetMeters: Double = 0.0,
): TrailGuidanceSession {
    require(trackPoints.size >= 2) { "The GPX does not contain enough points for guidance." }
    val cumulative = cumulativeDistancesMeters ?: buildCumulativeTrailDistances(trackPoints)
    require(cumulative.size == trackPoints.size) { "The cumulative route distances must match the track points." }
    return TrailGuidanceSession(
        trackId = trackId,
        trackTitle = trackTitle,
        trackPoints = trackPoints,
        cumulativeDistancesMeters = cumulative,
        totalDistanceMeters = cumulative.lastOrNull() ?: 0.0,
        instructions =
            deriveHintedTrailGuidanceInstructions(
                trackPoints = trackPoints,
                cumulativeDistancesMeters = cumulative,
                tuning = tuning,
                reverseDirection = reversed,
            ).ifEmpty {
                deriveGpxTrailGuidanceInstructions(
                    trackPoints = trackPoints,
                    cumulativeDistancesMeters = cumulative,
                    tuning = tuning,
                )
            },
        startReached = startReached,
        reversed = reversed,
        routeOffsetMeters = routeOffsetMeters,
    )
}

fun computeTrailGuidanceState(
    session: TrailGuidanceSession?,
    currentLocation: GeoPoint?,
    tuning: TrailGuidanceTuning = TrailGuidanceTuning(),
    previousDistanceFromStartMeters: Double? = null,
): TrailGuidanceState {
    if (session == null) {
        return TrailGuidanceState(
            active = false,
            mode = TrailGuidanceMode.WAITING_FOR_LOCATION,
            trackTitle = null,
            nextInstruction = null,
            distanceToInstructionMeters = null,
            distanceToStartMeters = null,
            bearingToStartDegrees = null,
            distanceToRouteMeters = null,
            bearingToRouteDegrees = null,
            distanceRemainingMeters = null,
            routeProgressFraction = null,
            offRoute = false,
        )
    }
    if (currentLocation == null) {
        val nextInstruction = session.instructions.firstOrNull()
        val fullRouteElevation = remainingElevationMeters(session, 0.0)
        return TrailGuidanceState(
            active = true,
            mode = TrailGuidanceMode.WAITING_FOR_LOCATION,
            trackTitle = session.trackTitle,
            nextInstruction = nextInstruction,
            distanceToInstructionMeters = null,
            distanceToStartMeters = null,
            bearingToStartDegrees = null,
            distanceToRouteMeters = null,
            bearingToRouteDegrees = null,
            distanceRemainingMeters = session.totalDistanceMeters,
            routeProgressFraction = 0f,
            offRoute = false,
            remainingAscentMeters = fullRouteElevation.first,
            remainingDescentMeters = fullRouteElevation.second,
            originalRouteDistanceFromStartMeters = originalRouteDistance(session, 0.0),
            nextSegmentTerrain =
                nextInstruction?.let { instruction ->
                    nextSegmentTerrain(
                        session = session,
                        nextInstruction = instruction,
                        followingInstruction = session.instructions.getOrNull(1),
                    )
                },
        )
    }

    val start = session.trackPoints.first().location
    val distanceToStart = haversineMeters(currentLocation, start)
    if (!session.startReached && distanceToStart > tuning.startReachedDistanceMeters) {
        val fullRouteElevation = remainingElevationMeters(session, 0.0)
        return TrailGuidanceState(
            active = true,
            mode = TrailGuidanceMode.TO_START,
            trackTitle = session.trackTitle,
            nextInstruction = null,
            distanceToInstructionMeters = null,
            distanceToStartMeters = distanceToStart,
            bearingToStartDegrees = bearingDegrees(currentLocation, start).toFloat(),
            distanceToRouteMeters = null,
            bearingToRouteDegrees = null,
            distanceRemainingMeters = session.totalDistanceMeters,
            routeProgressFraction = 0f,
            offRoute = false,
            remainingAscentMeters = fullRouteElevation.first,
            remainingDescentMeters = fullRouteElevation.second,
            originalRouteDistanceFromStartMeters = originalRouteDistance(session, 0.0),
        )
    }

    val points = session.trackPoints.map { it.location }
    val projection =
        projectTrailLocationToRoute(
            trackPoints = session.trackPoints,
            cumulativeDistancesMeters = session.cumulativeDistancesMeters,
            location = currentLocation,
            previousDistanceFromStartMeters =
                previousDistanceFromStartMeters
                    ?: 0.0.takeIf { distanceToStart <= tuning.startReachedDistanceMeters },
        )
    val nearestRoutePoint =
        projection?.let {
            projectedRoutePoint(
                points = points,
                projection = it,
            )
        }
    val projectionSegmentsScanned = projection?.let { points.lastIndex }
    val distanceToRoute = projection?.distanceToRouteMeters
    val bearingToRoute = nearestRoutePoint?.let { bearingDegrees(currentLocation, it).toFloat() }
    val distanceFromStart = projection?.distanceFromStartMeters ?: 0.0
    val remaining = (session.totalDistanceMeters - distanceFromStart).coerceAtLeast(0.0)
    val remainingElevation = remainingElevationMeters(session, distanceFromStart)
    val finish = points.last()
    val distanceToFinish = haversineMeters(currentLocation, finish)
    val closeEnoughToFinish =
        distanceToFinish <= tuning.finishArrivalDistanceMeters &&
            (distanceToRoute ?: Double.MAX_VALUE) <= tuning.offRouteDistanceMeters
    if (remaining <= tuning.finishDistanceMeters && closeEnoughToFinish) {
        return TrailGuidanceState(
            active = true,
            mode = TrailGuidanceMode.FINISHED,
            trackTitle = session.trackTitle,
            nextInstruction = session.instructions.lastOrNull(),
            distanceToInstructionMeters = 0.0,
            distanceToStartMeters = 0.0,
            bearingToStartDegrees = null,
            distanceToRouteMeters = distanceToRoute,
            bearingToRouteDegrees = bearingToRoute,
            distanceRemainingMeters = 0.0,
            routeProgressFraction = 1f,
            offRoute = false,
            distanceFromStartMeters = session.totalDistanceMeters,
            projectionSegmentsScanned = projectionSegmentsScanned,
            remainingAscentMeters = 0.0,
            remainingDescentMeters = 0.0,
            originalRouteDistanceFromStartMeters = originalRouteDistance(session, session.totalDistanceMeters),
        )
    }

    val nextInstructionIndex =
        session.instructions.indexOfFirst {
            distanceFromStart <=
                it.distanceFromStartMeters + tuning.instructionConfirmationDistanceMeters
        }
    val resolvedInstructionIndex =
        if (nextInstructionIndex >= 0) nextInstructionIndex else session.instructions.lastIndex
    val nextInstruction = session.instructions.getOrNull(resolvedInstructionIndex)
    val followingInstruction = session.instructions.getOrNull(resolvedInstructionIndex + 1)
    val distanceToInstruction =
        nextInstruction
            ?.let { (it.distanceFromStartMeters - distanceFromStart).coerceAtLeast(0.0) }
    val distanceToFollowingInstruction =
        followingInstruction
            ?.let { (it.distanceFromStartMeters - distanceFromStart).coerceAtLeast(0.0) }
    val nextSegmentTerrain =
        nextInstruction?.let { instruction ->
            nextSegmentTerrain(
                session = session,
                nextInstruction = instruction,
                followingInstruction = followingInstruction,
            )
        }
    val recentManeuverTerrain =
        recentManeuverTerrain(
            session = session,
            distanceFromStartMeters = distanceFromStart,
            nextInstructionIndex = resolvedInstructionIndex,
        )

    return TrailGuidanceState(
        active = true,
        mode = TrailGuidanceMode.FOLLOW_ROUTE,
        trackTitle = session.trackTitle,
        nextInstruction = nextInstruction,
        distanceToInstructionMeters = distanceToInstruction,
        distanceToStartMeters = distanceToStart,
        bearingToStartDegrees = null,
        distanceToRouteMeters = distanceToRoute,
        bearingToRouteDegrees = bearingToRoute,
        distanceRemainingMeters = remaining,
        routeProgressFraction = routeProgressFraction(distanceFromStart, session.totalDistanceMeters),
        offRoute = (distanceToRoute ?: 0.0) > tuning.offRouteDistanceMeters,
        distanceFromStartMeters = distanceFromStart,
        followingInstruction = followingInstruction,
        distanceToFollowingInstructionMeters = distanceToFollowingInstruction,
        remainingAscentMeters = remainingElevation.first,
        remainingDescentMeters = remainingElevation.second,
        nextSegmentTerrain = nextSegmentTerrain,
        recentManeuverTerrain = recentManeuverTerrain,
        projectionSegmentsScanned = projectionSegmentsScanned,
        originalRouteDistanceFromStartMeters = originalRouteDistance(session, distanceFromStart),
    )
}

private fun originalRouteDistance(
    session: TrailGuidanceSession,
    distanceFromStartMeters: Double,
): Double =
    session.routeOffsetMeters +
        if (session.reversed) {
            (session.totalDistanceMeters - distanceFromStartMeters).coerceIn(0.0, session.totalDistanceMeters)
        } else {
            distanceFromStartMeters.coerceIn(0.0, session.totalDistanceMeters)
        }

private fun recentManeuverTerrain(
    session: TrailGuidanceSession,
    distanceFromStartMeters: Double,
    nextInstructionIndex: Int,
): TrailGuidanceTerrainConfirmation? {
    val completedInstruction = session.instructions.getOrNull(nextInstructionIndex - 1) ?: return null
    val distanceSinceManeuver = distanceFromStartMeters - completedInstruction.distanceFromStartMeters
    if (distanceSinceManeuver !in 0.0..TERRAIN_CONFIRMATION_DISTANCE_METERS) return null
    val terrain =
        nextSegmentTerrain(
            session = session,
            nextInstruction = completedInstruction,
            followingInstruction = session.instructions.getOrNull(nextInstructionIndex),
        ) ?: return null
    return TrailGuidanceTerrainConfirmation(maneuver = completedInstruction.command, terrain = terrain)
}

private fun nextSegmentTerrain(
    session: TrailGuidanceSession,
    nextInstruction: TrailGuidanceInstruction,
    followingInstruction: TrailGuidanceInstruction?,
): TrailGuidanceTerrainPreview? {
    if (nextInstruction.command == TrailGuidanceCommand.FINISH) return null
    val startDistance = nextInstruction.distanceFromStartMeters.coerceIn(0.0, session.totalDistanceMeters)
    val endDistance =
        min(
            startDistance + NEXT_SEGMENT_TERRAIN_LOOK_AHEAD_METERS,
            followingInstruction
                ?.distanceFromStartMeters
                ?.coerceIn(startDistance, session.totalDistanceMeters)
                ?: session.totalDistanceMeters,
        )
    val segmentDistance = endDistance - startDistance
    if (segmentDistance < NEXT_SEGMENT_TERRAIN_MIN_DISTANCE_METERS) return null
    val startElevation = routeElevationAtDistance(session, startDistance) ?: return null
    val endElevation = routeElevationAtDistance(session, endDistance) ?: return null
    val elevationChange = endElevation - startElevation
    val meaningfulChangeThreshold =
        max(
            NEXT_SEGMENT_TERRAIN_MIN_VERTICAL_CHANGE_METERS,
            segmentDistance * NEXT_SEGMENT_TERRAIN_MIN_GRADE,
        )
    val direction =
        when {
            elevationChange >= meaningfulChangeThreshold -> TrailGuidanceTerrainDirection.UPHILL
            elevationChange <= -meaningfulChangeThreshold -> TrailGuidanceTerrainDirection.DOWNHILL
            else -> TrailGuidanceTerrainDirection.FLAT
        }
    return TrailGuidanceTerrainPreview(
        direction = direction,
        elevationChangeMeters = elevationChange,
        distanceMeters = segmentDistance,
    )
}

private fun routeElevationAtDistance(
    session: TrailGuidanceSession,
    distanceMeters: Double,
): Double? {
    val cumulative = session.cumulativeDistancesMeters
    val points = session.trackPoints
    val target = distanceMeters.coerceIn(0.0, session.totalDistanceMeters)
    cumulative
        .indexOfFirst { abs(it - target) <= ELEVATION_DISTANCE_MATCH_TOLERANCE_METERS }
        .takeIf { it >= 0 }
        ?.let { index ->
            return points.getOrNull(index)?.elevationMeters?.takeIf(Double::isFinite)
        }
    for (index in 0 until points.lastIndex) {
        val segmentStart = cumulative.getOrNull(index) ?: continue
        val segmentEnd = cumulative.getOrNull(index + 1) ?: continue
        if (target !in segmentStart..segmentEnd || segmentEnd <= segmentStart) continue
        val from = points[index].elevationMeters?.takeIf(Double::isFinite) ?: return null
        val to = points[index + 1].elevationMeters?.takeIf(Double::isFinite) ?: return null
        val fraction = ((target - segmentStart) / (segmentEnd - segmentStart)).coerceIn(0.0, 1.0)
        return from + (to - from) * fraction
    }
    return null
}

private fun remainingElevationMeters(
    session: TrailGuidanceSession,
    distanceFromStartMeters: Double,
): Pair<Double?, Double?> {
    val hasElevationSnapshot = hasUsableElevationSnapshot(session)
    val totalAscent = session.cumulativeAscentMeters.lastOrNull()?.takeIf(Double::isFinite)
    val totalDescent = session.cumulativeDescentMeters.lastOrNull()?.takeIf(Double::isFinite)
    val ascentAtDistance =
        if (hasElevationSnapshot && totalAscent != null && totalDescent != null) {
            interpolateCumulativeElevation(
                cumulativeDistancesMeters = session.cumulativeDistancesMeters,
                cumulativeElevationMeters = session.cumulativeAscentMeters,
                distanceMeters = distanceFromStartMeters,
                totalDistanceMeters = session.totalDistanceMeters,
            )
        } else {
            null
        }
    val descentAtDistance =
        if (hasElevationSnapshot && totalAscent != null && totalDescent != null) {
            interpolateCumulativeElevation(
                cumulativeDistancesMeters = session.cumulativeDistancesMeters,
                cumulativeElevationMeters = session.cumulativeDescentMeters,
                distanceMeters = distanceFromStartMeters,
                totalDistanceMeters = session.totalDistanceMeters,
            )
        } else {
            null
        }

    return when {
        totalAscent == null -> null to null
        totalDescent == null -> null to null
        ascentAtDistance == null -> null to null
        descentAtDistance == null -> null to null
        else -> {
            (totalAscent - ascentAtDistance).coerceAtLeast(0.0) to
                (totalDescent - descentAtDistance).coerceAtLeast(0.0)
        }
    }
}

private fun hasUsableElevationSnapshot(session: TrailGuidanceSession): Boolean =
    when {
        session.cumulativeDistancesMeters.isEmpty() -> false
        session.cumulativeDistancesMeters.size != session.cumulativeAscentMeters.size -> false
        session.cumulativeDistancesMeters.size != session.cumulativeDescentMeters.size -> false
        else -> true
    }

private fun hasValidInterpolationInput(
    cumulativeDistancesMeters: List<Double>,
    cumulativeElevationMeters: List<Double>,
    totalDistanceMeters: Double,
): Boolean =
    when {
        cumulativeDistancesMeters.isEmpty() -> false
        cumulativeDistancesMeters.size != cumulativeElevationMeters.size -> false
        !totalDistanceMeters.isFinite() -> false
        totalDistanceMeters < 0.0 -> false
        else -> true
    }

private fun interpolateCumulativeElevation(
    cumulativeDistancesMeters: List<Double>,
    cumulativeElevationMeters: List<Double>,
    distanceMeters: Double,
    totalDistanceMeters: Double,
): Double? {
    val interpolated =
        if (!hasValidInterpolationInput(cumulativeDistancesMeters, cumulativeElevationMeters, totalDistanceMeters)) {
            null
        } else {
            val target = distanceMeters.coerceIn(0.0, totalDistanceMeters)
            val upperIndex =
                cumulativeDistancesMeters.binarySearch(target).let { result ->
                    if (result >= 0) result else -result - 1
                }
            when {
                upperIndex <= 0 -> cumulativeElevationMeters.firstOrNull()?.takeIf(Double::isFinite)
                upperIndex >= cumulativeDistancesMeters.size ->
                    cumulativeElevationMeters.lastOrNull()?.takeIf(Double::isFinite)
                else -> {
                    val startDistance = cumulativeDistancesMeters[upperIndex - 1]
                    val endDistance = cumulativeDistancesMeters[upperIndex]
                    val startElevation = cumulativeElevationMeters[upperIndex - 1]
                    val endElevation = cumulativeElevationMeters[upperIndex]
                    val hasFiniteValues =
                        startDistance.isFinite() &&
                            endDistance.isFinite() &&
                            startElevation.isFinite() &&
                            endElevation.isFinite()
                    when {
                        !hasFiniteValues -> null
                        endDistance <= startDistance -> endElevation
                        else -> {
                            val fraction =
                                ((target - startDistance) / (endDistance - startDistance))
                                    .coerceIn(0.0, 1.0)
                            startElevation + (endElevation - startElevation) * fraction
                        }
                    }
                }
            }
        }
    return interpolated
}

private const val NEXT_SEGMENT_TERRAIN_LOOK_AHEAD_METERS = 300.0
private const val NEXT_SEGMENT_TERRAIN_MIN_DISTANCE_METERS = 25.0
private const val NEXT_SEGMENT_TERRAIN_MIN_VERTICAL_CHANGE_METERS = 5.0
private const val NEXT_SEGMENT_TERRAIN_MIN_GRADE = 0.03
private const val TERRAIN_CONFIRMATION_DISTANCE_METERS = 75.0
private const val ELEVATION_DISTANCE_MATCH_TOLERANCE_METERS = 0.5

private fun projectedRoutePoint(
    points: List<GeoPoint>,
    projection: TrailGuidanceProjection,
): GeoPoint? {
    val a = points.getOrNull(projection.segmentIndex) ?: return null
    val b = points.getOrNull(projection.segmentIndex + 1) ?: return a
    val t = projection.t.coerceIn(0.0, 1.0)
    return GeoPoint(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )
}

private fun routeProgressFraction(
    distanceFromStartMeters: Double,
    totalDistanceMeters: Double,
): Float? {
    if (totalDistanceMeters <= 0.0) return null
    return (distanceFromStartMeters / totalDistanceMeters)
        .coerceIn(0.0, 1.0)
        .toFloat()
}

fun isGuidanceStartReached(
    session: TrailGuidanceSession?,
    currentLocation: GeoPoint?,
    tuning: TrailGuidanceTuning = TrailGuidanceTuning(),
): Boolean {
    if (session == null || session.startReached || currentLocation == null) return false
    val start = session.trackPoints.firstOrNull()?.location ?: return false
    return haversineMeters(currentLocation, start) <= tuning.startReachedDistanceMeters
}

fun deriveGpxTrailGuidanceInstructions(
    trackPoints: List<TrailPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeTrailDistances(trackPoints),
    tuning: TrailGuidanceTuning = TrailGuidanceTuning(),
): List<TrailGuidanceInstruction> {
    if (trackPoints.size < 2) return emptyList()
    val points = trackPoints.map { it.location }
    val instructions = mutableListOf<TrailGuidanceInstruction>()
    var lastInstructionDistance = Double.NEGATIVE_INFINITY

    for (index in 1 until points.lastIndex) {
        val distanceAtPoint = cumulativeDistancesMeters.getOrNull(index) ?: continue
        if (distanceAtPoint - lastInstructionDistance < tuning.minInstructionSpacingMeters) continue

        val beforeIndex =
            indexAtOrBeforeDistance(
                cumulativeDistancesMeters,
                (distanceAtPoint - tuning.instructionLookBehindMeters).coerceAtLeast(0.0),
            )
        val afterIndex =
            indexAtOrAfterDistance(
                cumulativeDistancesMeters,
                (distanceAtPoint + tuning.instructionLookAheadMeters)
                    .coerceAtMost(cumulativeDistancesMeters.last()),
            )
        if (beforeIndex == index || afterIndex == index || beforeIndex == afterIndex) continue
        if (trackPoints.subList(beforeIndex + 1, index + 1).any(TrailPoint::startsNewSegment)) continue
        if (trackPoints.subList(index + 1, afterIndex + 1).any(TrailPoint::startsNewSegment)) continue

        val incoming = bearingDegrees(points[beforeIndex], points[index])
        val outgoing = bearingDegrees(points[index], points[afterIndex])
        val delta = normalizeSignedDegrees(outgoing - incoming)
        val absDelta = abs(delta)
        if (absDelta < tuning.minInstructionAngleDegrees) continue

        val command = commandForTurn(delta)
        instructions +=
            TrailGuidanceInstruction(
                command = command,
                message = command.message,
                location = points[index],
                trackPointIndex = index,
                distanceFromStartMeters = distanceAtPoint,
                turnAngleDegrees = delta.toFloat(),
            )
        lastInstructionDistance = distanceAtPoint
    }

    instructions +=
        TrailGuidanceInstruction(
            command = TrailGuidanceCommand.FINISH,
            message = TrailGuidanceCommand.FINISH.message,
            location = points.last(),
            trackPointIndex = points.lastIndex,
            distanceFromStartMeters = cumulativeDistancesMeters.lastOrNull() ?: 0.0,
            turnAngleDegrees = null,
        )
    return instructions
}

fun deriveHintedTrailGuidanceInstructions(
    trackPoints: List<TrailPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeTrailDistances(trackPoints),
    tuning: TrailGuidanceTuning = TrailGuidanceTuning(),
    reverseDirection: Boolean = false,
): List<TrailGuidanceInstruction> {
    if (trackPoints.size < 2) return emptyList()
    val instructions = mutableListOf<TrailGuidanceInstruction>()
    var lastInstructionDistance = Double.NEGATIVE_INFINITY

    trackPoints.forEachIndexed { index, point ->
        val hint = point.guidanceHint ?: return@forEachIndexed
        val hintedCommand = routeInstructionCommandForHint(hint.commandCode) ?: return@forEachIndexed
        val command =
            if (reverseDirection) {
                hintedCommand.invertedForReverseRoute()
            } else {
                hintedCommand
            }
        val distanceAtPoint = cumulativeDistancesMeters.getOrNull(index) ?: return@forEachIndexed
        if (command != TrailGuidanceCommand.FINISH &&
            distanceAtPoint - lastInstructionDistance < tuning.minInstructionSpacingMeters
        ) {
            return@forEachIndexed
        }
        val message =
            if (reverseDirection && command != hintedCommand) {
                command.message
            } else {
                hint.message?.toGuidanceMessage() ?: command.message
            }
        instructions +=
            TrailGuidanceInstruction(
                command = command,
                message = message,
                location = point.location,
                trackPointIndex = index,
                distanceFromStartMeters = distanceAtPoint,
                turnAngleDegrees = null,
                source = TrailGuidanceInstructionSource.BROUTER_HINT,
            )
        lastInstructionDistance = distanceAtPoint
    }

    if (instructions.isEmpty()) return emptyList()

    val hasFinish = instructions.any { it.command == TrailGuidanceCommand.FINISH }
    if (!hasFinish) {
        val points = trackPoints.map { it.location }
        instructions +=
            TrailGuidanceInstruction(
                command = TrailGuidanceCommand.FINISH,
                message = TrailGuidanceCommand.FINISH.message,
                location = points.last(),
                trackPointIndex = points.lastIndex,
                distanceFromStartMeters = cumulativeDistancesMeters.lastOrNull() ?: 0.0,
                turnAngleDegrees = null,
                source = TrailGuidanceInstructionSource.BROUTER_HINT,
            )
    }
    return instructions
}

private fun routeInstructionCommandForHint(commandCode: String?): TrailGuidanceCommand? {
    val normalized =
        commandCode
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
    return when {
        normalized == "TSHL" || normalized == "TLU" -> TrailGuidanceCommand.SHARP_LEFT
        normalized == "TL" || normalized == "KL" || normalized == "EL" -> TrailGuidanceCommand.LEFT
        normalized == "TSLL" -> TrailGuidanceCommand.SLIGHT_LEFT
        normalized == "TSLR" -> TrailGuidanceCommand.SLIGHT_RIGHT
        normalized == "TR" || normalized == "KR" || normalized == "ER" -> TrailGuidanceCommand.RIGHT
        normalized == "TSHR" || normalized == "TRU" -> TrailGuidanceCommand.SHARP_RIGHT
        normalized == "C" -> TrailGuidanceCommand.CONTINUE
        normalized == "END" -> TrailGuidanceCommand.FINISH
        normalized.startsWith("RNL") -> TrailGuidanceCommand.LEFT
        normalized.startsWith("RND") -> TrailGuidanceCommand.RIGHT
        normalized == "TU" -> TrailGuidanceCommand.SHARP_LEFT
        normalized == "BL" || normalized == "OFFR" -> null
        else -> null
    }
}

private fun TrailGuidanceCommand.invertedForReverseRoute(): TrailGuidanceCommand =
    when (this) {
        TrailGuidanceCommand.SLIGHT_LEFT -> TrailGuidanceCommand.SLIGHT_RIGHT
        TrailGuidanceCommand.LEFT -> TrailGuidanceCommand.RIGHT
        TrailGuidanceCommand.SHARP_LEFT -> TrailGuidanceCommand.SHARP_RIGHT
        TrailGuidanceCommand.SLIGHT_RIGHT -> TrailGuidanceCommand.SLIGHT_LEFT
        TrailGuidanceCommand.RIGHT -> TrailGuidanceCommand.LEFT
        TrailGuidanceCommand.SHARP_RIGHT -> TrailGuidanceCommand.SHARP_LEFT
        TrailGuidanceCommand.CONTINUE,
        TrailGuidanceCommand.FINISH,
        -> this
    }

private fun String.toGuidanceMessage(): String =
    trim()
        .replace('_', ' ')
        .lowercase(Locale.ROOT)
        .replaceFirstChar { char -> char.titlecase(Locale.ROOT) }

fun projectLocationToRoute(
    points: List<GeoPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeDistances(points),
    location: GeoPoint,
    previousDistanceFromStartMeters: Double? = null,
): TrailGuidanceProjection? =
    projectPointOntoRoute(
        points = points,
        cumulativeDistancesMeters = cumulativeDistancesMeters,
        point = location,
        previousDistanceFromStartMeters = previousDistanceFromStartMeters,
    )?.let { projection ->
        TrailGuidanceProjection(
            segmentIndex = projection.segmentIndex,
            t = projection.segmentFraction,
            distanceFromStartMeters = projection.distanceFromStartMeters,
            distanceToRouteMeters = projection.distanceToRouteMeters,
        )
    }

/** Projects onto continuous GPX segments independently, without inventing a line across gaps. */
fun projectTrailLocationToRoute(
    trackPoints: List<TrailPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeTrailDistances(trackPoints),
    location: GeoPoint,
    previousDistanceFromStartMeters: Double? = null,
): TrailGuidanceProjection? {
    if (trackPoints.size < 2 || cumulativeDistancesMeters.size != trackPoints.size) return null
    val previous = previousDistanceFromStartMeters?.takeIf { it.isFinite() && it >= 0.0 }
    val candidates = mutableListOf<TrailGuidanceProjection>()
    var rangeStart = 0
    for (index in 1..trackPoints.size) {
        val endsRange = index == trackPoints.size || trackPoints[index].startsNewSegment
        if (!endsRange) continue
        val rangeEnd = index - 1
        if (rangeEnd > rangeStart) {
            val points = trackPoints.subList(rangeStart, rangeEnd + 1).map(TrailPoint::location)
            val localDistances = buildCumulativeDistancesMeters(points)
            val offset = cumulativeDistancesMeters[rangeStart]
            val localPrevious = previous?.minus(offset)?.takeIf { it >= 0.0 && it <= localDistances.last() }
            val projection =
                projectPointOntoRoute(
                    points = points,
                    cumulativeDistancesMeters = localDistances,
                    point = location,
                    previousDistanceFromStartMeters = localPrevious,
                )
            if (projection != null) {
                candidates +=
                    TrailGuidanceProjection(
                        segmentIndex = rangeStart + projection.segmentIndex,
                        t = projection.segmentFraction,
                        distanceFromStartMeters = offset + projection.distanceFromStartMeters,
                        distanceToRouteMeters = projection.distanceToRouteMeters,
                    )
            }
        }
        rangeStart = index
    }
    val nearest = candidates.minByOrNull(TrailGuidanceProjection::distanceToRouteMeters) ?: return null
    if (previous == null) return nearest
    val config = RouteProjectionConfig()
    val continuityCandidate =
        candidates
            .asSequence()
            .filter {
                it.distanceFromStartMeters >= previous - config.maximumBacktrackMeters &&
                    it.distanceFromStartMeters <= previous + config.maximumForwardJumpMeters
            }.minByOrNull { candidate ->
                candidate.distanceToRouteMeters +
                    abs(candidate.distanceFromStartMeters - previous) * config.progressPenalty
            }
    return if (
        continuityCandidate != null &&
        continuityCandidate.distanceToRouteMeters <= nearest.distanceToRouteMeters + config.relockAdvantageMeters
    ) {
        continuityCandidate
    } else {
        nearest
    }
}

fun updateGuidanceOffRouteConfirmation(
    previous: TrailGuidanceOffRouteConfirmationState,
    distanceToRouteMeters: Double?,
    thresholdMeters: Double,
    allowOffRouteEntry: Boolean,
): TrailGuidanceOffRouteConfirmationState {
    val distance = distanceToRouteMeters?.takeIf { it.isFinite() && it >= 0.0 } ?: return previous

    if (previous.offRoute) {
        val strongRecovery =
            distance <= thresholdMeters * OFF_ROUTE_STRONG_RECOVERY_THRESHOLD_FRACTION
        if (strongRecovery) {
            return TrailGuidanceOffRouteConfirmationState()
        }
        val nextRecoveryCount =
            if (distance <= thresholdMeters) {
                previous.recoverySampleCount + 1
            } else {
                0
            }
        return if (nextRecoveryCount >= OFF_ROUTE_RECOVERY_SAMPLE_COUNT) {
            TrailGuidanceOffRouteConfirmationState()
        } else {
            previous.copy(
                outsideSampleCount = 0,
                recoverySampleCount = nextRecoveryCount,
            )
        }
    }

    if (!allowOffRouteEntry) {
        return previous.copy(outsideSampleCount = 0, recoverySampleCount = 0)
    }
    val nextOutsideCount =
        if (distance > thresholdMeters) {
            previous.outsideSampleCount + 1
        } else {
            0
        }
    return if (nextOutsideCount >= OFF_ROUTE_ENTRY_SAMPLE_COUNT) {
        TrailGuidanceOffRouteConfirmationState(offRoute = true)
    } else {
        previous.copy(outsideSampleCount = nextOutsideCount, recoverySampleCount = 0)
    }
}

@Suppress("ktlint:standard:function-expression-body")
fun buildCumulativeTrailDistances(points: List<TrailPoint>): List<Double> {
    if (points.isEmpty()) return emptyList()
    val cumulative = MutableList(points.size) { 0.0 }
    for (index in 0 until points.lastIndex) {
        cumulative[index + 1] =
            cumulative[index] +
            if (points[index + 1].startsNewSegment) {
                0.0
            } else {
                haversineDistanceMeters(points[index].location, points[index + 1].location)
            }
    }
    return cumulative
}

fun buildCumulativeDistances(points: List<GeoPoint>): List<Double> = buildCumulativeDistancesMeters(points)

private fun indexAtOrBeforeDistance(
    cumulativeDistancesMeters: List<Double>,
    target: Double,
): Int {
    var result = 0
    for (index in cumulativeDistancesMeters.indices) {
        if (cumulativeDistancesMeters[index] <= target) {
            result = index
        } else {
            break
        }
    }
    return result
}

private fun indexAtOrAfterDistance(
    cumulativeDistancesMeters: List<Double>,
    target: Double,
): Int {
    for (index in cumulativeDistancesMeters.indices) {
        if (cumulativeDistancesMeters[index] >= target) return index
    }
    return cumulativeDistancesMeters.lastIndex.coerceAtLeast(0)
}

private fun commandForTurn(deltaDegrees: Double): TrailGuidanceCommand =
    when {
        deltaDegrees <= -110.0 -> TrailGuidanceCommand.SHARP_LEFT
        deltaDegrees <= -45.0 -> TrailGuidanceCommand.LEFT
        deltaDegrees <= -25.0 -> TrailGuidanceCommand.SLIGHT_LEFT
        deltaDegrees >= 110.0 -> TrailGuidanceCommand.SHARP_RIGHT
        deltaDegrees >= 45.0 -> TrailGuidanceCommand.RIGHT
        deltaDegrees >= 25.0 -> TrailGuidanceCommand.SLIGHT_RIGHT
        else -> TrailGuidanceCommand.CONTINUE
    }

private const val OFF_ROUTE_ENTRY_SAMPLE_COUNT = 2
private const val OFF_ROUTE_RECOVERY_SAMPLE_COUNT = 2
private const val OFF_ROUTE_STRONG_RECOVERY_THRESHOLD_FRACTION = 0.5

val TrailGuidanceCommand.message: String
    get() =
        when (this) {
            TrailGuidanceCommand.CONTINUE -> "Continue"
            TrailGuidanceCommand.SLIGHT_LEFT -> "Slight left"
            TrailGuidanceCommand.LEFT -> "Left"
            TrailGuidanceCommand.SHARP_LEFT -> "Sharp left"
            TrailGuidanceCommand.SLIGHT_RIGHT -> "Slight right"
            TrailGuidanceCommand.RIGHT -> "Right"
            TrailGuidanceCommand.SHARP_RIGHT -> "Sharp right"
            TrailGuidanceCommand.FINISH -> "Finish"
        }

private fun normalizeSignedDegrees(value: Double): Double {
    var normalized = value % 360.0
    if (normalized > 180.0) normalized -= 360.0
    if (normalized < -180.0) normalized += 360.0
    return normalized
}

fun bearingDegrees(
    from: GeoPoint,
    to: GeoPoint,
): Double = initialBearingDegrees(from, to)

fun haversineMeters(
    a: GeoPoint,
    b: GeoPoint,
): Double = haversineDistanceMeters(a, b)

/** Reverses a route while keeping every original segment break attached to its new start. */
fun reverseTrailPoints(points: List<TrailPoint>): List<TrailPoint> =
    points.asReversed().mapIndexed { reversedIndex, point ->
        val originalIndex = points.lastIndex - reversedIndex
        point.copy(
            startsNewSegment =
                reversedIndex == 0 || points.getOrNull(originalIndex + 1)?.startsNewSegment == true,
        )
    }
