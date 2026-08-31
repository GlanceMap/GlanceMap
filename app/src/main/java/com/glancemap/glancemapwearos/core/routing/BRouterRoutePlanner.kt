package com.glancemap.glancemapwearos.core.routing

import android.content.Context
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.util.CheapRuler
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.routing.BRouterEngine
import com.glancemap.trailcore.routing.BRouterFileLayout
import com.glancemap.trailcore.routing.BRouterHikeProfileParams
import com.glancemap.trailcore.routing.BRouterRoutePreset
import com.glancemap.trailcore.routing.BRouterRouteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

private const val ROUTING_TILE_DEGREES = 5
private const val B_ROUTER_COORDINATE_SCALE = 1_000_000.0
private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
private const val METERS_PER_LONGITUDE_DEGREE_FLOOR = 10_000.0
private const val LOOP_TILE_COVERAGE_MIN_RADIUS_METERS = 500.0
private const val LOOP_SEARCH_RADIUS_MIN_METERS = 250
private const val LOOP_SEARCH_RADIUS_MAX_METERS = 25_000
private const val BROUTER_DEFAULT_ROUND_TRIP_POINTS = 5
private const val CITY_LOOP_RETRY_POINT_COUNT = 4
private const val CITY_LOOP_RECT_SHORT_EDGE_FACTOR = 0.58
private val LOOP_RADIUS_MULTIPLIERS = listOf(1.0, 0.85, 1.15)
private val SAME_WAY_BACK_RADIUS_MULTIPLIERS = listOf(0.9, 1.0, 1.1)
private val LOOP_CARDINAL_START_DIRECTIONS = listOf(0, 90, 180, 270)
private val LOOP_DIAGONAL_START_DIRECTIONS = listOf(45, 135, 225, 315)
private val LOOKUP_VERSION_MISMATCH_REGEX =
    Regex("""lookups\.dat=(\d+)\s+\S+\.rd5=(\d+)""", RegexOption.IGNORE_CASE)

internal fun cityLoopRetryDirections(): List<Int> = LOOP_DIAGONAL_START_DIRECTIONS

internal fun cityLoopRetryPreset(basePreset: RoutePlannerPreset): RoutePlannerPreset =
    when {
        basePreset.isBikePreset -> basePreset
        basePreset == RoutePlannerPreset.PREFER_EASIEST -> basePreset
        else -> RoutePlannerPreset.PREFER_EASIEST
    }

private val RoutePlannerPreset.isBikePreset: Boolean
    get() =
        when (this) {
            RoutePlannerPreset.BIKE_TOURING,
            RoutePlannerPreset.BIKE_ROAD,
            RoutePlannerPreset.BIKE_QUIET_ROAD,
            RoutePlannerPreset.BIKE_GRAVEL,
            RoutePlannerPreset.BIKE_MTB,
            -> true

            RoutePlannerPreset.BALANCED_HIKE,
            RoutePlannerPreset.PREFER_TRAILS,
            RoutePlannerPreset.PREFER_EASIEST,
            RoutePlannerPreset.CUSTOM_HIKE,
            -> false
        }

internal fun <T> rotateLoopVariants(
    values: List<T>,
    variationIndex: Int,
): List<T> {
    if (values.isEmpty()) return values
    val normalizedVariation = variationIndex.coerceAtLeast(0)
    val offset = normalizedVariation % values.size
    val rotated = values.drop(offset) + values.take(offset)
    return if (((normalizedVariation / values.size) % 2) == 1) {
        rotated.reversed()
    } else {
        rotated
    }
}

internal fun preferredLoopCandidateIndex(
    candidateCount: Int,
    variationIndex: Int,
): Int {
    if (candidateCount <= 1) return 0
    return variationIndex.coerceAtLeast(0) % candidateCount
}

internal fun requiredRoutingSegmentFileNames(
    origin: LatLong,
    destination: LatLong,
): List<String> = requiredRoutingSegmentFileNames(listOf(origin, destination))

internal fun requiredRoutingSegmentFileNames(points: List<LatLong>): List<String> {
    require(points.isNotEmpty()) { "At least one point is required." }

    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }

    val latStart = routingTileOrigin(minLat)
    val latEnd = routingTileOrigin(maxLat)
    val lonStart = routingTileOrigin(minLon)
    val lonEnd = routingTileOrigin(maxLon)

    val tiles = linkedSetOf<String>()
    var lat = latStart
    while (lat <= latEnd) {
        var lon = lonStart
        while (lon <= lonEnd) {
            tiles += routingTileFileName(swLat = lat, swLon = lon)
            lon += ROUTING_TILE_DEGREES
        }
        lat += ROUTING_TILE_DEGREES
    }
    return tiles.toList()
}

internal fun requiredRoundTripSegmentFileNames(
    start: LatLong,
    searchRadiusMeters: Int,
): List<String> {
    val radiusMeters = max(searchRadiusMeters.toDouble(), LOOP_TILE_COVERAGE_MIN_RADIUS_METERS)
    val latRadiusDeg = radiusMeters / METERS_PER_LATITUDE_DEGREE
    val lonRadiusDeg =
        radiusMeters /
            max(
                METERS_PER_LATITUDE_DEGREE * cos(Math.toRadians(start.latitude)),
                METERS_PER_LONGITUDE_DEGREE_FLOOR,
            )

    return requiredRoutingSegmentFileNames(
        listOf(
            start,
            LatLong(start.latitude + latRadiusDeg, start.longitude),
            LatLong(start.latitude - latRadiusDeg, start.longitude),
            LatLong(start.latitude, start.longitude + lonRadiusDeg),
            LatLong(start.latitude, start.longitude - lonRadiusDeg),
        ),
    )
}

