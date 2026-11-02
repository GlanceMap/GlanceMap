package com.glancemap.glancemapcompanionapp.map

import earth.worldwind.geom.Vec3
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.heatmap.ElevationHeatmapLayer
import earth.worldwind.layer.shadow.ShadowLayer
import earth.worldwind.render.RenderContext
import kotlin.math.cos
import kotlin.math.sin

/** A fixed north-west light keeps mountain relief legible regardless of the time of day. */
internal fun phoneWorldWindReliefDirection(
    latitudeRadians: Double,
    longitudeRadians: Double,
    result: Vec3,
): Vec3 {
    val sinLat = sin(latitudeRadians)
    val cosLat = cos(latitudeRadians)
    val sinLon = sin(longitudeRadians)
    val cosLon = cos(longitudeRadians)
    return result
        .set(
            0.70710678 * cosLat * sinLon - 0.5 * sinLat * sinLon - 0.5 * cosLon,
            0.70710678 * sinLat + 0.5 * cosLat,
            0.70710678 * cosLat * cosLon - 0.5 * sinLat * cosLon + 0.5 * sinLon,
        ).normalize()
}

/** Keep WorldWind's normal-based Lambert relief without allocating cascaded shadow maps. */
internal class PhoneWorldWindReliefLayer(
    strength: Float,
) : ShadowLayer() {
    init {
        terrainLambert = strength
        isTerrainCastingEnabled = false
    }

    override fun doRender(rc: RenderContext) {
        if (rc.globe.is2D || rc.isPickMode) return
        shadowState.terrainLambert = terrainLambert
        shadowState.lightDirection.copy(rc.lightDirection)
        shadowState.isReady = false
        rc.shadowState = shadowState
    }
}

/**
 * Clearing WorldWind's default layers also removed its atmosphere, which left black above the horizon in a
 * tilted view. Only the sky is drawn: the ground scattering is off so map colours stay true at any height.
 */
internal fun phoneWorldWindSkyLayer(): AtmosphereLayer =
    object : AtmosphereLayer() {
        // Equal fade altitudes would mean full ground scattering, so the ground pass is skipped instead.
        override fun renderGround(rc: RenderContext) = Unit
    }

/** Satellite and other online imagery already carries natural shading, so it gets a lighter maximum. */
private const val PHONE_WORLDWIND_MAX_OFFLINE_RELIEF = 0.56f
private const val PHONE_WORLDWIND_MAX_ONLINE_RELIEF = 0.24f
private const val PHONE_WORLDWIND_ELEVATION_COLORS_OPACITY = 0.45f

/** 50% reproduces the fixed relief used before the setting existed: 0.28 offline and 0.12 online. */
internal fun phoneWorldWindReliefStrength(
    percent: Int,
    offline: Boolean,
): Float {
    val maximum = if (offline) PHONE_WORLDWIND_MAX_OFFLINE_RELIEF else PHONE_WORLDWIND_MAX_ONLINE_RELIEF
    return maximum * percent.coerceIn(0, 100) / 100f
}

/** Colours span the lowest to highest loaded terrain in view, so the scale adapts as the camera moves. */
internal fun phoneWorldWindElevationColorsLayer(): ElevationHeatmapLayer =
    ElevationHeatmapLayer().apply {
        opacity = PHONE_WORLDWIND_ELEVATION_COLORS_OPACITY
        isEnabled = false
    }
