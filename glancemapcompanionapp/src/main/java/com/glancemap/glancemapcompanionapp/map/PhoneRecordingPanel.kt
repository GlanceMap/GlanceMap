package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.recording.RecordingSessionPhase
import com.glancemap.trailcore.recording.RecordingSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val phoneRecordingActiveColor = Color(0xFFD32F2F)
private val phoneRecordingPausedColor = Color(0xFFFFD54F)
private val phoneRecordingPausedContentColor = Color(0xFF202020)

@Composable
internal fun rememberPhoneRecordingStartAction(
    onLocationPermissionResult: (Boolean) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val hasLocation = PhoneMapLocationContext.hasPermission(context)
            onLocationPermissionResult(hasLocation)
            if (hasLocation) PhoneRecordingService.request(context, PhoneRecordingService.ACTION_START)
        }
    return {
        if (PhoneMapLocationContext.hasPermission(context) &&
            !needsNotificationPermission(context) &&
            !needsActivityPermission(context)
        ) {
            PhoneRecordingService.request(context, PhoneRecordingService.ACTION_START)
        } else {
            permissionLauncher.launch(recordingPermissions())
        }
    }
}

@Composable
// The controls share permission and session state; splitting those decisions risks inconsistent actions.
@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
internal fun PhoneRecordingPanel(
    general: MapToolsGeneralState,
    onGeneralSettingsChanged: (PhoneGeneralSettings) -> Unit,
) {
    val context = LocalContext.current
    val recording by PhoneRecordingSessionStore.state.collectAsState()
    val settings by PhoneRecordingSettingsState.state.collectAsState()
    val preferences = remember(context) { PhoneRecordingSettingsPreferences(context) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var activitySettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var gpsTrackSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var elevationSensorsSettingsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(context) {
        val loaded = preferences.load()
        PhoneRecordingSettingsState.publish(
            loaded.withGpsPreset(general.settings.activityProfile, loaded.gpsUsageProfile),
        )
        // Existing active service/draft is restored; opening this panel never starts a new recording.
        context.startService(
            Intent(context, PhoneRecordingService::class.java).setAction(PhoneRecordingService.ACTION_RESTORE),
        )
    }
    LaunchedEffect(recording.session) {
        while (recording.session.isActive) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val active = recording.session.isActive
    val paused = recording.session.phase == RecordingSessionPhase.PAUSED
    val profile = if (active) recording.activityProfile else general.settings.activityProfile
    val profileLabel = profileLabel(context, profile)
    val statusResource =
        if (paused && recording.session.autoPaused) {
            R.string.phone_rec_status_auto_paused
        } else {
            when (recording.message) {
                PhoneRecordingMessage.IDLE -> R.string.phone_rec_status_idle
                PhoneRecordingMessage.RECORDING -> R.string.phone_rec_status_recording
                PhoneRecordingMessage.PAUSED -> R.string.phone_rec_status_paused
                PhoneRecordingMessage.SAVING -> R.string.phone_rec_status_saving
                PhoneRecordingMessage.SAVE_FAILED -> R.string.phone_rec_status_save_failed
                PhoneRecordingMessage.SAVED -> R.string.phone_rec_status_saved
                PhoneRecordingMessage.PERMISSION_REQUIRED -> R.string.phone_rec_status_permission
                PhoneRecordingMessage.LOCATION_UNAVAILABLE -> R.string.phone_rec_status_location_unavailable
            }
        }
    val activeStatusColor =
        when {
            active && paused -> phoneRecordingPausedContentColor
            active -> phoneRecordingActiveColor
            else -> Color.Unspecified
        }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(statusResource), color = activeStatusColor)
        phoneRecordingLiveStatisticsGrid(recording, general.settings, nowMillis)
        Text(stringResource(R.string.phone_rec_settings_title))
        PhoneRecordingSettingsSection(
            title = stringResource(R.string.phone_profile_title),
            expanded = activitySettingsExpanded,
            onToggle = { activitySettingsExpanded = !activitySettingsExpanded },
        ) {
            OutlinedButton(
                onClick = { onGeneralSettingsChanged(general.settings.copy(isMetric = !general.settings.isMetric)) },
            ) {
                Text(
                    stringResource(
                        if (general.settings.isMetric) {
                            R.string.phone_rec_metric_units
                        } else {
                            R.string.phone_rec_imperial_units
                        },
                    ),
                )
            }
            Text(stringResource(R.string.phone_rec_weights_title))
            Text(stringResource(R.string.phone_rec_user_weight, general.settings.userWeightKg.roundToInt()))
            Slider(
                value = general.settings.userWeightKg,
                onValueChange = { value -> onGeneralSettingsChanged(general.settings.copy(userWeightKg = value)) },
                valueRange = MIN_PHONE_USER_WEIGHT_KG..MAX_PHONE_USER_WEIGHT_KG,
            )
            Text(stringResource(R.string.phone_rec_backpack_weight, general.settings.backpackWeightKg.roundToInt()))
            Slider(
                value = general.settings.backpackWeightKg,
                onValueChange = { value -> onGeneralSettingsChanged(general.settings.copy(backpackWeightKg = value)) },
                valueRange = MIN_PHONE_BACKPACK_WEIGHT_KG..MAX_PHONE_BACKPACK_WEIGHT_KG,
            )
            if (profile == PhoneActivityProfile.BIKE) {
                Text(stringResource(R.string.phone_rec_bike_weight, general.settings.bikeWeightKg.roundToInt()))
                Slider(
                    value = general.settings.bikeWeightKg,
                    onValueChange = { value -> onGeneralSettingsChanged(general.settings.copy(bikeWeightKg = value)) },
                    valueRange = MIN_PHONE_BIKE_WEIGHT_KG..MAX_PHONE_BIKE_WEIGHT_KG,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.phone_rec_show_saved_activities))
                Switch(
                    checked = settings.showSavedActivities,
                    onCheckedChange = { enabled ->
                        PhoneRecordingSettingsState.publish(
                            preferences.save(settings.copy(showSavedActivities = enabled)),
                        )
                    },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.phone_rec_auto_pause, profileLabel))
            Switch(
                enabled = !active,
                checked = settings.autoPauseMode(profile) != RecordingSettings.RECORDING_AUTO_PAUSE_OFF,
                onCheckedChange = { enabled ->
                    val value =
                        if (enabled) {
                            RecordingSettings.RECORDING_AUTO_PAUSE_ALWAYS
                        } else {
                            RecordingSettings.RECORDING_AUTO_PAUSE_OFF
                        }
                    PhoneRecordingSettingsState.publish(
                        preferences.save(
                            if (profile == PhoneActivityProfile.BIKE) {
                                settings.copy(bikeAutoPauseMode = value)
                            } else {
                                settings.copy(hikeAutoPauseMode = value)
                            },
                        ),
                    )
                },
            )
        }
        OutlinedButton(enabled = !active, onClick = {
            val modes =
                listOf(
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_OFF,
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS,
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS,
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS,
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES,
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES,
                    RecordingSettings.RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES,
                )
            val index = modes.indexOf(settings.progressVibrationMode).coerceAtLeast(0)
            PhoneRecordingSettingsState.publish(
                preferences.save(settings.withProgressVibrationMode(modes[(index + 1) % modes.size])),
            )
        }) {
            Text(
                stringResource(
                    R.string.phone_rec_progress_vibration,
                    vibrationLabel(context, settings, general.settings.isMetric),
                ),
            )
        }
        PhoneRecordingSettingsSection(
            title = stringResource(R.string.phone_rec_settings_gps_track),
            expanded = gpsTrackSettingsExpanded,
            onToggle = { gpsTrackSettingsExpanded = !gpsTrackSettingsExpanded },
        ) {
            OutlinedButton(enabled = !active, onClick = {
                val profiles =
                    listOf(
                        RecordingSettings.GPS_USAGE_PROFILE_BEST_TRACE,
                        RecordingSettings.GPS_USAGE_PROFILE_BALANCED,
                        RecordingSettings.GPS_USAGE_PROFILE_LONG_BATTERY,
                    )
                val next = profiles[(profiles.indexOf(settings.gpsUsageProfile).coerceAtLeast(0) + 1) % profiles.size]
                PhoneRecordingSettingsState.publish(preferences.applyGpsPreset(settings, profile, next))
            }) {
                Text(stringResource(R.string.phone_rec_gps_preset, gpsPresetLabel(context, settings.gpsUsageProfile)))
            }
            OutlinedButton(enabled = !active, onClick = {
                PhoneRecordingSettingsState.publish(
                    preferences.save(
                        settings.copy(
                            gpsUsageProfile = RecordingSettings.GPS_USAGE_PROFILE_CUSTOM,
                            screenOnIntervalSeconds = nextFixedInterval(settings.screenOnIntervalSeconds),
                        ),
                    ),
                )
            }) {
                Text(
                    stringResource(
                        R.string.phone_rec_screen_on_interval,
                        intervalLabel(context, settings.screenOnIntervalSeconds),
                    ),
                )
            }
            OutlinedButton(enabled = !active, onClick = {
                PhoneRecordingSettingsState.publish(
                    preferences.save(
                        settings.copy(
                            gpsUsageProfile = RecordingSettings.GPS_USAGE_PROFILE_CUSTOM,
                            screenOffIntervalSeconds = nextScreenOffInterval(settings.screenOffIntervalSeconds),
                        ),
                    ),
                )
            }) {
                Text(
                    stringResource(
                        R.string.phone_rec_screen_off_interval,
                        intervalLabel(context, settings.screenOffIntervalSeconds),
                    ),
                )
            }
            OutlinedButton(enabled = !active, onClick = {
                val choices =
                    listOf(
                        RecordingSettings.RECORDING_TRACK_SMOOTHING_OFF,
                        RecordingSettings.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                        RecordingSettings.RECORDING_TRACK_SMOOTHING_STRONG,
                    )
                val current = settings.smoothingMode(profile)
                val next = choices[(choices.indexOf(current).coerceAtLeast(0) + 1) % choices.size]
                PhoneRecordingSettingsState.publish(
                    preferences.save(
                        if (profile == PhoneActivityProfile.BIKE) {
                            settings.copy(bikeSmoothingMode = next)
                        } else {
                            settings.copy(hikeSmoothingMode = next)
                        },
                    ),
                )
            }) {
                Text(
                    stringResource(
                        R.string.phone_rec_smoothing,
                        profileLabel,
                        smoothingLabel(context, settings.smoothingMode(profile)),
                    ),
                )
            }
        }
        PhoneRecordingSettingsSection(
            title = stringResource(R.string.phone_rec_settings_elevation_sensors),
            expanded = elevationSensorsSettingsExpanded,
            onToggle = { elevationSensorsSettingsExpanded = !elevationSensorsSettingsExpanded },
        ) {
            OutlinedButton(enabled = !active, onClick = {
                val sources =
                    listOf(
                        RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO,
                        RecordingSettings.RECORDING_ELEVATION_SOURCE_DEM,
                        RecordingSettings.RECORDING_ELEVATION_SOURCE_GPS,
                        RecordingSettings.RECORDING_SOURCE_DISABLED,
                    )
                val index = sources.indexOf(settings.elevationSource).coerceAtLeast(0)
                PhoneRecordingSettingsState.publish(
                    preferences.save(settings.copy(elevationSource = sources[(index + 1) % sources.size])),
                )
            }) {
                Text(
                    stringResource(
                        R.string.phone_rec_elevation_source,
                        elevationLabel(context, settings.elevationSource),
                    ),
                )
            }
            val sensors =
                buildList {
                    if (general.sensorCapabilities.barometerAvailable) {
                        add(stringResource(R.string.phone_rec_sensor_barometer))
                    }
                    if (general.sensorCapabilities.stepCounterAvailable) {
                        add(stringResource(R.string.phone_rec_sensor_step_counter))
                    }
                    if (general.sensorCapabilities.gpsAvailable) add(stringResource(R.string.phone_rec_sensor_gps))
                }.ifEmpty { listOf(stringResource(R.string.phone_rec_sensor_gps_fallback)) }.joinToString()
            Text(stringResource(R.string.phone_rec_sensor_status, sensors))
            if (!general.sensorCapabilities.barometerAvailable) {
                Text(stringResource(R.string.phone_rec_no_elevation_sensor))
            }
        }
    }
}

