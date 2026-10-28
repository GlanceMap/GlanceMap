package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

internal data class TerrainPanDestination(
    val center: LatLong,
    val requiresRefresh: Boolean,
)

internal fun terrainGpxCrossesDateline(
    fromLongitude: Double,
    toLongitude: Double,
): Boolean = abs(fromLongitude - toLongitude) > 180.0

internal fun terrainPanDestination(
    base: LatLong,
    east: Float,
    north: Float,
    widthMeters: Float = TERRAIN_WIDTH_METERS,
    safeRadius: Float = widthMeters * 0.25f,
): TerrainPanDestination {
    require(east.isFinite() && north.isFinite())
    val area = TerrainArea(base.latitude, base.longitude, widthMeters.toDouble())
    val (latitude, longitude) = area.coordinateForMeters(east.toDouble(), north.toDouble())
    TerrainArea(latitude, longitude, widthMeters.toDouble())
    return TerrainPanDestination(LatLong(latitude, longitude), hypot(east.toDouble(), north.toDouble()) >= safeRadius)
}

internal class TerrainArea(
    latitude: Double,
    longitude: Double,
    private val widthMeters: Double,
) {
    private val mapSize = 1L shl 30
    private val centerX = MercatorProjection.longitudeToPixelX(longitude, mapSize)
    private val centerY = MercatorProjection.latitudeToPixelY(latitude, mapSize)
    private val metersPerWorld = MercatorProjection.EARTH_CIRCUMFERENCE * cos(Math.toRadians(latitude))
    private val widthPixels = widthMeters * mapSize / metersPerWorld

    init {
        require(latitude.isFinite() && longitude.isFinite() && widthMeters.isFinite() && widthMeters > 0)
        require(latitude in -84.0..84.0 && longitude in -180.0..180.0)
        require(centerX - widthPixels / 2 >= 0 && centerX + widthPixels / 2 <= mapSize)
        require(centerY - widthPixels / 2 >= 0 && centerY + widthPixels / 2 <= mapSize)
    }

    fun coordinate(
        u: Double,
        v: Double,
    ): Pair<Double, Double> =
        MercatorProjection.pixelYToLatitude(centerY + (v - 0.5) * widthPixels, mapSize) to
            MercatorProjection.pixelXToLongitude(centerX + (u - 0.5) * widthPixels, mapSize)

    fun coordinateForMeters(
        east: Double,
        north: Double,
    ): Pair<Double, Double> = coordinate(0.5 + east / widthMeters, 0.5 - north / widthMeters)

    fun texturePosition(
        latitude: Double,
        longitude: Double,
    ): Pair<Double, Double> =
        (0.5 + (MercatorProjection.longitudeToPixelX(longitude, mapSize) - centerX) / widthPixels) to
            (0.5 + (MercatorProjection.latitudeToPixelY(latitude, mapSize) - centerY) / widthPixels)

    fun metersForCoordinate(
        latitude: Double,
        longitude: Double,
    ): Pair<Float, Float> {
        val (u, v) = texturePosition(latitude, longitude)
        return ((u - 0.5) * widthMeters).toFloat() to ((0.5 - v) * widthMeters).toFloat()
    }
}
