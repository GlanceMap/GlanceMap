package com.glancemap.glancemapcompanionapp.library

import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxItem

internal data class PhoneLibraryVisibility(
    val checked: Boolean,
    val available: Boolean,
    val hiddenByLayers: Boolean,
)

internal fun phoneLibraryVisibility(
    item: PhoneLibraryItem,
    gpxItems: List<PhoneMapGpxItem>,
    gpxVisible: Boolean,
    poiVisible: Boolean,
): PhoneLibraryVisibility? =
    when (item) {
        is PhoneLibraryItem.Gpx,
        is PhoneLibraryItem.FolderGpx,
        -> {
            val id = if (item is PhoneLibraryItem.Gpx) item.route.id else (item as PhoneLibraryItem.FolderGpx).item.id
            val track = gpxItems.firstOrNull { it.id == id }
            PhoneLibraryVisibility(
                checked = track?.enabled == true,
                available = track != null,
                hiddenByLayers = track?.enabled == true && !gpxVisible,
            )
        }
        is PhoneLibraryItem.Pois ->
            PhoneLibraryVisibility(
                checked = item.source.isEnabled,
                available = item.source.isReadable,
                hiddenByLayers = item.source.isEnabled && !poiVisible,
            )
        else -> null
    }
