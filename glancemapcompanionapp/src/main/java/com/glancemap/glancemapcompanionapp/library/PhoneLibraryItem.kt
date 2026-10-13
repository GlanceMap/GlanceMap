package com.glancemap.glancemapcompanionapp.library

import android.net.Uri
import androidx.annotation.StringRes
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.PhoneInstalledBundle
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleHealth
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import java.io.File

internal enum class PhoneLibraryFilter(
    @StringRes val label: Int,
    val tool: MapTool?,
) {
    GPX(R.string.map_tool_gpx_title, MapTool.GPX),
    POIS(R.string.library_filter_pois, MapTool.POI),
    MAPS(R.string.map_tool_maps_title, MapTool.MAPS),
    BUNDLES(R.string.library_filter_bundles, null),
}

internal sealed interface PhoneLibraryItem {
    val key: String
    val title: String
    val filter: PhoneLibraryFilter

    data class Gpx(
        val route: RouteLibraryRoute,
    ) : PhoneLibraryItem {
        override val key = "gpx/${route.id}"
        override val title = route.displayName
        override val filter = PhoneLibraryFilter.GPX
    }

    data class ReceivedGpx(
        val file: File,
        val sizeBytes: Long,
        val modifiedMillis: Long,
    ) : PhoneLibraryItem {
        // A later export may reuse a filename after the old received copy is removed.
        val exportId = "${file.name}:$sizeBytes:$modifiedMillis"
        override val key = "received/${file.absolutePath}"
        override val title = file.name
        override val filter = PhoneLibraryFilter.GPX
    }

    data class Pois(
        val source: PhoneMapPoiSource,
    ) : PhoneLibraryItem {
        override val key = "poi/${source.fileName}"
        override val title = source.fileName
        override val filter = PhoneLibraryFilter.POIS
    }

    data class OfflineMap(
        val map: PhoneOfflineMap,
        val sizeBytes: Long,
    ) : PhoneLibraryItem {
        override val key = "offline/${map.selectionIdentity}"
        override val title = map.displayName
        override val filter = PhoneLibraryFilter.MAPS
    }

    data class Bundle(
        val bundle: PhoneInstalledBundle,
        val health: PhoneOfflineBundleHealth,
        val map: PhoneOfflineMap?,
    ) : PhoneLibraryItem {
        override val key = "bundle/${bundle.areaId}"
        override val title = bundle.areaLabel
        override val filter = PhoneLibraryFilter.BUNDLES
    }
}

internal data class PhoneLibraryActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onImportGpx: (Uri, Boolean, String?) -> Unit,
    val onUseRoute: (String) -> Unit,
    val onOpenMapTools: (MapTool) -> Unit,
    val onOpenSettings: (MapTool) -> Unit,
    val onMapAction: (MapLibraryAction) -> Unit,
    val onSendGpx: (String) -> Unit,
    val onFilterChanged: (PhoneLibraryFilter) -> Unit,
)

internal fun phoneLibraryItems(
    routes: List<RouteLibraryRoute>,
    localFiles: List<PhoneLibraryItem>,
    pois: List<PhoneMapPoiSource>,
): List<PhoneLibraryItem> {
    val importedExports = routes.mapNotNull(RouteLibraryRoute::sourceExportId).toSet()
    return routes.sortedByDescending(RouteLibraryRoute::importedAtMillis).map(PhoneLibraryItem::Gpx) +
        localFiles.filterNot { it is PhoneLibraryItem.ReceivedGpx && it.exportId in importedExports } +
        pois.map(PhoneLibraryItem::Pois)
}

internal fun filterPhoneLibraryItems(
    items: List<PhoneLibraryItem>,
    filter: PhoneLibraryFilter,
    query: String,
): List<PhoneLibraryItem> =
    items.filter { item ->
        (item.filter == filter) &&
            item.title.contains(query.trim(), ignoreCase = true)
    }

internal fun receivedPhoneLibraryFiles(directory: File): List<PhoneLibraryItem.ReceivedGpx> =
    directory
        .listFiles()
        .orEmpty()
        .filter { it.isFile && !it.isHidden && it.extension.equals("gpx", ignoreCase = true) }
        .sortedByDescending(File::lastModified)
        .map { PhoneLibraryItem.ReceivedGpx(it, it.length(), it.lastModified()) }
