package com.glancemap.glancemapcompanionapp.map

internal data class MapNavigationActions(
    val onOpenLibrary: () -> Unit = {},
    val onOpenSettings: (MapTool) -> Unit = {},
    val onSendToWatch: () -> Unit = {},
    val onLiveHike: () -> Unit = {},
    val onMapLegend: () -> Unit = {},
    val onLiveHikeSyncChanged: (Boolean) -> Unit = {},
)
