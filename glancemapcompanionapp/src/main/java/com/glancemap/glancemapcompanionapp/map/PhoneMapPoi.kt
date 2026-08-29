package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.refuges.PoiSqlitePoint
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.poi.PoiDetails
import com.glancemap.trailcore.poi.PoiSemantics
import com.glancemap.trailcore.poi.PoiType

/** A renderer-independent phone map POI prepared from one stored phone POI source. */
internal data class PhoneMapPoi(
    val id: String,
    val sourceId: String,
    val location: GeoPoint,
    val type: PoiType,
    val label: String?,
    val details: PoiDetails?,
)

/** A canonical companion POI database that the current viewport reader can use. */
internal data class PhoneMapPoiSource(
    val fileName: String,
    val isReadable: Boolean,
)

/** Geographic camera bounds passed from the MapLibre surface to the phone POI controller. */
internal data class PhoneMapViewport(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val zoom: Double,
) {
    init {
        require(minLat.isFinite() && maxLat.isFinite() && minLon.isFinite() && maxLon.isFinite())
        require(zoom.isFinite() && zoom >= 0.0)
        require(minLat <= maxLat && minLon <= maxLon)
    }
}

internal fun PoiSqlitePoint.toPhoneMapPoi(sourceKey: String): PhoneMapPoi? {
    val source = sourceKey.takeIf { it.isNotBlank() }
    val storedId = sourceId
    return if (source != null && storedId != null && hasValidLocation()) {
        PhoneMapPoi(
            id = "$source#$storedId",
            sourceId = source,
            location = GeoPoint(latitude = lat, longitude = lon),
            type = PoiSemantics.classify(tags = tags, categoryName = categoryName, rawData = rawData),
            label = PoiSemantics.displayName(tags),
            details = PoiSemantics.details(tags = tags, categoryName = categoryName),
        )
    } else {
        null
    }
}

private fun PoiSqlitePoint.hasValidLocation(): Boolean = lat.isValidLatitude() && lon.isValidLongitude()

private fun Double.isValidLatitude(): Boolean = isFinite() && this in -90.0..90.0

private fun Double.isValidLongitude(): Boolean = isFinite() && this in -180.0..180.0
