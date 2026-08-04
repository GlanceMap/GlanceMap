package com.glancemap.glancemapwearos.presentation.features.navigate.guidance

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.buildCumulativeDistancesMeters
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.geo.initialBearingDegrees
import com.glancemap.trailcore.geo.projectPointOntoRoute
import org.mapsforge.core.model.LatLong
import java.util.Locale
import kotlin.math.abs

enum class RouteInstructionCommand {
    CONTINUE,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    FINISH,
}

enum class RouteInstructionSource {
    GPX_GEOMETRY,
    BROUTER_HINT,
}

data class RouteInstruction(
    val command: RouteInstructionCommand,
    val message: String,
    val latLong: LatLong,
    val trackPointIndex: Int,
    val distanceFromStartMeters: Double,
    val turnAngleDegrees: Float?,
    val source: RouteInstructionSource = RouteInstructionSource.GPX_GEOMETRY,
)

data class GpxGuidanceSession(
    val trackId: String,
    val trackTitle: String,
    val trackPoints: List<TrackPoint>,
    val cumulativeDistancesMeters: List<Double>,
    val totalDistanceMeters: Double,
    val instructions: List<RouteInstruction>,
    val startReached: Boolean = false,
    val reversed: Boolean = false,
)

enum class GuidanceMode {
    WAITING_FOR_LOCATION,
    TO_START,
    FOLLOW_ROUTE,
    FINISHED,
}

data class TurnByTurnGuidanceState(
    val active: Boolean,
    val mode: GuidanceMode,
    val trackTitle: String?,
    val nextInstruction: RouteInstruction?,
    val distanceToInstructionMeters: Double?,
    val distanceToStartMeters: Double?,
    val bearingToStartDegrees: Float?,
    val distanceToRouteMeters: Double?,
    val bearingToRouteDegrees: Float?,
    val distanceRemainingMeters: Double?,
    val routeProgressFraction: Float?,
    val offRoute: Boolean,
    val distanceFromStartMeters: Double? = null,
    val followingInstruction: RouteInstruction? = null,
    val distanceToFollowingInstructionMeters: Double? = null,
    val estimatedRemainingSeconds: Long? = null,
    val remainingAscentMeters: Double? = null,
    val remainingDescentMeters: Double? = null,
    val currentAltitudeMeters: Double? = null,
    val alertSessionKey: String? = null,
    val alertGpsDeliveryIntervalMs: Long? = null,
)

data class GuidanceProjection(
    val segmentIndex: Int,
    val t: Double,
    val distanceFromStartMeters: Double,
    val distanceToRouteMeters: Double,
)

data class GuidanceOffRouteConfirmationState(
    val offRoute: Boolean = false,
    val outsideSampleCount: Int = 0,
    val recoverySampleCount: Int = 0,
)

