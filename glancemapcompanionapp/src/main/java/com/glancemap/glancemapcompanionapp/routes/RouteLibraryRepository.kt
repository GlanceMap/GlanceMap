package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionJourneyDiagnostics
import com.glancemap.glancemapcompanionapp.map.PhoneGeneralSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.map.toTrailPacingConfig
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailRouteProfile
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import com.glancemap.trailcore.profile.windowFromDistance
import com.glancemap.trailcore.routing.BRouterRouteOutput
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
    @field:SerializedName(value = "id", alternate = ["a"])
    val id: String,
    @field:SerializedName(value = "title", alternate = ["b"])
    val title: String,
    @field:SerializedName(value = "storedFileName", alternate = ["c"])
    val storedFileName: String,
    @field:SerializedName(value = "importedAtMillis", alternate = ["d"])
    val importedAtMillis: Long,
    @field:SerializedName(value = "summary", alternate = ["e"])
    val summary: RouteLibrarySummary,
)

data class RouteLibrarySummary(
    @field:SerializedName(value = "distanceMeters", alternate = ["a"])
    val distanceMeters: Double,
    @field:SerializedName(value = "elevationGainMeters", alternate = ["b"])
    val elevationGainMeters: Double,
    @field:SerializedName(value = "elevationLossMeters", alternate = ["c"])
    val elevationLossMeters: Double,
    @field:SerializedName(value = "estimatedDurationSeconds", alternate = ["d"])
    val estimatedDurationSeconds: Double,
    @field:SerializedName(value = "waypointCount", alternate = ["e"])
    val waypointCount: Int,
    @field:SerializedName(value = "firstThirtyMinutesDistanceMeters", alternate = ["f"])
    val firstThirtyMinutesDistanceMeters: Double,
    @field:SerializedName(value = "firstThirtyMinutesAscentMeters", alternate = ["g"])
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
    private val storage = PhoneOfflineStorage(appContext)
    private val routesDirectory: File
        get() = storage.routesDirectory()
    private val indexFile: File
        get() = File(routesDirectory, INDEX_FILE_NAME)
    private val gpxSettingsPreferences = PhoneMapGpxSettingsPreferences(appContext)
    private val generalSettingsPreferences = PhoneGeneralSettingsPreferences(appContext)

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

    @Suppress("LongMethod") // Import, parse, profile, and index updates are one atomic repository operation.
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
            saveParsedRouteLocked(
                destination = destination,
                parsed = parsed,
                fallbackTitle = displayNameFor(uri),
            )
        }

    suspend fun saveGeneratedRoute(output: BRouterRouteOutput): RouteLibraryUiState =
        mutex.withLock {
            routesDirectory.mkdirs()
            val id = UUID.randomUUID().toString()
            val destination = File(routesDirectory, "$id.gpx")
            destination.writeBytes(output.gpxBytes)
            val parsed =
                runCatching {
                    destination.inputStream().use(CompanionGpxRouteParser::parse)
                }.getOrElse { error ->
                    destination.delete()
                    throw IllegalArgumentException(error.message ?: "Could not read the generated GPX route.", error)
                }
            saveParsedRouteLocked(
                destination = destination,
                parsed = parsed,
                fallbackTitle = output.title,
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
            val profile =
                buildTrailRouteProfile(
                    parsedRoute.points,
                    gpxSettingsPreferences.load().toTrailPacingConfig(
                        parsedRoute.points,
                        generalSettingsPreferences.load(),
                    ),
                )
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
            val profile =
                buildTrailRouteProfile(
                    parsed.points,
                    gpxSettingsPreferences.load().toTrailPacingConfig(
                        parsed.points,
                        generalSettingsPreferences.load(),
                    ),
                )
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

    private fun saveParsedRouteLocked(
        destination: File,
        parsed: ParsedCompanionRoute,
        fallbackTitle: String,
    ): RouteLibraryUiState {
        val id = destination.nameWithoutExtension
        val profile =
            buildTrailRouteProfile(
                parsed.points,
                gpxSettingsPreferences.load().toTrailPacingConfig(
                    parsed.points,
                    generalSettingsPreferences.load(),
                ),
            )
        val firstThirtyMinutes =
            profile.windowFromDistance(
                startDistanceMeters = 0.0,
                maximumDurationSeconds = NEXT_WINDOW_SECONDS,
            )
        val route =
            RouteLibraryRoute(
                id = id,
                title = importedRouteTitle(parsed.title, fallbackTitle),
                storedFileName = destination.name,
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
                routes = listOf(route) + previous.routes.filterNot { existing -> existing.id == route.id },
                selectedRouteId = route.id,
            )
        writeIndex(next)
        CompanionJourneyDiagnostics.routeImportSucceeded(
            pointCount = parsed.points.size,
            waypointCount = parsed.waypoints.size,
            elevationCount = parsed.points.count { point -> point.elevationMeters != null },
        )
        return RouteLibraryUiState(
            routes = next.routes,
            selectedRouteId = route.id,
            isLoading = false,
        )
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
        @field:SerializedName(value = "routes", alternate = ["a"])
        val routes: List<RouteLibraryRoute> = emptyList(),
        @field:SerializedName(value = "selectedRouteId", alternate = ["b"])
        val selectedRouteId: String? = null,
    )

    private companion object {
        const val INDEX_FILE_NAME = "routes.json"
        const val MISSION_PLAN_EXPORT_DIRECTORY = "mission-plan-exports"
        const val NEXT_WINDOW_SECONDS = 30.0 * 60.0
    }
}
