package com.glancemap.glancemapcompanionapp.map

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.recording.RecordingSessionPhase

@Composable
internal fun phoneRecordingHeaderActions(
    settings: PhoneGeneralSettings,
    onSettingsChanged: (PhoneGeneralSettings) -> Unit,
) {
    val context = LocalContext.current
    val recording by PhoneRecordingSessionStore.state.collectAsState()
    val recordingSettings by PhoneRecordingSettingsState.state.collectAsState()
    val preferences = remember(context) { PhoneRecordingSettingsPreferences(context) }
    val start =
        rememberPhoneRecordingStartAction { granted ->
            if (!granted) Toast.makeText(context, R.string.phone_rec_status_permission, Toast.LENGTH_LONG).show()
        }
    val stop = phoneRecordingStopAction(recording)
    val active = recording.session.isActive
    val paused = recording.session.phase == RecordingSessionPhase.PAUSED
    val busy = recording.session.phase == RecordingSessionPhase.SAVING
    val profile = if (active) recording.activityProfile else settings.activityProfile
    val hiking = profile == PhoneActivityProfile.HIKE
    val profileIcon = if (hiking) Icons.Filled.Hiking else Icons.AutoMirrored.Filled.DirectionsBike
    val profileLabel =
        if (hiking) R.string.map_tools_settings_activity_hike else R.string.map_tools_settings_activity_bike
    IconButton(enabled = !busy, onClick = {
        if (!active) {
            start()
        } else {
            PhoneRecordingService.request(
                context,
                if (paused) PhoneRecordingService.ACTION_RESUME else PhoneRecordingService.ACTION_PAUSE,
            )
        }
    }) {
        Icon(
            imageVector = if (active && !paused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription =
                stringResource(
                    when {
                        !active -> R.string.phone_rec_start
                        paused -> R.string.phone_rec_resume
                        else -> R.string.phone_rec_pause
                    },
                ),
        )
    }
    IconButton(enabled = active && !busy, onClick = stop) {
        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.phone_rec_stop))
    }
    IconButton(enabled = !active, onClick = {
        val next = if (hiking) PhoneActivityProfile.BIKE else PhoneActivityProfile.HIKE
        onSettingsChanged(settings.copy(activityProfile = next))
        PhoneRecordingSettingsState.publish(
            preferences.applyGpsPreset(recordingSettings, next, recordingSettings.gpsUsageProfile),
        )
    }) {
        Icon(
            imageVector = profileIcon,
            contentDescription = stringResource(profileLabel),
        )
    }
}
