package com.glancemap.glancemapcompanionapp.hike

import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceProgressDraft
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceRouteDraft
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxTrack
import com.glancemap.glancemapcompanionapp.map.buildPhoneGuidanceSession
import com.glancemap.glancemapcompanionapp.map.toRouteSegments
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.RouteProjectionConfig
import com.glancemap.trailcore.geo.projectPointOntoRoute
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.guidance.buildTrailGuidanceEtaProjection
import com.glancemap.trailcore.profile.TrailPoint
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** Exact GPX content is retained separately from editable planning metadata. */
internal data class PhoneHikeRoute(
    val id: String,
    val trackId: String,
    val title: String,
    val points: List<TrailPoint>,
    val routeOffsetMeters: Double,
    val etaConfig: TrailGuidanceEtaConfig,
)

internal fun phoneHikeRouteId(
    trackId: String,
    points: List<TrailPoint>,
    offsetMeters: Double,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("$trackId:$offsetMeters".toByteArray(Charsets.UTF_8))
    points.forEachIndexed { index, point ->
        // Reversing a route twice marks its first point as a segment start.
        digest.update(
            (
                if (index ==
                    0
                ) {
                    point.copy(startsNewSegment = true)
                } else {
                    point
                }
            ).toString().toByteArray(Charsets.UTF_8),
        )
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class PhoneHikeCheckpoint(
    val id: String,
    val placeId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val segmentIndex: Int,
    val fraction: Double,
    val stopMinutes: Int = 0,
)

internal data class PhoneHikePlan(
    val routeId: String,
    val date: String? = null,
    val startTime: String? = null,
    val zoneId: String? = null,
    val cursorSegmentIndex: Int = 0,
    val cursorFraction: Double = 0.0,
    val inspecting: Boolean = false,
    val checkpoints: List<PhoneHikeCheckpoint> = emptyList(),
) {
    fun departureInstant(fallbackZone: ZoneId): Instant? =
        runCatching {
            LocalDate
                .parse(date)
                .atTime(LocalTime.parse(startTime))
                .atZone(zoneId?.let(ZoneId::of) ?: fallbackZone)
                .toInstant()
        }.getOrNull()
}

internal data class PhoneHikeWorkspaceData(
    val todayId: String? = null,
    val previewId: String? = null,
    val previewSelected: Boolean = false,
    val plans: List<PhoneHikePlan> = emptyList(),
    val todayPlan: PhoneHikePlan? = null,
    val routeTitles: Map<String, String> = emptyMap(),
) {
    val selectedId: String? get() = if (previewSelected) previewId else todayId
    val selectedPlan: PhoneHikePlan? get() =
        selectedId?.let {
            if (previewSelected) {
                plan(
                    it,
                )
            } else {
                todayPlan ?: plan(it)
            }
        }

    fun openPreview(routeId: String): PhoneHikeWorkspaceData = copy(previewId = routeId, previewSelected = true)

    fun useForToday(routeId: String): PhoneHikeWorkspaceData {
        val incoming = plan(routeId).copy(inspecting = false)
        val archived = todayPlan?.takeIf { it.routeId != routeId }?.let(::updatePlan) ?: this
        return archived.copy(todayId = routeId, previewSelected = false, todayPlan = incoming)
    }

    fun plan(routeId: String): PhoneHikePlan = plans.firstOrNull { it.routeId == routeId } ?: PhoneHikePlan(routeId)

    fun updatePlan(plan: PhoneHikePlan): PhoneHikeWorkspaceData =
        copy(
            plans =
                plans.filterNot { it.routeId == plan.routeId } + plan,
        )

    fun updateSelectedPlan(plan: PhoneHikePlan): PhoneHikeWorkspaceData =
        if (!previewSelected &&
            todayId == plan.routeId
        ) {
            copy(todayPlan = plan)
        } else {
            updatePlan(plan)
        }
}

internal data class PhoneHikePosition(
    val segmentIndex: Int,
    val fraction: Double,
    val coordinate: GeoPoint,
    val distanceMeters: Double,
    val elapsedSeconds: Double,
    val elevationMeters: Double?,
    val ascentMeters: Double?,
    val descentMeters: Double?,
)

internal data class PhoneHikeGraphPoint(
    val distanceMeters: Double,
    val elevationMeters: Double?,
    val startsNewSegment: Boolean,
)

/** Planning and inspection use the same ETA and elevation policy as phone/watch TBT. */
internal class PhoneHikeRouteModel(
    val route: PhoneHikeRoute,
    val reversed: Boolean = false,
) {
    init {
        require(route.points.size >= 2)
    }

    private val session =
        buildPhoneGuidanceSession(
            PhoneGuidanceRouteDraft(
                route.trackId,
                route.title,
                route.points,
                route.routeOffsetMeters,
                route.etaConfig.activityProfile,
                route.etaConfig,
            ),
            PhoneGuidanceProgressDraft(reversed = reversed),
        )
    private val eta =
        requireNotNull(
            buildTrailGuidanceEtaProjection(session.trackPoints, session.cumulativeDistancesMeters, route.etaConfig),
        )
    val distances: List<Double> get() = session.cumulativeDistancesMeters
    val totalDistanceMeters: Double get() = session.totalDistanceMeters
    val totalSeconds: Double get() = eta.totalSeconds ?: 0.0
    val graphPoints: List<PhoneHikeGraphPoint> = buildGraphPoints()
    val mapSegments = PhoneMapGpxTrack(route.trackId, session.trackPoints).toRouteSegments()

    fun position(
        segmentIndex: Int,
        fraction: Double,
    ): PhoneHikePosition? {
        if (segmentIndex !in 0 until route.points.lastIndex ||
            route.points[segmentIndex + 1].startsNewSegment ||
            !fraction.isFinite()
        ) {
            return null
        }
        val index = if (reversed) route.points.lastIndex - 1 - segmentIndex else segmentIndex
        val t = (if (reversed) 1.0 - fraction else fraction).coerceIn(0.0, 1.0)
        val a = session.trackPoints[index]
        val b = session.trackPoints[index + 1]

        fun interpolate(values: List<Double>): Double? =
            values.getOrNull(index)?.let { first ->
                values
                    .getOrNull(
                        index + 1,
                    )?.let { last -> first + t * (last - first) }
            }
        val firstElevation = a.elevationMeters
        val lastElevation = b.elevationMeters
        val originalA = route.points[segmentIndex].location
        val originalB = route.points[segmentIndex + 1].location
        val canonicalFraction = fraction.coerceIn(0.0, 1.0)
        val longitudeDelta = (originalB.longitude - originalA.longitude + 540.0) % 360.0 - 180.0
        val coordinate =
            when (canonicalFraction) {
                0.0 -> originalA
                1.0 -> originalB
                else ->
                    GeoPoint(
                        originalA.latitude + canonicalFraction * (originalB.latitude - originalA.latitude),
                        (originalA.longitude + canonicalFraction * longitudeDelta + 540.0) % 360.0 - 180.0,
                    )
            }
        return PhoneHikePosition(
            segmentIndex,
            fraction.coerceIn(0.0, 1.0),
            coordinate,
            requireNotNull(interpolate(distances)),
            eta.secondsAtRoutePosition(index, t) ?: 0.0,
            when {
                t == 0.0 -> a.elevationMeters
                t == 1.0 -> b.elevationMeters
                firstElevation != null && lastElevation != null -> firstElevation + t * (lastElevation - firstElevation)
                else -> null
            },
            interpolate(session.cumulativeAscentMeters),
            interpolate(session.cumulativeDescentMeters),
        )
    }

    fun atDistance(distanceMeters: Double): PhoneHikePosition? {
        val distance = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        val found = distances.binarySearch(distance)
        var index = (if (found >= 0) found else -found - 2).coerceIn(0, distances.lastIndex - 1)
        while (index < distances.lastIndex - 1 && distances[index + 1] <= distance) index++
        if (session.trackPoints[index + 1].startsNewSegment || distances[index + 1] <= distances[index]) {
            index =
                (index until distances.lastIndex).firstOrNull {
                    !session.trackPoints[it + 1].startsNewSegment &&
                        distances[it + 1] > distances[it]
                }
                    ?: (index downTo 0).firstOrNull {
                        !session.trackPoints[it + 1].startsNewSegment &&
                            distances[it + 1] > distances[it]
                    }
                    ?: return null
        }
        val t = ((distance - distances[index]) / (distances[index + 1] - distances[index])).coerceIn(0.0, 1.0)
        return position(if (reversed) route.points.lastIndex - 1 - index else index, if (reversed) 1.0 - t else t)
    }

    fun nearest(
        point: GeoPoint,
        previousDistanceMeters: Double? = null,
    ): Pair<PhoneHikePosition, Double>? {
        val config = RouteProjectionConfig()
        var start = 0
        val candidates = mutableListOf<Pair<PhoneHikePosition, Double>>()
        for (end in 1..route.points.size) {
            if (end != route.points.size && !route.points[end].startsNewSegment) continue
            if (end - start >= 2) {
                val points = route.points.subList(start, end).map(TrailPoint::location)
                val offset = if (reversed) totalDistanceMeters - distances[route.points.lastIndex - start] else distances[start]
                val canonicalPrevious = previousDistanceMeters?.let { if (reversed) totalDistanceMeters - it else it }
                val projection =
                    projectPointOntoRoute(
                        points,
                        point = point,
                        previousDistanceFromStartMeters = canonicalPrevious?.minus(offset),
                    )
                projection?.let { p ->
                    position(start + p.segmentIndex, p.segmentFraction)?.let {
                        candidates +=
                            it to p.distanceToRouteMeters
                    }
                }
            }
            start = end
        }
        val nearest = candidates.minByOrNull { it.second } ?: return null
        return previousDistanceMeters?.let { previous ->
            candidates
                .filter {
                    it.second <= nearest.second + config.relockAdvantageMeters &&
                        it.first.distanceMeters in
                        (previous - config.maximumBacktrackMeters)..(previous + config.maximumForwardJumpMeters)
                }.minByOrNull { it.second + abs(it.first.distanceMeters - previous) * config.progressPenalty }
        } ?: nearest
    }

    fun orderedCheckpoints(plan: PhoneHikePlan): List<Pair<PhoneHikeCheckpoint, PhoneHikePosition>> =
        plan.checkpoints
            .mapNotNull { checkpoint ->
                position(checkpoint.segmentIndex, checkpoint.fraction)?.let { checkpoint to it }
            }.sortedBy { it.second.distanceMeters }

    fun plannedSecondsAt(
        position: PhoneHikePosition,
        plan: PhoneHikePlan,
    ): Double =
        position.elapsedSeconds +
            orderedCheckpoints(plan).filter { it.second.distanceMeters < position.distanceMeters - 0.01 }.sumOf {
                it.first.stopMinutes *
                    60.0
            }

    private fun buildGraphPoints(): List<PhoneHikeGraphPoint> {
        val selected = sortedSetOf(0, session.trackPoints.lastIndex)
        val bucketSize = (session.trackPoints.size / 240).coerceAtLeast(1)
        session.trackPoints.indices.chunked(bucketSize).forEach { bucket ->
            bucket.minByOrNull { session.trackPoints[it].elevationMeters ?: Double.POSITIVE_INFINITY }?.let(
                selected::add,
            )
            bucket.maxByOrNull { session.trackPoints[it].elevationMeters ?: Double.NEGATIVE_INFINITY }?.let(
                selected::add,
            )
        }
        var previous = -1
        return selected.map { index ->
            val startsSegment = (previous + 1..index).any { session.trackPoints[it].startsNewSegment || session.trackPoints[it].elevationMeters == null }
            previous = index
            PhoneHikeGraphPoint(distances[index], session.trackPoints[index].elevationMeters, startsSegment)
        }
    }
}
