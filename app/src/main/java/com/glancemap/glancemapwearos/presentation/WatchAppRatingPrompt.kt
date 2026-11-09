@file:Suppress("FunctionName")

package com.glancemap.glancemapwearos.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog

private const val RATING_PROMPT_PREFS = "watch_app_rating_prompt"
private const val RATING_PROMPT_LAST_VERSION = "last_version_shown"
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
    val releaseNotes = stringArrayResource(R.array.watch_whats_new_notes).filter { it.isNotBlank() }
    var visible by
        remember(releaseNotes) {
            mutableStateOf(
                shouldShowWatchAppRatingPrompt(
                    lastShownVersion = preferences.getInt(RATING_PROMPT_LAST_VERSION, 0),
                    currentVersion = BuildConfig.VERSION_CODE,
                    hasReleaseNotes = releaseNotes.isNotEmpty(),
                ),
            )
        }

    LaunchedEffect(visible) {
        if (visible) {
            preferences.edit().putInt(RATING_PROMPT_LAST_VERSION, BuildConfig.VERSION_CODE).apply()
        }
    }

    WearInfoDialog(
        visible = visible,
        title = stringResource(R.string.watch_whats_new_title, BuildConfig.VERSION_NAME),
        onDismiss = { visible = false },
        swipeDownToDismiss = true,
    ) {
        items(releaseNotes.size) { index ->
            Text(text = "• ${releaseNotes[index]}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        item {
            Text(
                text = stringResource(R.string.common_please_take_a_moment_to_rate_glancemap_on_google_play),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        item {
            Button(onClick = {
                visible = false
                openWatchAppPlayStore(context)
            }) {
                Text(stringResource(R.string.common_rate_app))
            }
        }
    }
}

internal fun shouldShowWatchAppRatingPrompt(
    lastShownVersion: Int,
    currentVersion: Int,
    hasReleaseNotes: Boolean,
): Boolean = hasReleaseNotes && lastShownVersion < currentVersion

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
