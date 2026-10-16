package com.glancemap.glancemapcompanionapp.map

internal data class MapNavigationActions(
    val onOpenSettings: (MapTool) -> Unit = {},
    val onMapLegend: (List<PhoneMapLegendView>) -> Unit = {},
)

internal data class PhoneMapLegendView(
    val label: String,
    val sourceLabel: String,
    val themeId: String?,
)
