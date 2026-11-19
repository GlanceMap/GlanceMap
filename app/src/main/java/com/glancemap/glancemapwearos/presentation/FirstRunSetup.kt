@file:Suppress("FunctionNaming")

package com.glancemap.glancemapwearos.presentation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.location.config.CHANNEL_ID
import com.glancemap.glancemapwearos.presentation.features.navigate.LocationViewModel
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsViewModel
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog
import com.glancemap.glancemapwearos.presentation.ui.WearInfoText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val FIRST_RUN_PREFS = "watch_first_run_setup"
private const val FIRST_RUN_STEP = "step"

internal class FirstRunSetupState(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences(FIRST_RUN_PREFS, Context.MODE_PRIVATE)
    private val ratingPreferences = context.getSharedPreferences(RATING_PROMPT_PREFS, Context.MODE_PRIVATE)
    private val savedStep = preferences.getString(FIRST_RUN_STEP, null)
    var step by mutableStateOf(initialStep())
        private set
    val active: Boolean get() = step != FirstRunStep.DONE

    @Suppress("ReturnCount")
    private fun initialStep(): FirstRunStep {
        savedStep?.let { return runCatching { FirstRunStep.valueOf(it) }.getOrDefault(FirstRunStep.DONE) }
        @Suppress("DEPRECATION")
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val start =
            packageInfo != null &&
                shouldStartFirstRunSetup(
                    packageInfo.firstInstallTime,
                    packageInfo.lastUpdateTime,
                    ratingPreferences.getInt(RATING_PROMPT_LAST_VERSION, 0),
                    completed = false,
                )
        if (!start) return FirstRunStep.DONE
        preferences.edit().putString(FIRST_RUN_STEP, FirstRunStep.WELCOME.name).apply()
        ratingPreferences.edit().putInt(RATING_PROMPT_LAST_VERSION, BuildConfig.VERSION_CODE).apply()
        return FirstRunStep.WELCOME
    }

    fun moveTo(next: FirstRunStep) {
        preferences.edit().putString(FIRST_RUN_STEP, next.name).apply()
        step = next
    }
}

