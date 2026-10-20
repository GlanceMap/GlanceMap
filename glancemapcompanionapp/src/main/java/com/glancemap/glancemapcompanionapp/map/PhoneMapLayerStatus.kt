package com.glancemap.glancemapcompanionapp.map

internal fun PhoneMapSettings.hasActiveMapLayer(
    offlineBase: Boolean,
    hasElevationData: Boolean,
    comparisonActive: Boolean,
): Boolean =
    comparisonActive ||
        (hillShadingEnabled && (!offlineBase || hasElevationData)) ||
        (offlineBase && hasElevationData && reliefOverlayEnabled && reliefOverlayOpacityPercent > 0)
