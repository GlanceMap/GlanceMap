package com.glancemap.trailcore.guidance

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint

const val TRAIL_GUIDANCE_ROUTE_START_NEAREST_POINT = "NEAREST_POINT"
const val TRAIL_GUIDANCE_ROUTE_START_ASK = "ASK"
const val TRAIL_GUIDANCE_REVERSE_SUGGESTION_ASK = "ASK"

enum class TrailGuidanceStartDecision {
    REVERSE_ROUTE,
    START_HERE,
}

data class TrailGuidanceStartDecisionResult(
    val stableMidRouteSampleCount: Int,
    val markNearestPointStartReached: Boolean = false,
    val decision: TrailGuidanceStartDecision? = null,
)

/** Applies the watch's confirmed nearest-start and reverse-route policy to a usable location fix. */
fun evaluateTrailGuidanceStartDecision(
    session: TrailGuidanceSession,
    location: GeoPoint?,
    locationAccuracyMeters: Float?,
    hasFreshLocationFix: Boolean,
    previousStableMidRouteSampleCount: Int,
    routeStartBehavior: String,
    reverseSuggestionMode: String,
    tuning: TrailGuidanceTuning = TrailGuidanceTuning(),
): TrailGuidanceStartDecisionResult {
    if (location == null || session.startReached) return TrailGuidanceStartDecisionResult(0)
    val points = session.trackPoints.map(TrailPoint::location)
    val start = points.firstOrNull() ?: return TrailGuidanceStartDecisionResult(0)
    val end = points.lastOrNull() ?: return TrailGuidanceStartDecisionResult(0)
    val projection =
        projectTrailLocationToRoute(
            trackPoints = session.trackPoints,
            cumulativeDistancesMeters = session.cumulativeDistancesMeters,
            location = location,
        ) ?: return TrailGuidanceStartDecisionResult(0)
    val distanceToStart = haversineMeters(location, start)
    val distanceToEnd = haversineMeters(location, end)
    val closeToRoute = projection.distanceToRouteMeters <= tuning.offRouteDistanceMeters
    val midRouteCandidate =
        closeToRoute &&
            distanceToStart > tuning.startReachedDistanceMeters &&
            projection.distanceFromStartMeters > START_HERE_MIN_PROGRESS_METERS &&
            session.totalDistanceMeters - projection.distanceFromStartMeters > START_HERE_MIN_REMAINING_METERS
    val accurateEnough = locationAccuracyMeters?.let { it <= START_HERE_MAX_ACCURACY_METERS } == true
    val nextStableCount =
        if (midRouteCandidate && accurateEnough) {
            previousStableMidRouteSampleCount + 1
        } else {
            0
        }
    val stableMidRouteCandidate = midRouteCandidate && hasFreshLocationFix && nextStableCount >= START_HERE_STABLE_SAMPLE_COUNT
    val reverseCandidate =
        !session.reversed &&
            reverseSuggestionMode == TRAIL_GUIDANCE_REVERSE_SUGGESTION_ASK &&
            distanceToEnd + REVERSE_SUGGESTION_DISTANCE_MARGIN_METERS < distanceToStart &&
            distanceToEnd <= REVERSE_SUGGESTION_MAX_DISTANCE_METERS
    return when {
        reverseCandidate -> TrailGuidanceStartDecisionResult(nextStableCount, decision = TrailGuidanceStartDecision.REVERSE_ROUTE)
        stableMidRouteCandidate && routeStartBehavior == TRAIL_GUIDANCE_ROUTE_START_NEAREST_POINT ->
            TrailGuidanceStartDecisionResult(nextStableCount, markNearestPointStartReached = true)
        stableMidRouteCandidate && routeStartBehavior == TRAIL_GUIDANCE_ROUTE_START_ASK ->
            TrailGuidanceStartDecisionResult(nextStableCount, decision = TrailGuidanceStartDecision.START_HERE)
        else -> TrailGuidanceStartDecisionResult(nextStableCount)
    }
}

const val START_HERE_MIN_PROGRESS_METERS = 50.0
const val START_HERE_MIN_REMAINING_METERS = 50.0
const val START_HERE_STABLE_SAMPLE_COUNT = 2
const val START_HERE_MAX_ACCURACY_METERS = 60f
const val REVERSE_SUGGESTION_DISTANCE_MARGIN_METERS = 50.0
const val REVERSE_SUGGESTION_MAX_DISTANCE_METERS = 300.0
