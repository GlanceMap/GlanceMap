package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlayMarker
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlaySource
import java.util.LinkedHashMap

internal data class PoiMarkerBitmapKey(
    val type: PoiType,
    val effectiveMarkerSizePx: Int,
    val markerStyle: String,
)

internal data class PoiMarkerPreparationConfig(
    val sources: List<PoiOverlaySource>,
    val markerSizePx: Int,
    val markerStyle: String,
)

internal class PoiMarkerPreparationVersion(
    initialConfig: PoiMarkerPreparationConfig,
) {
    private var config = initialConfig
    private var version = 0L

    fun update(nextConfig: PoiMarkerPreparationConfig) {
        if (nextConfig != config) {
            config = nextConfig
            invalidate()
        }
    }

    fun snapshot(): Long = version

    fun invalidate(): Long {
        version += 1
        return version
    }

    fun isCurrent(snapshot: Long): Boolean = snapshot == version
}

/** An access-ordered cache. Eviction releases the cache's Mapsforge bitmap reference. */
internal class PoiMarkerBitmapCache<T : Any>(
    private val maxEntries: Int = MAX_POI_MARKER_BITMAP_CACHE_ENTRIES,
    private val onEvicted: (T) -> Unit = {},
) {
    private val entries = LinkedHashMap<PoiMarkerBitmapKey, T>(16, 0.75f, true)

    init {
        require(maxEntries > 0)
    }

    operator fun get(key: PoiMarkerBitmapKey): T? = entries[key]

    fun put(
        key: PoiMarkerBitmapKey,
        value: T,
    ) {
        val previous = entries.put(key, value)
        if (previous != null && previous !== value) {
            onEvicted(previous)
        }
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            val removed = eldest.next()
            eldest.remove()
            onEvicted(removed.value)
        }
    }

    fun clear() {
        val previousEntries = entries.values.toList()
        entries.clear()
        previousEntries.forEach(onEvicted)
    }
}

internal fun poiMarkerPreparationConfig(
    sources: List<PoiOverlaySource>,
    markerSizePx: Int,
    markerStyle: String,
): PoiMarkerPreparationConfig =
    PoiMarkerPreparationConfig(
        sources = sources.map { source -> source.copy(enabledCategoryIds = source.enabledCategoryIds.toSet()) },
        markerSizePx = markerSizePx,
        markerStyle = markerStyle,
    )

internal fun requiredPoiMarkerTypes(markers: List<PoiOverlayMarker>): Set<PoiType> {
    if (markers.isEmpty()) return emptySet()

    return buildSet {
        markers.forEach { marker -> add(marker.type) }
        add(PoiType.GENERIC)
    }
}

internal val MAX_POI_MARKER_BITMAP_CACHE_ENTRIES = PoiType.entries.size * 4
