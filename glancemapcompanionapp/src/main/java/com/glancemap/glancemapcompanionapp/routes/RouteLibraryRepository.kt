package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import com.glancemap.trailcore.profile.windowFromDistance
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

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
                    title = parsed.title ?: displayNameFor(uri),
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
        return file.takeIf(File::isFile)?.let { existingFile ->
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                existingFile,
            )
        }
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

    private data class RouteLibraryIndex(
        val routes: List<RouteLibraryRoute> = emptyList(),
        val selectedRouteId: String? = null,
    )

    private companion object {
        const val ROUTES_DIRECTORY_NAME = "route-library"
        const val INDEX_FILE_NAME = "routes.json"
        const val NEXT_WINDOW_SECONDS = 30.0 * 60.0
    }
}
