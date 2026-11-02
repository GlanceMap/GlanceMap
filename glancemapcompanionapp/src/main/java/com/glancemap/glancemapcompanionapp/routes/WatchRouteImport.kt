package com.glancemap.glancemapcompanionapp.routes

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val WATCH_ROUTE_EXPORT_PREFIX = "watch:"

/** Lets open screens reload when a background receive adds a route. */
internal object RouteLibraryUpdates {
    private val changed =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = changed.asSharedFlow()

    fun notifyChanged() {
        changed.tryEmit(Unit)
    }
}

internal sealed interface WatchRouteImport {
    val route: RouteLibraryRoute

    data class Added(
        override val route: RouteLibraryRoute,
    ) : WatchRouteImport

    data class AlreadyInLibrary(
        override val route: RouteLibraryRoute,
    ) : WatchRouteImport
}

/** The watch sends a checksum with every file, so the same content always maps to one route. */
internal fun watchRouteExportId(sha256: String): String = WATCH_ROUTE_EXPORT_PREFIX + sha256.lowercase()

internal fun List<RouteLibraryRoute>.findWatchRoute(sha256: String): RouteLibraryRoute? {
    val exportId = watchRouteExportId(sha256)
    return firstOrNull { it.sourceExportId == exportId }
}

/** Every route with an export id arrived from the watch, including ones imported by hand before sync. */
internal val RouteLibraryRoute.isFromWatch: Boolean
    get() = sourceExportId != null
