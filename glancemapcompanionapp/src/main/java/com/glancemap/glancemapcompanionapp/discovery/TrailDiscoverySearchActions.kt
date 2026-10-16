package com.glancemap.glancemapcompanionapp.discovery

/** Map inputs stay with the map owner; the panel only requests a search origin. */
internal data class TrailDiscoverySearchActions(
    val onNearMe: () -> Unit = {},
    val onChoosePoint: () -> Unit = {},
    val onUseMapCenter: () -> Unit = {},
    val onCancelPoint: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val pickingPoint: Boolean = false,
    val waitingForLocation: Boolean = false,
)