internal fun estimateRoundTripSearchRadiusMeters(
    targetDistanceMeters: Int,
    pointCount: Int,
    allowSameWayBack: Boolean,
): Int {
    val normalizedTarget = targetDistanceMeters.coerceIn(2_000, 60_000)
    val routeLengthFactor =
        when {
            allowSameWayBack -> 2.0
            pointCount <= 3 -> 3.0
            else -> {
                val chordFactor = 2.0 * kotlin.math.sin(Math.PI / (2.0 * pointCount))
                2.0 + (pointCount - 2) * chordFactor
            }
        }
    return (normalizedTarget / routeLengthFactor)
        .toInt()
        .coerceIn(LOOP_SEARCH_RADIUS_MIN_METERS, LOOP_SEARCH_RADIUS_MAX_METERS)
}

internal fun candidateLoopPointCounts(defaultPointCount: Int): List<Int> =
    linkedSetOf(defaultPointCount, BROUTER_DEFAULT_ROUND_TRIP_POINTS, 4, 6, 3)
        .filter { it in 3..20 }

internal fun estimateLoopCoverageRadiusMeters(
    targetDistanceMeters: Int,
    defaultPointCount: Int,
    allowOutAndBack: Boolean,
): Int {
    val circuitCoverageRadius =
        candidateLoopPointCounts(defaultPointCount)
            .maxOf { pointCount ->
                adjustedSearchRadii(
                    baseSearchRadiusMeters =
                        estimateRoundTripSearchRadiusMeters(
                            targetDistanceMeters = targetDistanceMeters,
                            pointCount = pointCount,
                            allowSameWayBack = false,
                        ),
                    multipliers = LOOP_RADIUS_MULTIPLIERS,
                ).maxOrNull() ?: LOOP_SEARCH_RADIUS_MIN_METERS
            }

    val sameWayBackCoverageRadius =
        if (allowOutAndBack) {
            adjustedSearchRadii(
                baseSearchRadiusMeters =
                    estimateRoundTripSearchRadiusMeters(
                        targetDistanceMeters = targetDistanceMeters,
                        pointCount = 3,
                        allowSameWayBack = true,
                    ),
                multipliers = SAME_WAY_BACK_RADIUS_MULTIPLIERS,
            ).maxOrNull() ?: LOOP_SEARCH_RADIUS_MIN_METERS
        } else {
            LOOP_SEARCH_RADIUS_MIN_METERS
        }

    return max(circuitCoverageRadius, sameWayBackCoverageRadius)
}

private fun adjustedSearchRadii(
    baseSearchRadiusMeters: Int,
    multipliers: List<Double>,
): List<Int> =
    multipliers
        .map { multiplier ->
            (baseSearchRadiusMeters * multiplier)
                .toInt()
                .coerceIn(LOOP_SEARCH_RADIUS_MIN_METERS, LOOP_SEARCH_RADIUS_MAX_METERS)
        }.distinct()

internal fun normalizeRoutingErrorMessage(message: String): String =
    when {
        message.isBlank() -> {
            "Could not create route."
        }

        message.startsWith("Missing routing data:", ignoreCase = true) -> {
            message
        }

        message.contains("lookup version mismatch", ignoreCase = true) -> {
            normalizeLookupVersionMismatch(message)
        }

        message.contains("checksum error", ignoreCase = true) ||
            message.contains("checksum failed", ignoreCase = true) -> {
            "Routing data is damaged. Open Downloads, check the bundle for updates, then choose Refresh to repair it."
        }

        message.contains("dummy.brf", ignoreCase = true) -> {
            "Routing profiles missing. Reopen route tools and try again."
        }

        message.contains("datafile", ignoreCase = true) -> {
            "Routing data missing"
        }

        message.contains("not found", ignoreCase = true) -> {
            "No route found."
        }

        message.contains("no track found", ignoreCase = true) -> {
            "No route found."
        }

        message.contains("fresh GPS fix", ignoreCase = true) -> {
            "Waiting for GPS"
        }

        else -> message
    }

private fun normalizeLookupVersionMismatch(message: String): String {
    val versionMatch = LOOKUP_VERSION_MISMATCH_REGEX.find(message)
    val appLookupVersion = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val packLookupVersion = versionMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

    return when {
        appLookupVersion != null &&
            packLookupVersion != null &&
            packLookupVersion > appLookupVersion -> {
            "Routing packs are newer than this app supports. " +
                "Update GlanceMap, then refresh routing packs."
        }

        appLookupVersion != null &&
            packLookupVersion != null &&
            packLookupVersion < appLookupVersion -> {
            "Routing packs use an older format. Refresh the routing packs."
        }

        else -> {
            "Routing data format does not match this app. Update GlanceMap or refresh routing packs."
        }
    }
}

