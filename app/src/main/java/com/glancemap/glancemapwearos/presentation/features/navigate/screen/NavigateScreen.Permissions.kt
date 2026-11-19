
package com.glancemap.glancemapwearos.presentation.features.navigate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry

/**
 * A composable that remembers the state of location permissions.
 * This exclusively handles foreground location permissions (fine or coarse).
 */
@Composable
fun rememberLocationPermissionState(
    context: Context,
    refreshKey: Any? = Unit,
    onPermissionsResult: (Boolean) -> Unit,
): LocationPermissionState {
    var hasLocationPermission by remember(context, refreshKey) {
        mutableStateOf(hasForegroundLocationPermission(context))
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { permissions ->
                val fineGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
                val coarseGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
                hasLocationPermission = fineGranted || coarseGranted
                onPermissionsResult(hasLocationPermission)
            },
        )

    return LocationPermissionState(
        hasLocationPermission = hasLocationPermission,
        launchPermissions = {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        },
    )
}

/**
 * State holder for foreground location permission.
 */
data class LocationPermissionState(
    val hasLocationPermission: Boolean,
    val launchPermissions: () -> Unit,
)

internal fun hasForegroundLocationPermission(context: Context): Boolean {
    val fineGranted =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

/**
 * A composable that remembers the state of notification permissions.
 */
@Composable
fun rememberNotificationPermissionState(
    context: Context,
    refreshKey: Any? = Unit,
    onPermissionResult: (Boolean) -> Unit = {},
): NotificationPermissionState {
    // Notification permission is only required on API 33+
    val isPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (isPermissionRequired) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
    }

    LaunchedEffect(context, refreshKey) {
        hasNotificationPermission =
            !isPermissionRequired ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                hasNotificationPermission = isGranted
                onPermissionResult(isGranted)
            },
        )

    return NotificationPermissionState(
        hasNotificationPermission = hasNotificationPermission,
        isPermissionRequired = isPermissionRequired,
        launchPermissionRequest = {
            if (isPermissionRequired) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

/**
 * State holder for notification permission.
 */
data class NotificationPermissionState(
    val hasNotificationPermission: Boolean,
    val isPermissionRequired: Boolean,
    val launchPermissionRequest: () -> Unit,
)

@Composable
internal fun rememberRecordingStartWithActivityPermission(
    context: Context,
    onStartRecording: () -> Unit,
): () -> Unit {
    val currentOnStartRecording by rememberUpdatedState(onStartRecording)
    val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val requestPreferences =
        remember(context) {
            context.getSharedPreferences(RECORDING_PERMISSION_PREFS, Context.MODE_PRIVATE)
        }
    var requestInProgress by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted ->
                requestInProgress = false
                DebugTelemetry.log(
                    "TraceRecordingSensors",
                    "event=activity_permission_result granted=$granted source=first_recording_start",
                )
                currentOnStartRecording()
            },
        )

    return {
        val permissionGranted =
            !permissionRequired ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION,
                ) == PackageManager.PERMISSION_GRANTED
        val alreadyRequested = requestPreferences.getBoolean(RECORDING_ACTIVITY_PERMISSION_REQUESTED, false)
        if (permissionGranted || alreadyRequested) {
            currentOnStartRecording()
        } else if (!requestInProgress) {
            requestInProgress = true
            requestPreferences.edit().putBoolean(RECORDING_ACTIVITY_PERMISSION_REQUESTED, true).apply()
            DebugTelemetry.log(
                "TraceRecordingSensors",
                "event=activity_permission_request source=first_recording_start",
            )
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
}

private const val RECORDING_PERMISSION_PREFS = "recording_permission_prefs"
private const val RECORDING_ACTIVITY_PERMISSION_REQUESTED = "activity_recognition_requested"
