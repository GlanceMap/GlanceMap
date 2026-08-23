package com.glancemap.trailcore.map

/** Features a renderer can implement for the shared [MapIntent.appearance] preferences. */
data class MapRendererCapabilities(
    val hillshade: Boolean = false,
    val slopeOverlay: Boolean = false,
    val contoursToggle: Boolean = false,
    val themes: Boolean = false,
)
