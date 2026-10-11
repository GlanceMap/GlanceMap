package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.map.model.MapViewPosition

internal fun MapViewPosition.setZoomLevelRange(
    min: Byte,
    max: Byte,
) {
    val currentMin = zoomLevelMin
    val currentMax = zoomLevelMax
    if (min > currentMax) {
        if (max != currentMax) setZoomLevelMax(max)
        if (min != currentMin) setZoomLevelMin(min)
    } else {
        if (min != currentMin) setZoomLevelMin(min)
        if (max != currentMax) setZoomLevelMax(max)
    }
}
