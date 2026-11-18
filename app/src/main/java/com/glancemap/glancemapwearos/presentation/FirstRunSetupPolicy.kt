@file:Suppress("MatchingDeclarationName")

package com.glancemap.glancemapwearos.presentation

internal enum class FirstRunStep {
    WELCOME,
    LOCATION_DENIED,
    STAY,
    NOTIFICATIONS_DENIED,
    STAY_UNAVAILABLE,
    MAP,
    DONE,
}

internal fun shouldStartFirstRunSetup(
    firstInstallTime: Long,
    lastUpdateTime: Long,
    lastShownVersion: Int,
    completed: Boolean,
): Boolean = !completed && lastShownVersion == 0 && firstInstallTime > 0L && firstInstallTime == lastUpdateTime

internal fun nextFirstRunStepAfterLocation(
    granted: Boolean,
    continueWithoutLocation: Boolean = false,
): FirstRunStep = if (granted || continueWithoutLocation) FirstRunStep.STAY else FirstRunStep.LOCATION_DENIED

internal fun firstRunStepWhenStayPermissionsMissing(
    locationGranted: Boolean,
    notificationsGranted: Boolean,
): FirstRunStep =
    when {
        !notificationsGranted -> FirstRunStep.NOTIFICATIONS_DENIED
        !locationGranted -> FirstRunStep.MAP
        else -> FirstRunStep.STAY_UNAVAILABLE
    }

internal fun nextFirstRunStepAfterNotifications(
    granted: Boolean,
): FirstRunStep = if (granted) FirstRunStep.MAP else FirstRunStep.NOTIFICATIONS_DENIED

internal fun firstRunMayEnableStay(
    locationGranted: Boolean,
    notificationsGranted: Boolean,
): Boolean = locationGranted && notificationsGranted
