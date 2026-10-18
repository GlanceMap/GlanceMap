package com.glancemap.glancemapcompanionapp.library

import android.net.Uri
import androidx.annotation.StringRes
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.PhoneInstalledBundle
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxItem
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleHealth
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageLocation
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import org.mapsforge.core.model.BoundingBox
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

    data class FolderGpx(
        val item: PhoneMapGpxItem,
    ) : PhoneLibraryItem {
        override val key = "gpx/${item.id}"
        override val title = item.displayName
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
        val sizeBytes: Long?,
        val coverage: PhoneLibraryMapCoverage? = null,
    ) : PhoneLibraryItem {
        override val key = "offline/${map.selectionIdentity}"
        override val title = map.displayName
        override val filter = PhoneLibraryFilter.MAPS
    }

    data class Bundle(
        val bundle: PhoneInstalledBundle,
        val health: PhoneOfflineBundleHealth,
        val map: PhoneOfflineMap?,
        val capabilities: List<PhoneLibraryOfflineCapability> = emptyList(),
    ) : PhoneLibraryItem {
        override val key = "bundle/${bundle.areaId}"
        override val title = bundle.areaLabel
        override val filter = PhoneLibraryFilter.BUNDLES
    }

    data class OfflineData(
        val file: File,
        val relativePath: String,
        val section: PhoneLibraryOfflineSection,
        val sizeBytes: Long,
        val modifiedMillis: Long,
        val isUsable: Boolean,
        val coverage: BoundingBox?,
        val areaNames: List<String>,
        val storageLocation: PhoneOfflineStorageLocation,
    ) : PhoneLibraryItem {
        override val key = "offline-data/${section.name}/$relativePath"
        override val title = file.name
        override val filter = PhoneLibraryFilter.BUNDLES
    }
}

internal data class PhoneLibraryRowState(
    val item: PhoneLibraryItem,
    val enabled: Boolean,
    val organization: PhoneLibraryOrganization = PhoneLibraryOrganization(),
    val guidanceEnabled: Boolean = false,
    val guidanceLoading: Boolean = false,
    val onStartGuidance: (PhoneLibraryItem) -> Unit = {},
    val onPreviewGpx: ((PhoneLibraryItem) -> Unit)? = null,
    val onOpenGpxInNewHikeTab: ((PhoneLibraryItem) -> Unit)? = null,
    val onRenameRoute: suspend (String, String) -> Unit = { _, _ -> },
    val onDeleteRoute: suspend (String) -> Unit = {},
)

internal data class PhoneLibraryListState(
    val items: List<PhoneLibraryItem>,
    val filter: PhoneLibraryFilter,
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val message: String? = null,
    val organization: PhoneLibraryOrganization = PhoneLibraryOrganization(),
)

internal data class PhoneLibraryActions(
    val onRefresh: () -> Unit,
    val onImportGpx: (Uri, Boolean, String?) -> Unit,
    val onUseRoute: (String) -> Unit,
    val onOpenMapTools: (MapTool) -> Unit,
    val onOpenSettings: (MapTool) -> Unit,
    val onMapAction: (MapLibraryAction) -> Unit,
    val onSendGpx: (String) -> Unit,
    val onFilterChanged: (PhoneLibraryFilter) -> Unit,
    val organization: PhoneLibraryOrganizationActions = PhoneLibraryOrganizationActions(),
    val onMoveItem: (PhoneLibraryItem) -> Unit = {},
    val onGpxAppearance: (PhoneLibraryItem) -> Unit = {},
    val onFolderGpxAppearance: (PhoneLibraryFolder) -> Unit = {},
    val onImportOfflineFiles: (List<Uri>, PhoneLibraryOfflineSection) -> Unit = { _, _ -> },
    val guidanceStartingKey: String? = null,
    val guidanceActive: Boolean = false,
    val onStartGuidance: (PhoneLibraryItem) -> Unit = {},
    val onPreviewGpx: ((PhoneLibraryItem) -> Unit)? = null,
    val onOpenGpxInNewHikeTab: ((PhoneLibraryItem) -> Unit)? = null,
    val onRenameRoute: suspend (String, String) -> Unit = { _, _ -> },
    val onDeleteRoute: suspend (String) -> Unit = {},
)

internal fun phoneLibraryItems(
    routes: List<RouteLibraryRoute>,
    localFiles: List<PhoneLibraryItem>,
    pois: List<PhoneMapPoiSource>,
    gpxItems: List<PhoneMapGpxItem> = emptyList(),
): List<PhoneLibraryItem> {
    val importedExports = routes.mapNotNull(RouteLibraryRoute::sourceExportId).toSet()
    val managedIds = routes.map(RouteLibraryRoute::id).toSet()
    return routes.sortedByDescending(RouteLibraryRoute::importedAtMillis).map(PhoneLibraryItem::Gpx) +
        gpxItems.filterNot { it.id in managedIds }.map(PhoneLibraryItem::FolderGpx) +
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
