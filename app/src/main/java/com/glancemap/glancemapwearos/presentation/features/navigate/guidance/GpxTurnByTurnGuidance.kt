package com.glancemap.glancemapwearos.presentation.features.navigate.guidance

import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxGuidanceHintSource
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.geo.initialBearingDegrees
import com.glancemap.trailcore.guidance.TrailGuidanceCommand
import com.glancemap.trailcore.guidance.TrailGuidanceInstruction
import com.glancemap.trailcore.guidance.TrailGuidanceInstructionSource
import com.glancemap.trailcore.guidance.TrailGuidanceMode
import com.glancemap.trailcore.guidance.TrailGuidanceOffRouteConfirmationState
import com.glancemap.trailcore.guidance.TrailGuidanceSession
import com.glancemap.trailcore.guidance.TrailGuidanceState
import com.glancemap.trailcore.guidance.TrailGuidanceTerrainConfirmation
import com.glancemap.trailcore.guidance.TrailGuidanceTerrainDirection
import com.glancemap.trailcore.guidance.TrailGuidanceTerrainPreview
import com.glancemap.trailcore.guidance.TrailGuidanceTuning
import com.glancemap.trailcore.guidance.buildCumulativeDistances
import com.glancemap.trailcore.guidance.buildCumulativeTrailDistances
import com.glancemap.trailcore.guidance.buildTrailGuidanceSession
import com.glancemap.trailcore.guidance.computeTrailGuidanceState
import com.glancemap.trailcore.guidance.deriveGpxTrailGuidanceInstructions
import com.glancemap.trailcore.guidance.deriveHintedTrailGuidanceInstructions
import com.glancemap.trailcore.profile.TrailGuidanceHint
import com.glancemap.trailcore.profile.TrailGuidanceHintSource
import com.glancemap.trailcore.profile.TrailPoint
import org.mapsforge.core.model.LatLong
import com.glancemap.trailcore.guidance.isGuidanceStartReached as trailGuidanceStartReached
import com.glancemap.trailcore.guidance.message as trailGuidanceCommandMessage
import com.glancemap.trailcore.guidance.projectLocationToRoute as trailProjectLocationToRoute
import com.glancemap.trailcore.guidance.updateGuidanceOffRouteConfirmation as trailUpdateOffRouteConfirmation

typealias RouteInstructionCommand = TrailGuidanceCommand
typealias RouteInstructionSource = TrailGuidanceInstructionSource
typealias GuidanceMode = TrailGuidanceMode
typealias GuidanceTerrainDirection = TrailGuidanceTerrainDirection
typealias GuidanceTerrainPreview = TrailGuidanceTerrainPreview
typealias GuidanceTerrainConfirmation = TrailGuidanceTerrainConfirmation
typealias GpxGuidanceTuning = TrailGuidanceTuning

/** Keeps the watch-facing command API source-compatible while sharing the policy text. */
val TrailGuidanceCommand.message: String
    get() = this.trailGuidanceCommandMessage

data class RouteInstruction(
    val command: TrailGuidanceCommand,
    val message: String? = null,
    @StringRes val messageRes: Int = command.messageRes,
    val latLong: LatLong,
    val trackPointIndex: Int,
    val distanceFromStartMeters: Double,
    val turnAngleDegrees: Float?,
    val source: TrailGuidanceInstructionSource = TrailGuidanceInstructionSource.GPX_GEOMETRY,
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
    val routeOffsetMeters: Double = 0.0,
    val cumulativeAscentMeters: List<Double> = emptyList(),
    val cumulativeDescentMeters: List<Double> = emptyList(),
)