data class GpxGuidanceTuning(
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

fun buildGpxGuidanceSession(
    trackId: String,
    trackTitle: String,
    trackPoints: List<TrackPoint>,
    startReached: Boolean = false,
    reversed: Boolean = false,
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
): GpxGuidanceSession {
    require(trackPoints.size >= 2) { "The GPX does not contain enough points for guidance." }
    val cumulative = buildCumulativeDistances(trackPoints.map { it.latLong })
    return GpxGuidanceSession(
        trackId = trackId,
        trackTitle = trackTitle,
        trackPoints = trackPoints,
        cumulativeDistancesMeters = cumulative,
        totalDistanceMeters = cumulative.lastOrNull() ?: 0.0,
        instructions =
            deriveHintedRouteInstructions(
                trackPoints = trackPoints,
                cumulativeDistancesMeters = cumulative,
                tuning = tuning,
                reverseDirection = reversed,
            ).ifEmpty {
                deriveGpxRouteInstructions(
                    trackPoints = trackPoints,
                    cumulativeDistancesMeters = cumulative,
                    tuning = tuning,
                )
            },
        startReached = startReached,
        reversed = reversed,
    )
}

fun computeTurnByTurnGuidanceState(
    session: GpxGuidanceSession?,
    currentLocation: LatLong?,
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
    previousDistanceFromStartMeters: Double? = null,
): TurnByTurnGuidanceState {
    if (session == null) {
        return TurnByTurnGuidanceState(
            active = false,
            mode = GuidanceMode.WAITING_FOR_LOCATION,
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
    val fullRouteElevation = remainingElevationMeters(session, 0.0)

    if (currentLocation == null) {
        return TurnByTurnGuidanceState(
            active = true,
            mode = GuidanceMode.WAITING_FOR_LOCATION,
            trackTitle = session.trackTitle,
            nextInstruction = session.instructions.firstOrNull(),
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
        )
    }

    val start = session.trackPoints.first().latLong
    val distanceToStart = haversineMeters(currentLocation, start)
    if (!session.startReached && distanceToStart > tuning.startReachedDistanceMeters) {
        return TurnByTurnGuidanceState(
            active = true,
            mode = GuidanceMode.TO_START,
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
        )
    }

    val points = session.trackPoints.map { it.latLong }
    val projection =
        projectLocationToRoute(
            points = points,
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
        return TurnByTurnGuidanceState(
            active = true,
            mode = GuidanceMode.FINISHED,
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
            remainingAscentMeters = 0.0,
            remainingDescentMeters = 0.0,
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

    return TurnByTurnGuidanceState(
        active = true,
        mode = GuidanceMode.FOLLOW_ROUTE,
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
    )
}

private fun remainingElevationMeters(
    session: GpxGuidanceSession,
    distanceFromStartMeters: Double,
): Pair<Double?, Double?> {
    val points = session.trackPoints
    if (points.count { it.elevation?.isFinite() == true } < 2) return null to null
    var ascent = 0.0
    var descent = 0.0
    for (index in 0 until points.lastIndex) {
        val segmentStart = session.cumulativeDistancesMeters.getOrNull(index) ?: continue
        val segmentEnd = session.cumulativeDistancesMeters.getOrNull(index + 1) ?: continue
        if (segmentEnd <= distanceFromStartMeters) continue
        val from = points[index].elevation?.takeIf(Double::isFinite) ?: continue
        val to = points[index + 1].elevation?.takeIf(Double::isFinite) ?: continue
        val segmentFraction =
            if (distanceFromStartMeters > segmentStart && segmentEnd > segmentStart) {
                ((segmentEnd - distanceFromStartMeters) / (segmentEnd - segmentStart)).coerceIn(0.0, 1.0)
            } else {
                1.0
            }
        val delta = (to - from) * segmentFraction
        if (delta > 0.0) ascent += delta else descent += -delta
    }
    return ascent to descent
}

private fun projectedRoutePoint(
    points: List<LatLong>,
    projection: GuidanceProjection,
): LatLong? {
    val a = points.getOrNull(projection.segmentIndex) ?: return null
    val b = points.getOrNull(projection.segmentIndex + 1) ?: return a
    val t = projection.t.coerceIn(0.0, 1.0)
    return LatLong(
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
    session: GpxGuidanceSession?,
    currentLocation: LatLong?,
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
): Boolean {
    if (session == null || session.startReached || currentLocation == null) return false
    val start = session.trackPoints.firstOrNull()?.latLong ?: return false
    return haversineMeters(currentLocation, start) <= tuning.startReachedDistanceMeters
}

fun deriveGpxRouteInstructions(
    trackPoints: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeDistances(trackPoints.map { it.latLong }),
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
): List<RouteInstruction> {
    if (trackPoints.size < 2) return emptyList()
    val points = trackPoints.map { it.latLong }
    val instructions = mutableListOf<RouteInstruction>()
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

        val incoming = bearingDegrees(points[beforeIndex], points[index])
        val outgoing = bearingDegrees(points[index], points[afterIndex])
        val delta = normalizeSignedDegrees(outgoing - incoming)
        val absDelta = abs(delta)
        if (absDelta < tuning.minInstructionAngleDegrees) continue

        val command = commandForTurn(delta)
        instructions +=
            RouteInstruction(
                command = command,
                message = command.message,
                latLong = points[index],
                trackPointIndex = index,
                distanceFromStartMeters = distanceAtPoint,
                turnAngleDegrees = delta.toFloat(),
            )
        lastInstructionDistance = distanceAtPoint
    }

    instructions +=
        RouteInstruction(
            command = RouteInstructionCommand.FINISH,
            message = RouteInstructionCommand.FINISH.message,
            latLong = points.last(),
            trackPointIndex = points.lastIndex,
            distanceFromStartMeters = cumulativeDistancesMeters.lastOrNull() ?: 0.0,
            turnAngleDegrees = null,
        )
    return instructions
}

fun deriveHintedRouteInstructions(
    trackPoints: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeDistances(trackPoints.map { it.latLong }),
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
    reverseDirection: Boolean = false,
): List<RouteInstruction> {
    if (trackPoints.size < 2) return emptyList()
    val instructions = mutableListOf<RouteInstruction>()
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
        if (command != RouteInstructionCommand.FINISH &&
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
            RouteInstruction(
                command = command,
                message = message,
                latLong = point.latLong,
                trackPointIndex = index,
                distanceFromStartMeters = distanceAtPoint,
                turnAngleDegrees = null,
                source = RouteInstructionSource.BROUTER_HINT,
            )
        lastInstructionDistance = distanceAtPoint
    }

    if (instructions.isEmpty()) return emptyList()

    val hasFinish = instructions.any { it.command == RouteInstructionCommand.FINISH }
    if (!hasFinish) {
        val points = trackPoints.map { it.latLong }
        instructions +=
            RouteInstruction(
                command = RouteInstructionCommand.FINISH,
                message = RouteInstructionCommand.FINISH.message,
                latLong = points.last(),
                trackPointIndex = points.lastIndex,
                distanceFromStartMeters = cumulativeDistancesMeters.lastOrNull() ?: 0.0,
                turnAngleDegrees = null,
                source = RouteInstructionSource.BROUTER_HINT,
            )
    }
    return instructions
}

private fun routeInstructionCommandForHint(commandCode: String?): RouteInstructionCommand? {
    val normalized =
        commandCode
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
    return when {
        normalized == "TSHL" || normalized == "TLU" -> RouteInstructionCommand.SHARP_LEFT
        normalized == "TL" || normalized == "KL" || normalized == "EL" -> RouteInstructionCommand.LEFT
        normalized == "TSLL" -> RouteInstructionCommand.SLIGHT_LEFT
        normalized == "TSLR" -> RouteInstructionCommand.SLIGHT_RIGHT
        normalized == "TR" || normalized == "KR" || normalized == "ER" -> RouteInstructionCommand.RIGHT
        normalized == "TSHR" || normalized == "TRU" -> RouteInstructionCommand.SHARP_RIGHT
        normalized == "C" -> RouteInstructionCommand.CONTINUE
        normalized == "END" -> RouteInstructionCommand.FINISH
        normalized.startsWith("RNL") -> RouteInstructionCommand.LEFT
        normalized.startsWith("RND") -> RouteInstructionCommand.RIGHT
        normalized == "TU" -> RouteInstructionCommand.SHARP_LEFT
        normalized == "BL" || normalized == "OFFR" -> null
        else -> null
    }
}

private fun RouteInstructionCommand.invertedForReverseRoute(): RouteInstructionCommand =
    when (this) {
        RouteInstructionCommand.SLIGHT_LEFT -> RouteInstructionCommand.SLIGHT_RIGHT
        RouteInstructionCommand.LEFT -> RouteInstructionCommand.RIGHT
        RouteInstructionCommand.SHARP_LEFT -> RouteInstructionCommand.SHARP_RIGHT
        RouteInstructionCommand.SLIGHT_RIGHT -> RouteInstructionCommand.SLIGHT_LEFT
        RouteInstructionCommand.RIGHT -> RouteInstructionCommand.LEFT
        RouteInstructionCommand.SHARP_RIGHT -> RouteInstructionCommand.SHARP_LEFT
        RouteInstructionCommand.CONTINUE,
        RouteInstructionCommand.FINISH,
        -> this
    }

private fun String.toGuidanceMessage(): String =
    trim()
        .replace('_', ' ')
        .lowercase(Locale.ROOT)
        .replaceFirstChar { char -> char.titlecase(Locale.ROOT) }

fun projectLocationToRoute(
    points: List<LatLong>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeDistances(points),
    location: LatLong,
    previousDistanceFromStartMeters: Double? = null,
): GuidanceProjection? {
    val projection =
        projectPointOntoRoute(
            points = points.map(LatLong::toGeoPoint),
            cumulativeDistancesMeters = cumulativeDistancesMeters,
            point = location.toGeoPoint(),
            previousDistanceFromStartMeters = previousDistanceFromStartMeters,
        ) ?: return null
    return GuidanceProjection(
        segmentIndex = projection.segmentIndex,
        t = projection.segmentFraction,
        distanceFromStartMeters = projection.distanceFromStartMeters,
        distanceToRouteMeters = projection.distanceToRouteMeters,
    )
}

fun updateGuidanceOffRouteConfirmation(
    previous: GuidanceOffRouteConfirmationState,
    distanceToRouteMeters: Double?,
    locationAccuracyMeters: Float?,
    thresholdMeters: Double,
    allowOffRouteEntry: Boolean,
): GuidanceOffRouteConfirmationState {
    val distance = distanceToRouteMeters?.takeIf { it.isFinite() && it >= 0.0 } ?: return previous

    if (previous.offRoute) {
        val strongRecovery =
            distance <= thresholdMeters * OFF_ROUTE_STRONG_RECOVERY_THRESHOLD_FRACTION
        if (strongRecovery) {
            return GuidanceOffRouteConfirmationState()
        }
        val nextRecoveryCount =
            if (distance <= thresholdMeters) {
                previous.recoverySampleCount + 1
            } else {
                0
            }
        return if (nextRecoveryCount >= OFF_ROUTE_RECOVERY_SAMPLE_COUNT) {
            GuidanceOffRouteConfirmationState()
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
        GuidanceOffRouteConfirmationState(offRoute = true)
    } else {
        previous.copy(outsideSampleCount = nextOutsideCount, recoverySampleCount = 0)
    }
}

fun buildCumulativeDistances(points: List<LatLong>): List<Double> {
    return buildCumulativeDistancesMeters(points.map(LatLong::toGeoPoint))
}

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

private fun commandForTurn(deltaDegrees: Double): RouteInstructionCommand =
    when {
        deltaDegrees <= -110.0 -> RouteInstructionCommand.SHARP_LEFT
        deltaDegrees <= -45.0 -> RouteInstructionCommand.LEFT
        deltaDegrees <= -25.0 -> RouteInstructionCommand.SLIGHT_LEFT
        deltaDegrees >= 110.0 -> RouteInstructionCommand.SHARP_RIGHT
        deltaDegrees >= 45.0 -> RouteInstructionCommand.RIGHT
        deltaDegrees >= 25.0 -> RouteInstructionCommand.SLIGHT_RIGHT
        else -> RouteInstructionCommand.CONTINUE
    }

private const val OFF_ROUTE_ENTRY_SAMPLE_COUNT = 2
private const val OFF_ROUTE_RECOVERY_SAMPLE_COUNT = 2
private const val OFF_ROUTE_STRONG_RECOVERY_THRESHOLD_FRACTION = 0.5

val RouteInstructionCommand.message: String
    get() =
        when (this) {
            RouteInstructionCommand.CONTINUE -> "Continue"
            RouteInstructionCommand.SLIGHT_LEFT -> "Slight left"
            RouteInstructionCommand.LEFT -> "Left"
            RouteInstructionCommand.SHARP_LEFT -> "Sharp left"
            RouteInstructionCommand.SLIGHT_RIGHT -> "Slight right"
            RouteInstructionCommand.RIGHT -> "Right"
            RouteInstructionCommand.SHARP_RIGHT -> "Sharp right"
            RouteInstructionCommand.FINISH -> "Finish"
        }

private fun normalizeSignedDegrees(value: Double): Double {
    var normalized = value % 360.0
    if (normalized > 180.0) normalized -= 360.0
    if (normalized < -180.0) normalized += 360.0
    return normalized
}

fun bearingDegrees(
    from: LatLong,
    to: LatLong,
): Double = initialBearingDegrees(from.toGeoPoint(), to.toGeoPoint())

fun haversineMeters(
    a: LatLong,
    b: LatLong,
): Double = haversineDistanceMeters(a.toGeoPoint(), b.toGeoPoint())

private fun LatLong.toGeoPoint(): GeoPoint = GeoPoint(latitude = latitude, longitude = longitude)
