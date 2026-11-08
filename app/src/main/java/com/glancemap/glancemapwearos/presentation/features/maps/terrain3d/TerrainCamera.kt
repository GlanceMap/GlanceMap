package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

private val TERRAIN_ANGLE_PRESETS = listOf(0, 60, 80)

internal fun nextTerrainAnglePreset(angle: Int): Int = TERRAIN_ANGLE_PRESETS.firstOrNull { it > angle } ?: 0

internal fun isTerrainAnglePreset(angle: Int): Boolean = angle in TERRAIN_ANGLE_PRESETS

internal const val INITIAL_TERRAIN_CAMERA_DISTANCE = 4000f
internal const val TERRAIN_FIELD_OF_VIEW_DEGREES = 30f
internal const val TERRAIN_LOG_TAG = "Terrain3D"
