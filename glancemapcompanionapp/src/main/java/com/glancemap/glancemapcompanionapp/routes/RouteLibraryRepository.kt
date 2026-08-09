package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionJourneyDiagnostics
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailRouteProfile
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import com.glancemap.trailcore.profile.windowFromDistance
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Locus can use the bounding-box coordinates as GPX metadata instead of a useful route name. */
internal fun importedRouteTitle(
    parsedTitle: String?,
    fallbackTitle: String,
): String =
    parsedTitle
        ?.trim()
        ?.takeUnless(::isCoordinateBoundsTitle)
        ?.takeIf(String::isNotBlank)
        ?: fallbackTitle

private fun isCoordinateBoundsTitle(title: String): Boolean = COORDINATE_BOUNDS_TITLE.matches(title)

private val COORDINATE_BOUNDS_TITLE =
    Regex(
        """^-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*-\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?$""",
    )

data class RouteLibraryRoute(
    val id: String,
    val title: String,
    val storedFileName: String,
    val importedAtMillis: Long,
    val summary: RouteLibrarySummary,
)

data class RouteLibrarySummary(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val estimatedDurationSeconds: Double,
    val waypointCount: Int,
    val firstThirtyMinutesDistanceMeters: Double,
    val firstThirtyMinutesAscentMeters: Double,
)

data class RouteLibraryRouteDetails(
    val route: RouteLibraryRoute,
    val profile: TrailRouteProfile,
    val waypoints: List<RouteLibraryWaypoint>,
)

data class RouteLibraryWaypoint(
    val title: String,
    val description: String?,
    val distanceFromStartMeters: Double,
)

data class RouteLibraryUiState(
    val routes: List<RouteLibraryRoute> = emptyList(),
    val selectedRouteId: String? = null,
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val message: String? = null,
) {
    val selectedRoute: RouteLibraryRoute?
        get() = routes.firstOrNull { it.id == selectedRouteId }
}

