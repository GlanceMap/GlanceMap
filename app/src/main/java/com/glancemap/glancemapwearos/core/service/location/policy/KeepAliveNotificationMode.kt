package com.glancemap.glancemapwearos.core.service.location.policy

import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive

internal enum class KeepAliveNotificationMode {
    OFF,
    PINNED_NOTIFICATION,
    LOCATION_FOREGROUND,
}

internal fun locationKeepAliveNotificationMode(
    keepAppOpen: Boolean,
    trackingEnabled: Boolean,
    screenState: LocationScreenState,
    backgroundGpsEnabled: Boolean,
    runtimeReason: String,
): KeepAliveNotificationMode {
    val locationAllowed = trackingEnabled && (screenState.isInteractive || backgroundGpsEnabled)
    val sessionActive =
        when (runtimeReason) {
            NavigationRuntimeDemandReason.GUIDANCE_VISIBLE,
            NavigationRuntimeDemandReason.GUIDANCE_AMBIENT,
            NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND,
            NavigationRuntimeDemandReason.RECORDING,
            NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED,
            NavigationRuntimeDemandReason.RECORDING_GUIDANCE,
            -> true
            else -> false
        }
    return when {
        sessionActive && locationAllowed -> KeepAliveNotificationMode.LOCATION_FOREGROUND
        !keepAppOpen -> KeepAliveNotificationMode.OFF
        locationAllowed -> KeepAliveNotificationMode.LOCATION_FOREGROUND
        else -> KeepAliveNotificationMode.PINNED_NOTIFICATION
    }
}
