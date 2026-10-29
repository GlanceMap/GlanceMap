package com.glancemap.glancemapcompanionapp.map

import earth.worldwind.geom.Vec3
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
