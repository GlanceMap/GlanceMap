package com.glancemap.glancemapcompanionapp.map

internal data class MapNavigationActions(
    val onOpenSettings: (MapTool) -> Unit = {},
    val onMapLegend: () -> Unit = {},
)