@Composable
internal fun rememberFirstRunSetupState(): FirstRunSetupState {
    val context = LocalContext.current.applicationContext
    return remember(context) { FirstRunSetupState(context) }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
internal fun FirstRunSetup(
    state: FirstRunSetupState,
    settings: SettingsViewModel,
    location: LocationViewModel,
    onFinish: (Boolean) -> Unit,
) {
    if (!state.active) return
    val context = LocalContext.current
    val lifecycle = ProcessLifecycleOwner.get().lifecycle
    val notificationManager = remember(context) { NotificationManagerCompat.from(context) }
    val platformNotificationManager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    val scope = rememberCoroutineScope()
    var permissionPending by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(true) }

    fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotifications(): Boolean =
        hasNotificationPermission() &&
            notificationManager.areNotificationsEnabled() &&
            platformNotificationManager.getNotificationChannel(CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE

    fun enableStay() {
        if (preparing) return
        preparing = true
        scope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            if (!state.active) return@launch
            val notificationsGranted = hasNotifications()
            val granted = firstRunMayEnableStay(notificationsGranted)
            if (granted && location.startKeepOpenForSetup()) {
                settings.setKeepAppOpen(true).join()
                if (!state.active) {
                    settings.setKeepAppOpen(false).join()
                    return@launch
                }
                state.moveTo(FirstRunStep.MAP)
            } else {
                settings.setKeepAppOpen(false).join()
                state.moveTo(
                    firstRunStepWhenStayCannotStart(notificationsGranted),
                )
            }
            preparing = false
        }
    }

    val locationRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionPending = false
            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (!granted) settings.setKeepAppOpen(false)
            state.moveTo(nextFirstRunStepAfterLocation(granted))
        }

    fun requestLocation() {
        permissionPending = true
        locationRequest.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    val notificationsRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionPending = false
            if (granted && hasNotifications()) {
                enableStay()
            } else {
                settings.setKeepAppOpen(false)
                state.moveTo(nextFirstRunStepAfterNotifications(false))
            }
        }
    val settingsRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permissionPending = false
            when (state.step) {
                FirstRunStep.LOCATION_DENIED -> if (hasLocation()) state.moveTo(FirstRunStep.STAY)
                FirstRunStep.NOTIFICATIONS_DENIED -> if (hasNotifications()) enableStay()
                else -> Unit
            }
        }

    LaunchedEffect(state) {
        if (state.step == FirstRunStep.WELCOME) settings.setKeepAppOpen(false).join()
        preparing = false
    }

    fun finish(openMaps: Boolean) {
        if (state.step != FirstRunStep.MAP) settings.setKeepAppOpen(false)
        state.moveTo(FirstRunStep.DONE)
        onFinish(openMaps)
    }

    val step = state.step
    val title =
        when (step) {
            FirstRunStep.WELCOME -> R.string.first_run_welcome
            FirstRunStep.STAY -> R.string.first_run_stay
            FirstRunStep.LOCATION_DENIED -> R.string.first_run_location_off
            FirstRunStep.NOTIFICATIONS_DENIED -> R.string.first_run_stay_off
            FirstRunStep.STAY_UNAVAILABLE -> R.string.first_run_stay_unavailable
            FirstRunStep.MAP -> R.string.first_run_download_map
            FirstRunStep.DONE -> return
        }
    val message =
        when (step) {
            FirstRunStep.WELCOME -> R.string.first_run_welcome_message
            FirstRunStep.STAY -> R.string.first_run_stay_message
            FirstRunStep.LOCATION_DENIED -> R.string.first_run_location_off_message
            FirstRunStep.NOTIFICATIONS_DENIED -> R.string.first_run_stay_off_message
            FirstRunStep.STAY_UNAVAILABLE -> R.string.first_run_stay_unavailable_message
            FirstRunStep.MAP -> R.string.first_run_download_map_message
            FirstRunStep.DONE -> return
        }
    val progress =
        when (step) {
            FirstRunStep.WELCOME -> "1 / 3"
            FirstRunStep.STAY -> "2 / 3"
            FirstRunStep.MAP -> "3 / 3"
            else -> null
        }
    WearInfoDialog(
        visible = !permissionPending,
        title = stringResource(title),
        onDismiss = { finish(false) },
        showDragHandle = false,
        titleContent = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (progress != null) {
                    Text(progress, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (step == FirstRunStep.STAY) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
            }
        },
    ) {
        item {
            WearInfoText(stringResource(message))
        }
        item {
            Button(
                enabled = !preparing,
                onClick = {
                    when (step) {
                        FirstRunStep.WELCOME ->
                            if (hasLocation()) {
                                state.moveTo(FirstRunStep.STAY)
                            } else {
                                requestLocation()
                            }
                        FirstRunStep.STAY ->
                            if (hasNotifications()) {
                                enableStay()
                            } else if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()
                            ) {
                                permissionPending = true
                                notificationsRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                state.moveTo(FirstRunStep.NOTIFICATIONS_DENIED)
                            }
                        FirstRunStep.LOCATION_DENIED -> {
                            settings.setOfflineMode(true)
                            state.moveTo(nextFirstRunStepAfterLocation(granted = false, continueWithoutLocation = true))
                        }
                        FirstRunStep.NOTIFICATIONS_DENIED,
                        FirstRunStep.STAY_UNAVAILABLE,
                        -> state.moveTo(FirstRunStep.MAP)
                        FirstRunStep.MAP -> finish(true)
                        FirstRunStep.DONE -> Unit
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (step == FirstRunStep.MAP) R.string.first_run_choose_map else R.string.common_continue,
                    ),
                )
            }
        }
        if (step == FirstRunStep.LOCATION_DENIED) {
            item { Button(onClick = { requestLocation() }) { Text(stringResource(R.string.common_back)) } }
        }
        if (step == FirstRunStep.NOTIFICATIONS_DENIED) {
            item {
                Button(onClick = {
                    permissionPending = true
                    settingsRequest.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }) { Text(stringResource(R.string.first_run_permission_settings)) }
            }
        }
        if (step == FirstRunStep.MAP) {
            item { Button(onClick = { finish(false) }) { Text(stringResource(R.string.navigate_later)) } }
        }
    }
}
