package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlin.math.sqrt

internal const val TERRAIN_VERTICAL_EXAGGERATION = 1.25f

internal data class TerrainMesh(
    val vertices: FloatArray,
    val indices: ShortArray,
    val light: FloatArray,
)

internal fun buildTerrainMesh(
    size: Int,
    widthMeters: Float,
    elevationAt: (u: Double, v: Double) -> Double?,
): TerrainMesh? {
    require(size in 2..255 && widthMeters.isFinite() && widthMeters > 0f)
    val elevations = DoubleArray(size * size)
    var total = 0.0
    for (row in 0 until size) {
        for (column in 0 until size) {
            val elevation = elevationAt(column.toDouble() / (size - 1), row.toDouble() / (size - 1))
            if (elevation == null || !elevation.isFinite()) return null
            elevations[row * size + column] = elevation
            total += elevation
        }
    }
    val mean = total / elevations.size
    val vertices = FloatArray(size * size * 5)
    val light = FloatArray(size * size)
    for (row in 0 until size) {
        for (column in 0 until size) {
            val u = column.toFloat() / (size - 1)
            val v = row.toFloat() / (size - 1)
            val offset = (row * size + column) * 5
            vertices[offset] = (u - 0.5f) * widthMeters
            vertices[offset + 1] = ((elevations[row * size + column] - mean) * TERRAIN_VERTICAL_EXAGGERATION).toFloat()
            vertices[offset + 2] = (v - 0.5f) * widthMeters
            vertices[offset + 3] = u
            vertices[offset + 4] = v
            val left = (column - 1).coerceAtLeast(0)
            val right = (column + 1).coerceAtMost(size - 1)
            val up = (row - 1).coerceAtLeast(0)
            val down = (row + 1).coerceAtMost(size - 1)
            val spacing = widthMeters / (size - 1)
            val dx =
                (elevations[row * size + right] - elevations[row * size + left]) *
                    TERRAIN_VERTICAL_EXAGGERATION / ((right - left) * spacing)
            val dz =
                (elevations[down * size + column] - elevations[up * size + column]) *
                    TERRAIN_VERTICAL_EXAGGERATION / ((down - up) * spacing)
            val illumination = (1.0 - 0.5 * dx + 0.5 * dz) / sqrt(dx * dx + dz * dz + 1.0)
            light[row * size + column] = (0.8 + 0.2 * illumination).toFloat().coerceIn(0.75f, 1f)
        }
    }
    val indices = ShortArray((size - 1) * (size - 1) * 6)
    var index = 0
    for (row in 0 until size - 1) {
        for (column in 0 until size - 1) {
            val a = row * size + column
            val b = a + 1
            val c = a + size
            val d = c + 1
            indices[index++] = a.toShort()
            indices[index++] = c.toShort()
            indices[index++] = b.toShort()
            indices[index++] = b.toShort()
            indices[index++] = c.toShort()
            indices[index++] = d.toShort()
        }
    }
    return TerrainMesh(vertices, indices, light)
}