@Composable
private fun PhoneRecordingSettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val expansionDescription =
        stringResource(
            if (expanded) R.string.phone_rec_settings_expanded else R.string.phone_rec_settings_collapsed,
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onToggle,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = expansionDescription },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title)
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

/** Compact control stays beside recenter while the recording panel is closed. */
@Composable
internal fun PhoneRecordingQuickControl(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recording by PhoneRecordingSessionStore.state.collectAsState()
    val stopRecording = phoneRecordingStopAction(recording)
    val active =
        recording.session.phase == RecordingSessionPhase.RECORDING ||
            recording.session.phase == RecordingSessionPhase.PAUSED
    val paused = recording.session.phase == RecordingSessionPhase.PAUSED
    val busy = recording.session.phase == RecordingSessionPhase.SAVING

    if (!active) return

    val statusResource =
        if (paused && recording.session.autoPaused) {
            R.string.phone_rec_status_auto_paused
        } else if (paused) {
            R.string.phone_rec_status_paused
        } else {
            R.string.phone_rec_status_recording
        }
    val containerColor = if (paused) phoneRecordingPausedColor else phoneRecordingActiveColor
    val contentColor = if (paused) phoneRecordingPausedContentColor else Color.White
    val status = stringResource(statusResource)

    Surface(
        modifier = modifier.testTag("phone-recording-quick-control"),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.FiberManualRecord,
                contentDescription = status,
                modifier = Modifier.size(10.dp),
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconButton(
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = !busy,
                    onClick = {
                        PhoneRecordingService.request(
                            context,
                            if (paused) PhoneRecordingService.ACTION_RESUME else PhoneRecordingService.ACTION_PAUSE,
                        )
                    },
                ) {
                    Icon(
                        imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription =
                            stringResource(if (paused) R.string.phone_rec_resume else R.string.phone_rec_pause),
                    )
                }
                IconButton(modifier = Modifier.weight(1f).height(48.dp), enabled = !busy, onClick = stopRecording) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.phone_rec_stop),
                    )
                }
                IconButton(modifier = Modifier.weight(1f).height(48.dp), onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.phone_rec_settings_title),
                    )
                }
            }
        }
    }
}

private fun recordingPermissions(): Array<String> =
    buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (android.os.Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }.toTypedArray()

private fun needsNotificationPermission(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun needsActivityPermission(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 29 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
