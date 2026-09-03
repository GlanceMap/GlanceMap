package com.glancemap.glancemapwearos.presentation.features.navigate

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class NavigateScreenLifecycleState(
    val isScreenResumed: Boolean,
    val lastScreenResumeElapsedMs: Long,
    val menuClickWakeElapsedMs: Long,
    val menuClickGuardUntilElapsedMs: Long,
)

internal data class NavigateMenuClickGuardState(
    val observedNonInteractive: Boolean = false,
    val wakeElapsedMs: Long = 0L,
    val guardUntilElapsedMs: Long = 0L,
)

internal fun updateNavigateMenuClickGuard(
    previous: NavigateMenuClickGuardState,
    isDeviceInteractive: Boolean,
    nowElapsedMs: Long,
): NavigateMenuClickGuardState =
    when {
        !isDeviceInteractive -> previous.copy(observedNonInteractive = true)
        !previous.observedNonInteractive -> previous
        else ->
            NavigateMenuClickGuardState(
                wakeElapsedMs = nowElapsedMs,
                guardUntilElapsedMs = nowElapsedMs + NAVIGATE_MENU_CLICK_RESUME_GUARD_MS,
            )
    }

@Composable
internal fun rememberNavigateScreenLifecycleState(
    isDeviceInteractive: Boolean,
): NavigateScreenLifecycleState {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isScreenResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var lastScreenResumeElapsedMs by remember(lifecycleOwner) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    var menuClickGuardState by remember(lifecycleOwner) {
        mutableStateOf(NavigateMenuClickGuardState())
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        val nowElapsedMs = SystemClock.elapsedRealtime()
                        isScreenResumed = true
                        lastScreenResumeElapsedMs = nowElapsedMs
                    }
                    Lifecycle.Event.ON_PAUSE -> isScreenResumed = false
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isScreenResumed, isDeviceInteractive) {
        if (isScreenResumed && isDeviceInteractive) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            lastScreenResumeElapsedMs = nowElapsedMs
        }
    }

    LaunchedEffect(isDeviceInteractive) {
        menuClickGuardState =
            updateNavigateMenuClickGuard(
                previous = menuClickGuardState,
                isDeviceInteractive = isDeviceInteractive,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
    }

    return NavigateScreenLifecycleState(
        isScreenResumed = isScreenResumed,
        lastScreenResumeElapsedMs = lastScreenResumeElapsedMs,
        menuClickWakeElapsedMs = menuClickGuardState.wakeElapsedMs,
        menuClickGuardUntilElapsedMs = menuClickGuardState.guardUntilElapsedMs,
    )
}

private const val NAVIGATE_MENU_CLICK_RESUME_GUARD_MS = 1_000L
