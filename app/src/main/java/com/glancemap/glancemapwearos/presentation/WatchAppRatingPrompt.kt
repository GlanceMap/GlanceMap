@file:Suppress("FunctionName")

package com.glancemap.glancemapwearos.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog

private const val RATING_PROMPT_PREFS = "watch_app_rating_prompt"
private const val RATING_PROMPT_SHOWN = "shown"
internal const val WATCH_APP_PACKAGE_NAME = "com.glancemap.glancemapwearos"
internal const val WATCH_APP_PLAY_STORE_PACKAGE_NAME = "com.android.vending"
internal const val WATCH_APP_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=$WATCH_APP_PACKAGE_NAME"

@Composable
internal fun WatchAppRatingPrompt() {
    val context = LocalContext.current.applicationContext
    val preferences =
        remember(context) {
            context.getSharedPreferences(RATING_PROMPT_PREFS, Context.MODE_PRIVATE)
        }
    var visible by
        remember {
            mutableStateOf(
                shouldShowWatchAppRatingPrompt(
                    promptShown = preferences.getBoolean(RATING_PROMPT_SHOWN, false),
                ),
            )
        }

    LaunchedEffect(Unit) {
        if (visible) {
            preferences.edit().putBoolean(RATING_PROMPT_SHOWN, true).apply()
        }
    }

    WearActionDialog(
        visible = visible,
        title = "Enjoying GlanceMap?",
        message = "Please take a moment to rate GlanceMap on Google Play.",
        confirmText = "Rate app",
        onConfirm = {
            visible = false
            openWatchAppPlayStore(context)
        },
        onDismissRequest = { visible = false },
        dismissText = "Not now",
        onDismiss = { visible = false },
    )
}

internal fun shouldShowWatchAppRatingPrompt(promptShown: Boolean): Boolean = !promptShown

internal fun buildWatchAppPlayStoreIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(WATCH_APP_PLAY_STORE_URL)).apply {
        setPackage(WATCH_APP_PLAY_STORE_PACKAGE_NAME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun openWatchAppPlayStore(context: Context) {
    try {
        context.startActivity(buildWatchAppPlayStoreIntent())
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(WATCH_APP_PLAY_STORE_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (_: ActivityNotFoundException) {
            // No Play Store or browser is available on this watch.
        }
    }
}