@Suppress("TooManyFunctions")
class RouteLibraryRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val mutex = Mutex()
    private val routesDirectory = File(appContext.filesDir, ROUTES_DIRECTORY_NAME)
    private val indexFile = File(routesDirectory, INDEX_FILE_NAME)

    suspend fun load(): RouteLibraryUiState =
        mutex.withLock {
            val index = readIndex()
            val routes = index.routes.filter { route -> File(routesDirectory, route.storedFileName).isFile }
            val selectedRouteId = index.selectedRouteId?.takeIf { id -> routes.any { it.id == id } }
            RouteLibraryUiState(
                routes = routes.sortedByDescending(RouteLibraryRoute::importedAtMillis),
                selectedRouteId = selectedRouteId,
                isLoading = false,
            )
        }

    suspend fun importRoute(uri: Uri): RouteLibraryUiState =
        mutex.withLock {
            routesDirectory.mkdirs()
            val id = UUID.randomUUID().toString()
            val storedFileName = "$id.gpx"
            val destination = File(routesDirectory, storedFileName)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot read the selected GPX file.")

            val parsed =
                runCatching {
                    destination.inputStream().use(CompanionGpxRouteParser::parse)
                }.getOrElse { error ->
                    destination.delete()
                    throw IllegalArgumentException(error.message ?: "Could not read the GPX route.", error)
                }
            val profile = buildTrailRouteProfile(parsed.points)
            val firstThirtyMinutes =
                profile.windowFromDistance(
                    startDistanceMeters = 0.0,
                    maximumDurationSeconds = NEXT_WINDOW_SECONDS,
                )
            val route =
                RouteLibraryRoute(
                    id = id,
                    title = importedRouteTitle(parsed.title, displayNameFor(uri)),
                    storedFileName = storedFileName,
                    importedAtMillis = System.currentTimeMillis(),
                    summary =
                        RouteLibrarySummary(
                            distanceMeters = profile.totalDistanceMeters,
                            elevationGainMeters = profile.totalAscentMeters,
                            elevationLossMeters = profile.totalDescentMeters,
                            estimatedDurationSeconds = profile.estimatedDurationSeconds,
                            waypointCount = parsed.waypoints.size,
                            firstThirtyMinutesDistanceMeters = firstThirtyMinutes.distanceMeters,
                            firstThirtyMinutesAscentMeters = firstThirtyMinutes.ascentMeters,
                        ),
                )
            val previous = readIndex()
            val next =
                RouteLibraryIndex(
                    routes = listOf(route) + previous.routes.filterNot { it.id == route.id },
                    selectedRouteId = route.id,
                )
            writeIndex(next)
            CompanionJourneyDiagnostics.routeImportSucceeded(
                pointCount = parsed.points.size,
                waypointCount = parsed.waypoints.size,
                elevationCount = parsed.points.count { point -> point.elevationMeters != null },
            )
            RouteLibraryUiState(
                routes = next.routes,
                selectedRouteId = route.id,
                isLoading = false,
            )
        }

    suspend fun selectRoute(routeId: String): RouteLibraryUiState =
        mutex.withLock {
            val index = readIndex()
            val selectedRouteId = routeId.takeIf { id -> index.routes.any { it.id == id } }
            val next = index.copy(selectedRouteId = selectedRouteId)
            writeIndex(next)
            RouteLibraryUiState(
                routes = next.routes.sortedByDescending(RouteLibraryRoute::importedAtMillis),
                selectedRouteId = selectedRouteId,
                isLoading = false,
            )
        }

    fun contentUriFor(routeId: String): Uri? {
        val route = readIndex().routes.firstOrNull { it.id == routeId } ?: return null
        val file = File(routesDirectory, route.storedFileName)
        return file.takeIf(File::isFile)?.let(::contentUriForFile)
    }

    /** Returns the source GPX for a full day, or a disposable GPX export for a route segment. */
    suspend fun contentUriFor(day: MissionPlanDay): Uri? =
        mutex.withLock {
            val route = readIndex().routes.firstOrNull { it.id == day.routeId } ?: return@withLock null
            val sourceFile = File(routesDirectory, route.storedFileName)
            if (!sourceFile.isFile) return@withLock null
            val parsedRoute = sourceFile.inputStream().use(CompanionGpxRouteParser::parse)
            val profile = buildTrailRouteProfile(parsedRoute.points)
            if (day.isWholeRoute(profile.totalDistanceMeters)) {
                return@withLock contentUriForFile(sourceFile)
            }
            val exportDirectory =
                File(appContext.cacheDir, MISSION_PLAN_EXPORT_DIRECTORY).apply(File::mkdirs)
            val exportFile = File(exportDirectory, "mission-day-${day.id}.gpx")
            exportFile.writeText(
                MissionPlanGpxExporter.export(
                    day = day,
                    routeTitle = route.title,
                    parsedRoute = parsedRoute,
                    profile = profile,
                ),
            )
            contentUriForFile(exportFile)
        }

    suspend fun routeDetails(routeId: String): RouteLibraryRouteDetails? =
        mutex.withLock {
            val route = readIndex().routes.firstOrNull { it.id == routeId } ?: return@withLock null
            val file = File(routesDirectory, route.storedFileName)
            if (!file.isFile) return@withLock null
            val parsed = file.inputStream().use(CompanionGpxRouteParser::parse)
            val profile = buildTrailRouteProfile(parsed.points)
            RouteLibraryRouteDetails(
                route = route,
                profile = profile,
                waypoints =
                    parsed.waypoints
                        .mapNotNull { waypoint ->
                            val title = waypoint.title ?: waypoint.description ?: return@mapNotNull null
                            RouteLibraryWaypoint(
                                title = title,
                                description = waypoint.description?.takeIf { it != title },
                                distanceFromStartMeters = profile.nearestPointDistanceFromStart(waypoint),
                            )
                        }.sortedBy(RouteLibraryWaypoint::distanceFromStartMeters),
            )
        }

    private fun readIndex(): RouteLibraryIndex {
        if (!indexFile.isFile) return RouteLibraryIndex()
        return runCatching {
            indexFile.reader().use { reader ->
                gson.fromJson(reader, RouteLibraryIndex::class.java) ?: RouteLibraryIndex()
            }
        }.getOrDefault(RouteLibraryIndex())
    }

    private fun writeIndex(index: RouteLibraryIndex) {
        routesDirectory.mkdirs()
        val tempFile = File(routesDirectory, "$INDEX_FILE_NAME.tmp")
        tempFile.writer().use { writer -> gson.toJson(index, writer) }
        if (!tempFile.renameTo(indexFile)) {
            tempFile.copyTo(indexFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun displayNameFor(uri: Uri): String =
        uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.replace('-', ' ')
            ?.takeIf { it.isNotBlank() }
            ?: "Imported route"

    private fun TrailRouteProfile.nearestPointDistanceFromStart(waypoint: RouteWaypoint): Double {
        val nearestPointIndex =
            points.indices.minByOrNull { index ->
                haversineDistanceMeters(points[index].location, waypoint.location)
            } ?: return 0.0
        return cumulativeDistanceMeters[nearestPointIndex]
    }

    private fun contentUriForFile(file: File): Uri =
        FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )

    private data class RouteLibraryIndex(
        val routes: List<RouteLibraryRoute> = emptyList(),
        val selectedRouteId: String? = null,
    )

    private companion object {
        const val ROUTES_DIRECTORY_NAME = "route-library"
        const val INDEX_FILE_NAME = "routes.json"
        const val MISSION_PLAN_EXPORT_DIRECTORY = "mission-plan-exports"
        const val NEXT_WINDOW_SECONDS = 30.0 * 60.0
    }
}
