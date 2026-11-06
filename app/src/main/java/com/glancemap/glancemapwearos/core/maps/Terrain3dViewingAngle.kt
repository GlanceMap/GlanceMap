package com.glancemap.glancemapwearos.core.maps

internal const val DEFAULT_TERRAIN_3D_VIEWING_ANGLE = 45
internal val TERRAIN_3D_VIEWING_ANGLES = listOf(45, 50, 55, 60, 65, 70)

internal fun sanitizeTerrain3dViewingAngle(
    angle: Int?,
): Int = angle?.takeIf { it in TERRAIN_3D_VIEWING_ANGLES } ?: DEFAULT_TERRAIN_3D_VIEWING_ANGLE