data class TurnByTurnGuidanceState(
    val active: Boolean,
    val mode: TrailGuidanceMode,
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
    val nextSegmentTerrain: TrailGuidanceTerrainPreview? = null,
    val recentManeuverTerrain: TrailGuidanceTerrainConfirmation? = null,
    val alertSessionKey: String? = null,
    val alertGpsDeliveryIntervalMs: Long? = null,
    val projectionSegmentsScanned: Int? = null,
    val originalRouteDistanceFromStartMeters: Double? = null,
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

@Suppress("LongParameterList")
fun buildGpxGuidanceSession(
    trackId: String,
    trackTitle: String,
    trackPoints: List<TrackPoint>,
    startReached: Boolean = false,
    reversed: Boolean = false,
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
    cumulativeDistancesMeters: List<Double>? = null,
): GpxGuidanceSession =
    buildTrailGuidanceSession(
        trackId = trackId,
        trackTitle = trackTitle,
        trackPoints = trackPoints.map(TrackPoint::toTrailPoint),
        startReached = startReached,
        reversed = reversed,
        tuning = tuning,
        cumulativeDistancesMeters = cumulativeDistancesMeters,
    ).toWatchSession(trackPoints)

fun computeTurnByTurnGuidanceState(
    session: GpxGuidanceSession?,
    currentLocation: LatLong?,
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
    previousDistanceFromStartMeters: Double? = null,
): TurnByTurnGuidanceState =
    computeTrailGuidanceState(
        session = session?.toTrailSession(),
        currentLocation = currentLocation?.toGeoPoint(),
        tuning = tuning,
        previousDistanceFromStartMeters = previousDistanceFromStartMeters,
    ).toWatchState(session)

fun isGuidanceStartReached(
    session: GpxGuidanceSession?,
    currentLocation: LatLong?,
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
): Boolean =
    trailGuidanceStartReached(
        session = session?.toTrailSession(),
        currentLocation = currentLocation?.toGeoPoint(),
        tuning = tuning,
    )

fun deriveGpxRouteInstructions(
    trackPoints: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeTrailDistances(trackPoints.map(TrackPoint::toTrailPoint)),
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
): List<RouteInstruction> =
    deriveGpxTrailGuidanceInstructions(
        trackPoints.map(TrackPoint::toTrailPoint),
        cumulativeDistancesMeters,
        tuning,
    ).map { it.toWatchInstruction() }

fun deriveHintedRouteInstructions(
    trackPoints: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeTrailDistances(trackPoints.map(TrackPoint::toTrailPoint)),
    tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
    reverseDirection: Boolean = false,
): List<RouteInstruction> =
    deriveHintedTrailGuidanceInstructions(
        trackPoints.map(TrackPoint::toTrailPoint),
        cumulativeDistancesMeters,
        tuning,
        reverseDirection,
    ).map { it.toWatchInstruction(trackPoints, reverseDirection) }

fun projectLocationToRoute(
    points: List<LatLong>,
    cumulativeDistancesMeters: List<Double> = buildCumulativeDistances(points.map(LatLong::toGeoPoint)),
    location: LatLong,
    previousDistanceFromStartMeters: Double? = null,
): GuidanceProjection? =
    trailProjectLocationToRoute(
        points = points.map(LatLong::toGeoPoint),
        cumulativeDistancesMeters = cumulativeDistancesMeters,
        location = location.toGeoPoint(),
        previousDistanceFromStartMeters = previousDistanceFromStartMeters,
    )?.let { projection ->
        GuidanceProjection(
            segmentIndex = projection.segmentIndex,
            t = projection.t,
            distanceFromStartMeters = projection.distanceFromStartMeters,
            distanceToRouteMeters = projection.distanceToRouteMeters,
        )
    }

fun updateGuidanceOffRouteConfirmation(
    previous: GuidanceOffRouteConfirmationState,
    distanceToRouteMeters: Double?,
    thresholdMeters: Double,
    allowOffRouteEntry: Boolean,
): GuidanceOffRouteConfirmationState =
    trailUpdateOffRouteConfirmation(
        previous =
            TrailGuidanceOffRouteConfirmationState(
                offRoute = previous.offRoute,
                outsideSampleCount = previous.outsideSampleCount,
                recoverySampleCount = previous.recoverySampleCount,
            ),
        distanceToRouteMeters = distanceToRouteMeters,
        thresholdMeters = thresholdMeters,
        allowOffRouteEntry = allowOffRouteEntry,
    ).let { state ->
        GuidanceOffRouteConfirmationState(
            offRoute = state.offRoute,
            outsideSampleCount = state.outsideSampleCount,
            recoverySampleCount = state.recoverySampleCount,
        )
    }

fun buildCumulativeDistances(
    points: List<LatLong>,
): List<Double> = buildCumulativeDistances(points.map(LatLong::toGeoPoint))

fun bearingDegrees(
    from: LatLong,
    to: LatLong,
): Double = initialBearingDegrees(from.toGeoPoint(), to.toGeoPoint())

fun haversineMeters(
    a: LatLong,
    b: LatLong,
): Double = haversineDistanceMeters(a.toGeoPoint(), b.toGeoPoint())

internal fun GpxGuidanceSession.toTrailSession(): TrailGuidanceSession =
    TrailGuidanceSession(
        trackId = trackId,
        trackTitle = trackTitle,
        trackPoints = trackPoints.map(TrackPoint::toTrailPoint),
        cumulativeDistancesMeters = cumulativeDistancesMeters,
        totalDistanceMeters = totalDistanceMeters,
        instructions = instructions.map(RouteInstruction::toTrailInstruction),
        startReached = startReached,
        reversed = reversed,
        routeOffsetMeters = routeOffsetMeters,
        cumulativeAscentMeters = cumulativeAscentMeters,
        cumulativeDescentMeters = cumulativeDescentMeters,
    )

private fun TrailGuidanceSession.toWatchSession(originalPoints: List<TrackPoint>): GpxGuidanceSession =
    GpxGuidanceSession(
        trackId = trackId,
        trackTitle = trackTitle,
        trackPoints = originalPoints,
        cumulativeDistancesMeters = cumulativeDistancesMeters,
        totalDistanceMeters = totalDistanceMeters,
        instructions = instructions.map { it.toWatchInstruction(originalPoints, reversed) },
        startReached = startReached,
        reversed = reversed,
        routeOffsetMeters = routeOffsetMeters,
        cumulativeAscentMeters = cumulativeAscentMeters,
        cumulativeDescentMeters = cumulativeDescentMeters,
    )

private fun TrackPoint.toTrailPoint(): TrailPoint =
    TrailPoint(
        location = latLong.toGeoPoint(),
        elevationMeters = elevation,
        startsNewSegment = startsNewSegment,
        guidanceHint =
            guidanceHint?.let { hint ->
                TrailGuidanceHint(
                    commandCode = hint.commandCode,
                    message = hint.message,
                    source =
                        when (hint.source) {
                            GpxGuidanceHintSource.BROUTER -> TrailGuidanceHintSource.BROUTER
                            GpxGuidanceHintSource.GPX_SYMBOL -> TrailGuidanceHintSource.GPX_SYMBOL
                        },
                )
            },
    )

private fun TrailGuidanceInstruction.toWatchInstruction(
    points: List<TrackPoint>? = null,
    reversed: Boolean = false,
): RouteInstruction =
    RouteInstruction(
        command = command,
        message =
            message.takeIf {
                source == TrailGuidanceInstructionSource.BROUTER_HINT &&
                    points?.getOrNull(trackPointIndex)?.guidanceHint?.message != null &&
                    !(reversed && command != TrailGuidanceCommand.CONTINUE && command != TrailGuidanceCommand.FINISH)
            },
        latLong = location.toLatLong(),
        trackPointIndex = trackPointIndex,
        distanceFromStartMeters = distanceFromStartMeters,
        turnAngleDegrees = turnAngleDegrees,
        source = source,
    )

private fun RouteInstruction.toTrailInstruction(): TrailGuidanceInstruction =
    TrailGuidanceInstruction(
        command = command,
        message = message ?: command.message,
        location = latLong.toGeoPoint(),
        trackPointIndex = trackPointIndex,
        distanceFromStartMeters = distanceFromStartMeters,
        turnAngleDegrees = turnAngleDegrees,
        source = source,
    )

private fun TrailGuidanceState.toWatchState(session: GpxGuidanceSession?): TurnByTurnGuidanceState =
    TurnByTurnGuidanceState(
        active = active,
        mode = mode,
        trackTitle = trackTitle,
        nextInstruction = nextInstruction?.toWatchInstruction(session?.trackPoints, session?.reversed == true),
        distanceToInstructionMeters = distanceToInstructionMeters,
        distanceToStartMeters = distanceToStartMeters,
        bearingToStartDegrees = bearingToStartDegrees,
        distanceToRouteMeters = distanceToRouteMeters,
        bearingToRouteDegrees = bearingToRouteDegrees,
        distanceRemainingMeters = distanceRemainingMeters,
        routeProgressFraction = routeProgressFraction,
        offRoute = offRoute,
        distanceFromStartMeters = distanceFromStartMeters,
        followingInstruction =
            followingInstruction?.toWatchInstruction(session?.trackPoints, session?.reversed == true),
        distanceToFollowingInstructionMeters = distanceToFollowingInstructionMeters,
        estimatedRemainingSeconds = estimatedRemainingSeconds,
        remainingAscentMeters = remainingAscentMeters,
        remainingDescentMeters = remainingDescentMeters,
        currentAltitudeMeters = currentAltitudeMeters,
        nextSegmentTerrain = nextSegmentTerrain,
        recentManeuverTerrain = recentManeuverTerrain,
        alertSessionKey = alertSessionKey,
        alertGpsDeliveryIntervalMs = alertGpsDeliveryIntervalMs,
        projectionSegmentsScanned = projectionSegmentsScanned,
        originalRouteDistanceFromStartMeters = originalRouteDistanceFromStartMeters,
    )

private fun LatLong.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

private fun GeoPoint.toLatLong(): LatLong = LatLong(latitude, longitude)

@get:StringRes
val RouteInstructionCommand.messageRes: Int
    get() =
        when (this) {
            RouteInstructionCommand.CONTINUE -> R.string.common_continue
            RouteInstructionCommand.SLIGHT_LEFT -> R.string.navigation_slight_left
            RouteInstructionCommand.LEFT -> R.string.navigation_left
            RouteInstructionCommand.SHARP_LEFT -> R.string.navigation_sharp_left
            RouteInstructionCommand.SLIGHT_RIGHT -> R.string.navigation_slight_right
            RouteInstructionCommand.RIGHT -> R.string.navigation_right
            RouteInstructionCommand.SHARP_RIGHT -> R.string.navigation_sharp_right
            RouteInstructionCommand.FINISH -> R.string.navigation_finish
        }