class BRouterRoutePlanner(
    private val context: Context,
) : RoutePlanner {
    private val sharedBRouterEngine by lazy {
        BRouterEngine(
            BRouterFileLayout(
                segmentsDirectory = routingSegmentsDir(context),
                profilesDirectory = routingProfilesDir(context),
            ),
        )
    }

    override suspend fun createRoute(request: RoutePlannerRequest): RoutePlannerOutput =
        withContext(Dispatchers.IO) {
            try {
                ensureBundledProfilesInstalled()
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                logRoutePlannerRequest(
                    kind = "route",
                    preset = request.preset,
                    useElevation = request.useElevation,
                    allowFerries = request.allowFerries,
                    customHikeParams = request.customHikeParams,
                )
                val routedAttempt = executeRoutingRequest(request)
                buildRoutePlannerOutput(
                    routingContext = routedAttempt.routingContext,
                    engine = routedAttempt.engine,
                    title = "BRouter route $stamp",
                    fileName = "route-$stamp.gpx",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val normalized = normalizeRoutingErrorMessage(error.message.orEmpty())
                throw IllegalStateException(normalized, error)
            }
        }

    override suspend fun createLoop(request: RoundTripPlannerRequest): RoutePlannerOutput {
        return withContext(Dispatchers.IO) {
            try {
                ensureBundledProfilesInstalled()
                logRoutePlannerRequest(
                    kind = "loop",
                    preset = request.preset,
                    useElevation = request.useElevation,
                    allowFerries = request.allowFerries,
                    customHikeParams = request.customHikeParams,
                )

                val searchCoverageRadiusMeters =
                    estimateLoopCoverageRadiusMeters(
                        targetDistanceMeters = request.targetDistanceMeters,
                        defaultPointCount = request.pointCount,
                        allowOutAndBack = request.allowOutAndBack,
                    )
                val missingSegments =
                    findMissingRoundTripSegments(
                        start = request.start,
                        searchRadiusMeters = searchCoverageRadiusMeters,
                    )
                require(missingSegments.isEmpty()) {
                    buildMissingSegmentsMessage(missingSegments)
                }

                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val targetDistanceMeters = request.targetDistanceMeters
                val preferAlternativeLoop = request.variationIndex > 0
                val successfulAttempts = mutableListOf<LoopCandidate>()
                var lastErrorMessage: String? = null

                val selectedAttempt =
                    run {
                        fun recordAttempt(attempt: LoopCandidate?) {
                            if (attempt == null) return
                            if (!attempt.hasUsableTrack()) {
                                lastErrorMessage = attempt.engine.errorMessage
                                    ?.takeIf { it.isNotBlank() }
                                    ?: lastErrorMessage
                                return
                            }
                            successfulAttempts += attempt
                        }

                        fun runAttemptStages(
                            stages: List<List<LoopAttemptSpec>>,
                            stopOnCloseEnough: Boolean = true,
                            runner: (LoopAttemptSpec) -> LoopCandidate?,
                        ): LoopCandidate? {
                            for (stage in stages) {
                                for (spec in stage) {
                                    recordAttempt(
                                        runCatching { runner(spec) }.getOrNull(),
                                    )
                                }
                                if (stopOnCloseEnough) {
                                    successfulAttempts
                                        .preferredCloseEnoughCandidate(
                                            targetDistanceMeters = targetDistanceMeters,
                                            variationIndex = request.variationIndex,
                                        )?.let {
                                            return it
                                        }
                                }
                            }
                            return null
                        }

                        val primaryStages =
                            loopAttemptStages(
                                targetDistanceMeters = targetDistanceMeters,
                                defaultPointCount = request.pointCount,
                                allowOutAndBack = request.allowOutAndBack,
                                variationIndex = request.variationIndex,
                            )
                        runAttemptStages(
                            stages = primaryStages.take(1),
                            stopOnCloseEnough = !preferAlternativeLoop,
                        ) { spec ->
                            createLoopEngine(request = request, spec = spec)
                        }?.let { return@run it }

                        runAttemptStages(
                            loopCityRetryStages(
                                targetDistanceMeters = targetDistanceMeters,
                                requestPreset = request.preset,
                                variationIndex = request.variationIndex,
                            ),
                            stopOnCloseEnough = !preferAlternativeLoop,
                        ) { spec ->
                            createLoopFallbackRoute(request = request, spec = spec)
                        }?.let { return@run it }

                        runAttemptStages(
                            stages = primaryStages.drop(1),
                            stopOnCloseEnough = !preferAlternativeLoop,
                        ) { spec ->
                            createLoopEngine(request = request, spec = spec)
                        }?.let { return@run it }

                        if (
                            successfulAttempts.isEmpty() ||
                            successfulAttempts.none { it.isCloseEnoughTo(targetDistanceMeters) }
                        ) {
                            runAttemptStages(
                                loopFallbackStages(
                                    targetDistanceMeters = targetDistanceMeters,
                                    defaultPointCount = request.pointCount,
                                    allowOutAndBack = request.allowOutAndBack,
                                    variationIndex = request.variationIndex,
                                ),
                            ) { spec ->
                                createLoopFallbackRoute(request = request, spec = spec)
                            }?.let { return@run it }
                        }

                        successfulAttempts.preferredCloseEnoughCandidate(
                            targetDistanceMeters = targetDistanceMeters,
                            variationIndex = request.variationIndex,
                        )
                    }

                if (selectedAttempt == null) {
                    val loopSuggestions = successfulAttempts.closestLoopSuggestions(targetDistanceMeters)
                    if (!request.allowOutAndBack || loopSuggestions.hasSuggestion()) {
                        throw LoopRouteSuggestionException(
                            lowerDistanceMeters = loopSuggestions.lowerDistanceMeters,
                            higherDistanceMeters = loopSuggestions.higherDistanceMeters,
                        )
                    }
                    error(
                        lastErrorMessage
                            ?.takeIf { it.isNotBlank() }
                            ?: if (request.allowOutAndBack) {
                                "No loop found near the selected target. Try a different start or target."
                            } else {
                                "No circuit loop found near the selected target. Try a different start or target."
                            },
                    )
                }

                val errorMessage = selectedAttempt.engine.errorMessage
                require(errorMessage.isNullOrBlank()) {
                    normalizeRoutingErrorMessage(errorMessage)
                }

                buildRoutePlannerOutput(
                    routingContext = selectedAttempt.routingContext,
                    engine = selectedAttempt.engine,
                    title = "BRouter loop ${request.targetLabel} $stamp",
                    fileName = "loop-$stamp.gpx",
                )
            } catch (suggestion: LoopRouteSuggestionException) {
                throw suggestion
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val normalized = normalizeRoutingErrorMessage(error.message.orEmpty())
                throw IllegalStateException(normalized, error)
            }
        }
    }

    private fun createLoopEngine(
        request: RoundTripPlannerRequest,
        spec: LoopAttemptSpec,
    ): LoopAttemptResult {
        val effectivePreset = spec.presetOverride ?: request.preset
        val routingContext =
            RoutingContext().apply {
                localFunction = routingProfileFileFor(effectivePreset).absolutePath
                outputFormat = "gpx"
                keyValues =
                    buildProfileParams(
                        preset = effectivePreset,
                        useElevation = request.useElevation,
                        allowFerries = request.allowFerries,
                        customHikeParams = request.customHikeParams,
                    )
                roundTripDistance = spec.searchRadiusMeters
                roundTripPoints = spec.pointCount
                allowSamewayback = spec.allowSameWayBack
                startDirection = spec.startDirectionDegrees
            }

        val engine =
            RoutingEngine(
                null,
                null,
                routingSegmentsDir(context),
                listOf(request.start.toWaypoint("from")),
                routingContext,
                RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP,
            ).apply {
                quite = true
            }

        engine.doRun(LOOP_ROUND_TRIP_TIMEOUT_MS)
        return LoopAttemptResult(
            spec = spec,
            routingContext = routingContext,
            engine = engine,
        )
    }

    private fun createLoopFallbackRoute(
        request: RoundTripPlannerRequest,
        spec: LoopAttemptSpec,
    ): RoutedAttemptResult =
        executeRoutingRequest(
            RoutePlannerRequest(
                origin = request.start,
                destination = request.start,
                viaPoints =
                    buildLoopFallbackViaPoints(
                        start = request.start,
                        spec = spec,
                    ),
                preset = spec.presetOverride ?: request.preset,
                useElevation = request.useElevation,
                allowFerries = request.allowFerries,
                customHikeParams = request.customHikeParams,
            ),
            loopSpec = spec,
            timeoutMs = LOOP_FALLBACK_TIMEOUT_MS,
        )

    private fun executeRoutingRequest(
        request: RoutePlannerRequest,
        loopSpec: LoopAttemptSpec? = null,
        timeoutMs: Long = ROUTE_TIMEOUT_MS,
    ): RoutedAttemptResult {
        val attempt = sharedBRouterEngine.route(request.toSharedRequest(), timeoutMs)

        return RoutedAttemptResult(
            spec = loopSpec,
            routingContext = attempt.routingContext,
            engine = attempt.engine,
        )
    }

    private fun loopAttemptStages(
        targetDistanceMeters: Int,
        defaultPointCount: Int,
        allowOutAndBack: Boolean,
        variationIndex: Int,
    ): List<List<LoopAttemptSpec>> {
        val normalizedPointCount = defaultPointCount.coerceIn(3, 20)
        val defaultBaseRadius =
            estimateRoundTripSearchRadiusMeters(
                targetDistanceMeters = targetDistanceMeters,
                pointCount = normalizedPointCount,
                allowSameWayBack = false,
            )
        val alternatePointCounts =
            rotateLoopVariants(
                values = candidateLoopPointCounts(normalizedPointCount),
                variationIndex = variationIndex,
            ).filterNot { it == normalizedPointCount }

        return buildList {
            add(
                rotateLoopVariants(
                    values =
                        adjustedSearchRadii(
                            baseSearchRadiusMeters = defaultBaseRadius,
                            multipliers = LOOP_RADIUS_MULTIPLIERS,
                        ),
                    variationIndex = variationIndex,
                ).map { searchRadiusMeters ->
                    LoopAttemptSpec(
                        searchRadiusMeters = searchRadiusMeters,
                        pointCount = normalizedPointCount,
                        allowSameWayBack = false,
                    )
                },
            )
            add(
                rotateLoopVariants(
                    values = LOOP_CARDINAL_START_DIRECTIONS,
                    variationIndex = variationIndex,
                ).map { direction ->
                    LoopAttemptSpec(
                        searchRadiusMeters = defaultBaseRadius,
                        pointCount = normalizedPointCount,
                        allowSameWayBack = false,
                        startDirectionDegrees = direction,
                    )
                },
            )
            alternatePointCounts.forEach { pointCount ->
                val baseSearchRadius =
                    estimateRoundTripSearchRadiusMeters(
                        targetDistanceMeters = targetDistanceMeters,
                        pointCount = pointCount,
                        allowSameWayBack = false,
                    )
                add(
                    listOf(
                        LoopAttemptSpec(
                            searchRadiusMeters = baseSearchRadius,
                            pointCount = pointCount,
                            allowSameWayBack = false,
                        ),
                    ),
                )
            }

            if (allowOutAndBack) {
                val sameWayBackBaseRadius =
                    estimateRoundTripSearchRadiusMeters(
                        targetDistanceMeters = targetDistanceMeters,
                        pointCount = 3,
                        allowSameWayBack = true,
                    )
                add(
                    adjustedSearchRadii(
                        baseSearchRadiusMeters = sameWayBackBaseRadius,
                        multipliers = SAME_WAY_BACK_RADIUS_MULTIPLIERS,
                    ).map { searchRadiusMeters ->
                        LoopAttemptSpec(
                            searchRadiusMeters = searchRadiusMeters,
                            pointCount = 3,
                            allowSameWayBack = true,
                        )
                    },
                )
                add(
                    LOOP_CARDINAL_START_DIRECTIONS.map { direction ->
                        LoopAttemptSpec(
                            searchRadiusMeters = sameWayBackBaseRadius,
                            pointCount = 3,
                            allowSameWayBack = true,
                            startDirectionDegrees = direction,
                        )
                    },
                )
            }
        }
    }

    private fun loopCityRetryStages(
        targetDistanceMeters: Int,
        requestPreset: RoutePlannerPreset,
        variationIndex: Int,
    ): List<List<LoopAttemptSpec>> {
        val retryPreset = cityLoopRetryPreset(requestPreset)
        val baseSearchRadius =
            estimateRoundTripSearchRadiusMeters(
                targetDistanceMeters = targetDistanceMeters,
                pointCount = CITY_LOOP_RETRY_POINT_COUNT,
                allowSameWayBack = false,
            )
        return rotateLoopVariants(
            values =
                adjustedSearchRadii(
                    baseSearchRadiusMeters = baseSearchRadius,
                    multipliers = LOOP_RADIUS_MULTIPLIERS,
                ),
            variationIndex = variationIndex,
        ).map { searchRadiusMeters ->
            rotateLoopVariants(
                values = cityLoopRetryDirections(),
                variationIndex = variationIndex,
            ).map { direction ->
                LoopAttemptSpec(
                    searchRadiusMeters = searchRadiusMeters,
                    pointCount = CITY_LOOP_RETRY_POINT_COUNT,
                    allowSameWayBack = false,
                    startDirectionDegrees = direction,
                    presetOverride = retryPreset,
                    fallbackShape = LoopFallbackShape.RECTANGULAR,
                )
            }
        }
    }

    private fun loopFallbackStages(
        targetDistanceMeters: Int,
        defaultPointCount: Int,
        allowOutAndBack: Boolean,
        variationIndex: Int,
    ): List<List<LoopAttemptSpec>> {
        val loopPointCount = defaultPointCount.coerceIn(3, 20)
        val loopBaseRadius =
            estimateRoundTripSearchRadiusMeters(
                targetDistanceMeters = targetDistanceMeters,
                pointCount = loopPointCount,
                allowSameWayBack = false,
            )
        val loopRadii =
            rotateLoopVariants(
                values =
                    adjustedSearchRadii(
                        baseSearchRadiusMeters = loopBaseRadius,
                        multipliers = LOOP_RADIUS_MULTIPLIERS,
                    ),
                variationIndex = variationIndex,
            )

        return buildList {
            add(
                loopRadii.flatMap { searchRadiusMeters ->
                    rotateLoopVariants(
                        values = LOOP_CARDINAL_START_DIRECTIONS,
                        variationIndex = variationIndex,
                    ).map { direction ->
                        LoopAttemptSpec(
                            searchRadiusMeters = searchRadiusMeters,
                            pointCount = loopPointCount,
                            allowSameWayBack = false,
                            startDirectionDegrees = direction,
                        )
                    }
                },
            )
            if (allowOutAndBack) {
                val sameWayBackRadius =
                    estimateRoundTripSearchRadiusMeters(
                        targetDistanceMeters = targetDistanceMeters,
                        pointCount = 3,
                        allowSameWayBack = true,
                    )
                add(
                    rotateLoopVariants(
                        values = LOOP_CARDINAL_START_DIRECTIONS,
                        variationIndex = variationIndex,
                    ).map { direction ->
                        LoopAttemptSpec(
                            searchRadiusMeters = sameWayBackRadius,
                            pointCount = 3,
                            allowSameWayBack = true,
                            startDirectionDegrees = direction,
                        )
                    },
                )
            }
        }
    }

    private fun buildLoopFallbackViaPoints(
        start: LatLong,
        spec: LoopAttemptSpec,
    ): List<LatLong> {
        val startDirection = spec.startDirectionDegrees ?: 0
        return if (spec.allowSameWayBack) {
            listOf(
                offsetLatLong(
                    start = start,
                    distanceMeters = spec.searchRadiusMeters.toDouble(),
                    bearingDegrees = startDirection.toDouble(),
                ),
            )
        } else if (spec.fallbackShape == LoopFallbackShape.RECTANGULAR) {
            val forwardPoint =
                offsetLatLong(
                    start = start,
                    distanceMeters = spec.searchRadiusMeters.toDouble(),
                    bearingDegrees = startDirection.toDouble(),
                )
            val lateralDistance = spec.searchRadiusMeters * CITY_LOOP_RECT_SHORT_EDGE_FACTOR
            val sideBearing = startDirection + 90.0
            val cornerPoint =
                offsetLatLong(
                    start = forwardPoint,
                    distanceMeters = lateralDistance,
                    bearingDegrees = sideBearing,
                )
            val returnEdgePoint =
                offsetLatLong(
                    start = start,
                    distanceMeters = lateralDistance,
                    bearingDegrees = sideBearing,
                )
            listOf(forwardPoint, cornerPoint, returnEdgePoint)
        } else {
            buildList {
                for (index in 1 until spec.pointCount) {
                    val angleOffset = 90.0 - (180.0 * index / spec.pointCount.toDouble())
                    val bearing = startDirection - angleOffset
                    add(
                        offsetLatLong(
                            start = start,
                            distanceMeters = spec.searchRadiusMeters.toDouble(),
                            bearingDegrees = bearing,
                        ),
                    )
                }
            }
        }
    }

    private fun offsetLatLong(
        start: LatLong,
        distanceMeters: Double,
        bearingDegrees: Double,
    ): LatLong {
        val waypoint = start.toWaypoint("offset")
        val destination =
            CheapRuler.destination(
                waypoint.ilon,
                waypoint.ilat,
                distanceMeters,
                bearingDegrees,
            )
        return LatLong(
            destination[1] / B_ROUTER_COORDINATE_SCALE - 90.0,
            destination[0] / B_ROUTER_COORDINATE_SCALE - 180.0,
        )
    }

    private data class LoopAttemptSpec(
        val searchRadiusMeters: Int,
        val pointCount: Int,
        val allowSameWayBack: Boolean,
        val startDirectionDegrees: Int? = null,
        val presetOverride: RoutePlannerPreset? = null,
        val fallbackShape: LoopFallbackShape = LoopFallbackShape.POLYGON,
    )

    private enum class LoopFallbackShape {
        POLYGON,
        RECTANGULAR,
    }

    private data class LoopAttemptResult(
        val spec: LoopAttemptSpec,
        override val routingContext: RoutingContext,
        override val engine: RoutingEngine,
    ) : LoopCandidate {
        override fun hasUsableTrack(): Boolean {
            val track = engine.foundTrack ?: return false
            if (!engine.errorMessage.isNullOrBlank()) return false
            return track.nodes?.isNotEmpty() == true
        }

        override fun actualDistanceMeters(): Int = engine.foundTrack?.distance ?: Int.MAX_VALUE

        override fun allowSameWayBack(): Boolean = spec.allowSameWayBack
    }

    private data class RoutedAttemptResult(
        val spec: LoopAttemptSpec? = null,
        override val routingContext: RoutingContext,
        override val engine: RoutingEngine,
    ) : LoopCandidate {
        override fun hasUsableTrack(): Boolean {
            val track = engine.foundTrack ?: return false
            if (!engine.errorMessage.isNullOrBlank()) return false
            return track.nodes?.isNotEmpty() == true
        }

        override fun actualDistanceMeters(): Int = engine.foundTrack?.distance ?: Int.MAX_VALUE

        override fun allowSameWayBack(): Boolean = spec?.allowSameWayBack == true
    }

    private interface LoopCandidate {
        val routingContext: RoutingContext
        val engine: RoutingEngine

        fun hasUsableTrack(): Boolean

        fun actualDistanceMeters(): Int

        fun allowSameWayBack(): Boolean

        fun distanceDifferenceFrom(targetDistanceMeters: Int): Int = abs(actualDistanceMeters() - targetDistanceMeters)

        fun isCloseEnoughTo(targetDistanceMeters: Int): Boolean {
            val toleranceMeters = max(1_000, (targetDistanceMeters * 0.18).toInt())
            return distanceDifferenceFrom(targetDistanceMeters) <= toleranceMeters
        }
    }

    private data class LoopDistanceSuggestions(
        val lowerDistanceMeters: Int?,
        val higherDistanceMeters: Int?,
    ) {
        fun hasSuggestion(): Boolean = lowerDistanceMeters != null || higherDistanceMeters != null
    }

    private fun List<LoopCandidate>.closestLoopSuggestions(
        targetDistanceMeters: Int,
    ): LoopDistanceSuggestions {
        val viableDistances =
            asSequence()
                .map { it.actualDistanceMeters() }
                .filter { it != Int.MAX_VALUE }
                .distinct()
                .sorted()
                .toList()

        return LoopDistanceSuggestions(
            lowerDistanceMeters = viableDistances.lastOrNull { it < targetDistanceMeters },
            higherDistanceMeters = viableDistances.firstOrNull { it > targetDistanceMeters },
        )
    }

    private fun List<LoopCandidate>.preferredCloseEnoughCandidate(
        targetDistanceMeters: Int,
        variationIndex: Int,
    ): LoopCandidate? {
        val closeEnough =
            asSequence()
                .filter { it.isCloseEnoughTo(targetDistanceMeters) }
                .sortedWith(
                    compareBy<LoopCandidate> { if (it.allowSameWayBack()) 1 else 0 }
                        .thenBy { it.distanceDifferenceFrom(targetDistanceMeters) },
                ).toList()
        if (closeEnough.isEmpty()) return null
        return closeEnough[preferredLoopCandidateIndex(closeEnough.size, variationIndex)]
    }

    private fun ensureBundledProfilesInstalled() {
        copyAsset(
            assetPath = "brouter/profiles2/lookups.dat",
            targetFile = File(routingProfilesDir(context), "lookups.dat"),
        )
        copyAsset(
            assetPath = "brouter/profiles2/$ROUTING_DEFAULT_PROFILE_FILE_NAME",
            targetFile = defaultRoutingProfileFile(context),
        )
        copyAsset(
            assetPath = "brouter/profiles2/$ROUTING_BIKE_TOURING_PROFILE_FILE_NAME",
            targetFile = bikeTouringRoutingProfileFile(context),
        )
        copyAsset(
            assetPath = "brouter/profiles2/$ROUTING_BIKE_ROAD_PROFILE_FILE_NAME",
            targetFile = bikeRoadRoutingProfileFile(context),
        )
        copyAsset(
            assetPath = "brouter/profiles2/$ROUTING_BIKE_QUIET_ROAD_PROFILE_FILE_NAME",
            targetFile = bikeQuietRoadRoutingProfileFile(context),
        )
        copyAsset(
            assetPath = "brouter/profiles2/$ROUTING_BIKE_GRAVEL_PROFILE_FILE_NAME",
            targetFile = bikeGravelRoutingProfileFile(context),
        )
        copyAsset(
            assetPath = "brouter/profiles2/$ROUTING_BIKE_MTB_PROFILE_FILE_NAME",
            targetFile = bikeMtbRoutingProfileFile(context),
        )
        copyAsset(
            assetPath = "brouter/profiles2/$ROUTING_DEFAULT_PROFILE_FILE_NAME",
            targetFile = dummyRoutingProfileFile(context),
        )
        routingSegmentsDir(context)
    }

    private fun routingProfileFileFor(preset: RoutePlannerPreset): File =
        when (preset) {
            RoutePlannerPreset.BIKE_TOURING -> bikeTouringRoutingProfileFile(context)
            RoutePlannerPreset.BIKE_ROAD -> bikeRoadRoutingProfileFile(context)
            RoutePlannerPreset.BIKE_QUIET_ROAD -> bikeQuietRoadRoutingProfileFile(context)
            RoutePlannerPreset.BIKE_GRAVEL -> bikeGravelRoutingProfileFile(context)
            RoutePlannerPreset.BIKE_MTB -> bikeMtbRoutingProfileFile(context)
            else -> defaultRoutingProfileFile(context)
        }

    private fun copyAsset(
        assetPath: String,
        targetFile: File,
    ) {
        targetFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun buildProfileParams(
        preset: RoutePlannerPreset,
        useElevation: Boolean,
        allowFerries: Boolean,
        customHikeParams: HikeRouteProfileParams? = null,
    ): HashMap<String, String> =
        hashMapOf<String, String>().apply {
            put("allow_ferries", allowFerries.toProfileNumber())
            put("consider_elevation", useElevation.toProfileNumber())
            if (!preset.isBikePreset) {
                put(
                    "consider_forest",
                    (customHikeParams?.considerForest ?: false).toProfileNumber(),
                )
            }
            when (preset) {
                RoutePlannerPreset.BALANCED_HIKE -> {
                    put("hiking_routes_preference", "0.20")
                    put("path_preference", "0.0")
                    put("SAC_scale_limit", "3")
                    put("SAC_scale_preferred", "1")
                }

                RoutePlannerPreset.PREFER_TRAILS -> {
                    put("hiking_routes_preference", "0.60")
                    put("path_preference", "20.0")
                    put("SAC_scale_limit", "3")
                    put("SAC_scale_preferred", "2")
                }

                RoutePlannerPreset.PREFER_EASIEST -> {
                    put("hiking_routes_preference", "0.0")
                    put("path_preference", "0.0")
                    put("SAC_scale_limit", "1")
                    put("SAC_scale_preferred", "1")
                }

                RoutePlannerPreset.CUSTOM_HIKE -> {
                    val params = customHikeParams ?: defaultCustomHikeProfileParams()
                    put("hiking_routes_preference", params.hikingRoutesPreference.toProfileNumber())
                    put("path_preference", params.pathPreference.toProfileNumber())
                    put("SAC_scale_limit", params.sacScaleLimit.toString())
                    put("SAC_scale_preferred", params.sacScalePreferred.toString())
                }

                RoutePlannerPreset.BIKE_TOURING -> {
                    put("allow_steps", "0")
                    put("consider_noise", "1")
                    put("consider_traffic", "1")
                    put("avoid_unsafe", "1")
                }

                RoutePlannerPreset.BIKE_ROAD,
                RoutePlannerPreset.BIKE_QUIET_ROAD,
                -> {
                    put("allow_steps", "0")
                }

                RoutePlannerPreset.BIKE_GRAVEL -> {
                    put("avoid_steep_inclines", useElevation.toProfileNumber())
                }

                RoutePlannerPreset.BIKE_MTB -> {
                    put("allow_steps", "0")
                }
            }
        }

    private fun logRoutePlannerRequest(
        kind: String,
        preset: RoutePlannerPreset,
        useElevation: Boolean,
        allowFerries: Boolean,
        customHikeParams: HikeRouteProfileParams?,
    ) {
        val profileFile = routingProfileFileFor(preset).name
        val params =
            buildProfileParams(
                preset = preset,
                useElevation = useElevation,
                allowFerries = allowFerries,
                customHikeParams = customHikeParams,
            ).toSortedMap()
        DebugTelemetry.log(
            "RouteTools",
            "event=planner_request kind=$kind preset=$preset profile=$profileFile params=$params",
        )
    }

    private fun defaultCustomHikeProfileParams(): HikeRouteProfileParams =
        HikeRouteProfileParams(
            hikingRoutesPreference = 0.20f,
            pathPreference = 0f,
            sacScaleLimit = 3,
            sacScalePreferred = 1,
            considerForest = false,
        )

    private fun findMissingSegments(
        origin: LatLong,
        destination: LatLong,
    ): List<String> = findMissingSegments(listOf(origin, destination))

    private fun findMissingSegments(points: List<LatLong>): List<String> {
        val segmentDir = routingSegmentsDir(context)
        return requiredRoutingSegmentFileNames(points)
            .filterNot { File(segmentDir, it).exists() }
    }

    private fun findMissingRoundTripSegments(
        start: LatLong,
        searchRadiusMeters: Int,
    ): List<String> {
        val segmentDir = routingSegmentsDir(context)
        return requiredRoundTripSegmentFileNames(start, searchRadiusMeters)
            .filterNot { File(segmentDir, it).exists() }
    }

    private fun buildMissingSegmentsMessage(missingSegments: List<String>): String =
        when (missingSegments.size) {
            0 -> "Routing data missing"
            1 -> "Missing routing data: ${missingSegments.first()}"
            else -> "Missing routing data: ${missingSegments.first()} +${missingSegments.size - 1}"
        }

    private fun buildRoutePlannerOutput(
        routingContext: RoutingContext,
        engine: RoutingEngine,
        title: String,
        fileName: String,
    ): RoutePlannerOutput {
        val output =
            sharedBRouterEngine.output(
                attempt =
                    com.glancemap.trailcore.routing.BRouterRoutingAttempt(
                        routingContext = routingContext,
                        engine = engine,
                    ),
                title = title,
                fileName = fileName,
            )

        return RoutePlannerOutput(
            fileName = output.fileName,
            title = output.title,
            gpxBytes = output.gpxBytes,
            points =
                output.points.map { point ->
                    RouteGeometryPoint(
                        latLong = LatLong(point.location.latitude, point.location.longitude),
                        elevation = point.elevationMeters,
                    )
                },
        )
    }

    private fun RoutePlannerRequest.toSharedRequest(): BRouterRouteRequest =
        BRouterRouteRequest(
            origin = origin.toGeoPoint(),
            destination = destination.toGeoPoint(),
            viaPoints = viaPoints.map { it.toGeoPoint() },
            preset = preset.toSharedPreset(),
            useElevation = useElevation,
            allowFerries = allowFerries,
            customHikeParams = customHikeParams?.toSharedParams(),
        )

    private fun LatLong.toGeoPoint(): GeoPoint = GeoPoint(latitude = latitude, longitude = longitude)

    private fun RoutePlannerPreset.toSharedPreset(): BRouterRoutePreset = BRouterRoutePreset.valueOf(name)

    private fun HikeRouteProfileParams.toSharedParams(): BRouterHikeProfileParams =
        BRouterHikeProfileParams(
            hikingRoutesPreference = hikingRoutesPreference,
            pathPreference = pathPreference,
            sacScaleLimit = sacScaleLimit,
            sacScalePreferred = sacScalePreferred,
            considerForest = considerForest,
        )

    private fun LatLong.toWaypoint(name: String): OsmNodeNamed =
        OsmNodeNamed().apply {
            this.name = name
            ilon = ((longitude + 180.0) * 1_000_000.0 + 0.5).toInt()
            ilat = ((latitude + 90.0) * 1_000_000.0 + 0.5).toInt()
        }

    private fun Boolean.toProfileNumber(): String = if (this) "1" else "0"

    private fun Float.toProfileNumber(): String = toString()

    private companion object {
        const val ROUTE_TIMEOUT_MS = 60_000L
        const val LOOP_ROUND_TRIP_TIMEOUT_MS = 15_000L
        const val LOOP_FALLBACK_TIMEOUT_MS = 20_000L
    }
}

private fun routingTileOrigin(coordinate: Double): Int = floor(coordinate / ROUTING_TILE_DEGREES.toDouble()).toInt() * ROUTING_TILE_DEGREES

private fun routingTileFileName(
    swLat: Int,
    swLon: Int,
): String =
    "${formatRoutingTileCoord(swLon, positivePrefix = 'E', negativePrefix = 'W')}" +
        "_${formatRoutingTileCoord(swLat, positivePrefix = 'N', negativePrefix = 'S')}.rd5"

private fun formatRoutingTileCoord(
    value: Int,
    positivePrefix: Char,
    negativePrefix: Char,
): String {
    val prefix = if (value < 0) negativePrefix else positivePrefix
    return "$prefix${abs(value)}"
}
