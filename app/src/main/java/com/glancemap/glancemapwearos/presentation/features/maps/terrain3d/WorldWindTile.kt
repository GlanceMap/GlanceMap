package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

internal data class WorldWindTileKey(
    val map: String,
    val theme: String,
    val scale: Float,
    val z: Int,
    val x: Int,
    val y: Int,
    val resolution: Int,
)

internal data class WorldWindTileBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun intersects(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): Boolean = this.south <= north && this.north >= south && this.west <= east && this.east >= west
}

internal fun worldWindTileBounds(
    z: Int,
    x: Int,
    y: Int,
): WorldWindTileBounds {
    require(z in 0..20)
    val count = 1 shl z
    require(x in 0 until count && y in 0 until count)

    fun latitude(row: Int): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * row / count))))
    return WorldWindTileBounds(
        latitude(y + 1),
        x.toDouble() / count * 360.0 - 180.0,
        latitude(y),
        (x + 1.0) / count * 360.0 - 180.0,
    )
}
