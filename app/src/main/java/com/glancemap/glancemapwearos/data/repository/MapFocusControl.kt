package com.glancemap.glancemapwearos.data.repository

enum class MapFocusControl {
    MENU_BUTTON,
    ZOOM_PLUS,
    ZOOM_MINUS,
    NORTH_INDICATOR,
    NAV_MODE_BUTTON,
    SCALE,
    TIME,
    LIVE_METRICS,
}

internal fun decodeMapFocusControls(stored: Set<String>?): Set<MapFocusControl> {
    val names = stored.orEmpty()
    return MapFocusControl.entries.filterTo(mutableSetOf()) { it.name in names }
}
