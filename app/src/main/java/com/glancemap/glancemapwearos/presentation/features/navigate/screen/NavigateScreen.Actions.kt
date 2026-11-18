package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal enum class StayToggleAction { SHOW_INFO, TURN_OFF, TURN_ON }

internal enum class StayNotificationAction { REQUEST_PERMISSION, SHOW_EXPLANATION }

internal fun stayNotificationAction(justExplained: Boolean): StayNotificationAction =
    if (justExplained) {
        StayNotificationAction.REQUEST_PERMISSION
    } else {
        StayNotificationAction.SHOW_EXPLANATION
    }

internal fun stayToggleAction(
    enabled: Boolean,
    tipShown: Boolean,
): StayToggleAction =
    when {
        !tipShown -> StayToggleAction.SHOW_INFO
        enabled -> StayToggleAction.TURN_OFF
        else -> StayToggleAction.TURN_ON
    }

internal data class NavigateScreenActions(
    val triggerHaptic: () -> Unit,
    val continueKeepAppOpenEnableFlow: () -> Unit,
    val toggleKeepAppOpen: () -> Unit,
    val toggleShortcutTray: () -> Unit,
)

@Composable
internal fun rememberNavigateScreenActions(
    context: Context,
    settingsViewModel: SettingsViewModel,
    locationPermissionState: LocationPermissionState,
    notificationPermissionState: NotificationPermissionState,
    keepAppOpen: Boolean,
    keepAppOpenTipShown: Boolean,
    offlineMode: Boolean,
    setPendingKeepAppOpen: (Boolean) -> Unit,
    setShowKeepAppOpenInfoDialog: (Boolean) -> Unit,
    setShortcutTrayExpanded: (Boolean) -> Unit,
    isShortcutTrayExpanded: Boolean,
): NavigateScreenActions {
    val vibrator = remember { vibratorFrom(context) }
    val scope = rememberCoroutineScope()
    var lastVibrationTime by remember { mutableLongStateOf(0L) }
    val hapticIntervalMs = 150L

    fun triggerHaptic() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVibrationTime < hapticIntervalMs) return
        lastVibrationTime = now
        scope.launch(Dispatchers.Default) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun continueKeepAppOpenEnableFlow() {
        setPendingKeepAppOpen(true)

        if (!offlineMode && !locationPermissionState.hasLocationPermission) {
            locationPermissionState.launchPermissions()
            return
        }

        if (
            notificationPermissionState.isPermissionRequired &&
            !notificationPermissionState.hasNotificationPermission
        ) {
            notificationPermissionState.launchPermissionRequest()
            return
        }

        settingsViewModel.setKeepAppOpen(true)
        setPendingKeepAppOpen(false)
    }

    fun toggleKeepAppOpen() {
        triggerHaptic()

        when (stayToggleAction(keepAppOpen, keepAppOpenTipShown)) {
            StayToggleAction.SHOW_INFO -> {
                setShowKeepAppOpenInfoDialog(true)
                setPendingKeepAppOpen(!keepAppOpen)
                settingsViewModel.setKeepAppOpenTipShown(true)
            }
            StayToggleAction.TURN_OFF -> {
                settingsViewModel.setKeepAppOpen(false)
                setPendingKeepAppOpen(false)
                setShowKeepAppOpenInfoDialog(false)
            }
            StayToggleAction.TURN_ON -> continueKeepAppOpenEnableFlow()
        }
    }

    fun toggleShortcutTray() {
        triggerHaptic()
        setShortcutTrayExpanded(!isShortcutTrayExpanded)
    }

    return NavigateScreenActions(
        triggerHaptic = ::triggerHaptic,
        continueKeepAppOpenEnableFlow = ::continueKeepAppOpenEnableFlow,
        toggleKeepAppOpen = ::toggleKeepAppOpen,
        toggleShortcutTray = ::toggleShortcutTray,
    )
}
