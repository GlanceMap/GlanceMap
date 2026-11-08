package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowLayer
import earth.worldwind.render.RenderContext
import kotlin.math.cos
import kotlin.math.sin

internal fun worldWindReliefDirection(
    latitude: Double,
    longitude: Double,
    result: Vec3,
): Vec3 {
    val sinLat = sin(latitude)
    val cosLat = cos(latitude)
    val sinLon = sin(longitude)
    val cosLon = cos(longitude)
    return result
        .set(
            0.70710678 * cosLat * sinLon - 0.5 * sinLat * sinLon - 0.5 * cosLon,
            0.70710678 * sinLat + 0.5 * cosLat,
            0.70710678 * cosLat * cosLon - 0.5 * sinLat * cosLon + 0.5 * sinLon,
        ).normalize()
}

/** Enables normal-based terrain shading without allocating shadow maps. */
internal class WorldWindReliefLayer : ShadowLayer() {
    init {
        terrainLambert = 0.28f
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
