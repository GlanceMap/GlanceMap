package com.glancemap.glancemapcompanionapp.map

import kotlin.math.pow

// Mapsforge scales the whole framebuffer at fractional zoom, so counter-scale the marker bitmap.
internal fun phoneOfflineMarkerBitmapScale(
    zoomLevel: Byte,
    mapScaleFactor: Double,
): Float = (2.0.pow(zoomLevel.toInt()) / mapScaleFactor).toFloat()
