package com.glancemap.glancemapcompanionapp.map

internal data class PhoneMapPoiMarkerAppearanceKey(
    val iconSize: PhoneMapPoiIconSize,
    val markerStyle: PhoneMapPoiMarkerStyle,
)

internal val PhoneMapPoiSettings.markerAppearanceKey: PhoneMapPoiMarkerAppearanceKey
    get() = PhoneMapPoiMarkerAppearanceKey(iconSize = iconSize, markerStyle = markerStyle)
