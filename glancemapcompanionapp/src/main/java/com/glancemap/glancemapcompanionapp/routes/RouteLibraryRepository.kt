package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionJourneyDiagnostics
import com.glancemap.glancemapcompanionapp.map.PhoneGeneralSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.map.phoneGpxDisplayNameFromFileName
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

/**
 * A managed GPX keeps its user-facing filename identity separate from the physical file and GPX
 * XML metadata. Older indexes used `title`; the alternate name keeps those records readable.
 */
data class RouteLibraryRoute(
    @field:SerializedName(value = "id", alternate = ["a"])
    val id: String,
    @field:SerializedName(value = "displayName", alternate = ["title", "b"])
    val displayName: String,
    @field:SerializedName(value = "storedFileName", alternate = ["c"])
    val storedFileName: String,
    @field:SerializedName(value = "importedAtMillis", alternate = ["d"])
    val importedAtMillis: Long,
    @field:SerializedName(value = "summary", alternate = ["e"])
    val summary: RouteLibrarySummary,
    @field:SerializedName(value = "metadataTitle", alternate = ["f"])
    val metadataTitle: String? = null,
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
            val displayName = displayNameFor(uri)
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
                displayName = displayName,
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
                displayName = output.title,
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

    suspend fun renameRoute(
        routeId: String,
        newTitle: String,
    ): RouteLibraryUiState =
        mutex.withLock {
            val index = readIndex()
            require(newTitle.trim().isNotBlank()) { "Enter a GPX name first." }
            val normalizedDisplayName = phoneGpxDisplayNameFromFileName(newTitle)
            require(index.routes.any { route -> route.id == routeId }) { "The GPX route could not be found." }
            val next =
                index.copy(
                    routes =
                        index.routes.map { route ->
                            if (route.id == routeId) route.copy(displayName = normalizedDisplayName) else route
                        },
                )
            writeIndex(next)
            RouteLibraryUiState(
                routes = next.routes.sortedByDescending(RouteLibraryRoute::importedAtMillis),
                selectedRouteId = next.selectedRouteId,
                isLoading = false,
            )
        }

    suspend fun deleteRoute(routeId: String): RouteLibraryUiState =
        mutex.withLock {
            val index = readIndex()
            val route =
                index.routes.firstOrNull { item -> item.id == routeId }
                    ?: error("The GPX route could not be found.")
            val file = File(routesDirectory, route.storedFileName)
            require(!file.exists() || file.delete()) { "The GPX route could not be deleted." }
            val next =
                index.copy(
                    routes = index.routes.filterNot { item -> item.id == routeId },
                    selectedRouteId = index.selectedRouteId.takeUnless { it == routeId },
                )
            writeIndex(next)
            RouteLibraryUiState(
                routes = next.routes.sortedByDescending(RouteLibraryRoute::importedAtMillis),
                selectedRouteId = next.selectedRouteId,
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
                    routeTitle = route.displayName,
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
        displayName: String,
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
                displayName = phoneGpxDisplayNameFromFileName(displayName),
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
                metadataTitle = parsed.title,
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

    private fun displayNameFor(uri: Uri): String {
        val documentName =
            runCatching {
                appContext.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
                    }
            }.getOrNull()?.takeIf(String::isNotBlank)
        val uriName = uri.lastPathSegment?.let(Uri::decode)
        val sourceName = documentName ?: uriName
        return when {
            documentName != null -> phoneGpxDisplayNameFromFileName(documentName)
            sourceName?.trim()?.endsWith(".gpx", ignoreCase = true) == true ->
                phoneGpxDisplayNameFromFileName(sourceName)
            else -> "Imported route"
        }
    }

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
