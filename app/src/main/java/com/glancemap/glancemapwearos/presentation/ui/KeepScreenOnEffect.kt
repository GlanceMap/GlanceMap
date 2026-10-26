package com.glancemap.glancemapwearos.presentation.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
@Suppress("FunctionNaming")
internal fun KeepScreenOnEffect(screenBrightness: Float? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity, view, screenBrightness) {
        val window = activity?.window
        val previousKeepScreenOn = view.keepScreenOn
        val previousBrightness = window?.attributes?.screenBrightness
        val hadKeepScreenOnFlag =
            window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        view.keepScreenOn = true
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (screenBrightness != null && window != null) {
            window.attributes =
                window.attributes.apply {
                    this.screenBrightness =
                        screenBrightness.coerceIn(MINIMUM_SCREEN_BRIGHTNESS, MAXIMUM_SCREEN_BRIGHTNESS)
                }
        }
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
            if (window != null) {
                if (previousBrightness != null) {
                    window.attributes =
                        window.attributes.apply {
                            this.screenBrightness = previousBrightness
                        }
                }
                if (hadKeepScreenOnFlag) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val MINIMUM_SCREEN_BRIGHTNESS = 0.01f
private const val MAXIMUM_SCREEN_BRIGHTNESS = 1f
