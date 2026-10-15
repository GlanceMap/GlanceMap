package com.glancemap.glancemapcompanionapp.library

import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import com.glancemap.glancemapcompanionapp.map.phoneDemTileIdsForBounds
import com.glancemap.glancemapcompanionapp.routing.BRouterTileMath
import com.glancemap.glancemapcompanionapp.routing.RoutingBBox
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.reader.MapFile
import java.util.Locale

internal data class PhoneLibraryMapCoverage(
    val bounds: BoundingBox?,
    val requiredElevationTiles: Int? = null,
    val availableElevationTiles: Int = 0,
    val requiredRoutingFiles: Int? = null,
    val availableRoutingFiles: Int = 0,
)

internal fun libraryOfflineMapItems(
    maps: List<PhoneOfflineMap>,
    storedItems: List<PhoneLibraryItem>,
): List<PhoneLibraryItem.OfflineMap> {
    val stored = storedItems.filterIsInstance<PhoneLibraryItem.OfflineMap>().associateBy { it.map.rendererIdentity }
    return maps.map { map ->
        val previous = stored[map.rendererIdentity]
        PhoneLibraryItem.OfflineMap(map, previous?.sizeBytes, previous?.coverage)
    }
}

internal fun readLibraryMapBounds(map: PhoneOfflineMap): BoundingBox? =
    runCatching {
        val reader = MapFile(map.file)
        try {
            reader.boundingBox()
        } finally {
            reader.close()
        }
    }.getOrNull()

internal fun phoneLibraryMapCoverage(
    bounds: BoundingBox?,
    data: List<PhoneLibraryItem.OfflineData>,
): PhoneLibraryMapCoverage {
    if (bounds == null) return PhoneLibraryMapCoverage(null)
    val usable = data.filter { it.isUsable && it.coverage != null }
    val elevation =
        usable
            .filter { it.section == PhoneLibraryOfflineSection.ELEVATION }
            .map { it.title.substringBefore('.').uppercase(Locale.ROOT) }
            .toSet()
    val routing =
        usable
            .filter { it.section == PhoneLibraryOfflineSection.ROUTING }
            .map { it.title.uppercase(Locale.ROOT) }
            .toSet()
    val requiredElevation = phoneDemTileIdsForBounds(bounds)
    val requiredRouting =
        BRouterTileMath.tileFileNamesForBbox(
            RoutingBBox(bounds.minLongitude, bounds.minLatitude, bounds.maxLongitude, bounds.maxLatitude),
        )
    return PhoneLibraryMapCoverage(
        bounds,
        requiredElevation.size,
        requiredElevation.count(elevation::contains),
        requiredRouting.size,
        requiredRouting.count { it.uppercase(Locale.ROOT) in routing },
    )
}
