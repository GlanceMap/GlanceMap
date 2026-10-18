package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.recording.RecordingSessionPhase

// These thresholds only decide whether to ask; recording and GPX validation remain unchanged.
private const val SHORT_RECORDING_DURATION_MILLIS = 60_000L
private const val SHORT_RECORDING_DISTANCE_METERS = 10.0

internal fun phoneRecordingNeedsStopConfirmation(
    pointCount: Int,
    elapsedMillis: Long,
    distanceMeters: Double,
): Boolean =
    pointCount < 2 ||
        elapsedMillis < SHORT_RECORDING_DURATION_MILLIS ||
        distanceMeters < SHORT_RECORDING_DISTANCE_METERS

@Composable
internal fun phoneRecordingStopAction(recording: PhoneRecordingUiState): () -> Unit {
    val context = LocalContext.current
    var confirmStop by rememberSaveable(recording.session.generation) { mutableStateOf(false) }
    val canStop =
        recording.session.phase == RecordingSessionPhase.RECORDING ||
            recording.session.phase == RecordingSessionPhase.PAUSED
    if (confirmStop && canStop) {
        val canSave = recording.points.size >= 2
        val messageResource =
            if (canSave) R.string.phone_rec_short_stop_message else R.string.phone_rec_no_track_stop_message
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.phone_rec_short_stop_title)) },
            text = { Text(stringResource(messageResource)) },
            confirmButton = {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        confirmStop = false
                        PhoneRecordingService.request(context, PhoneRecordingService.ACTION_SAVE)
                    },
                ) { Text(stringResource(R.string.phone_rec_save)) }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        confirmStop = false
                        PhoneRecordingService.request(context, PhoneRecordingService.ACTION_DISCARD)
                    }) { Text(stringResource(R.string.phone_rec_discard)) }
                    TextButton(onClick = { confirmStop = false }) {
                        Text(stringResource(R.string.phone_rec_keep_recording))
                    }
                }
            },
        )
    }
    return {
        if (canStop) {
            if (phoneRecordingNeedsStopConfirmation(
                    recording.points.size,
                    recording.session.elapsedMillis(System.currentTimeMillis()),
                    recording.distanceMeters,
                )
            ) {
                confirmStop = true
            } else {
                PhoneRecordingService.request(context, PhoneRecordingService.ACTION_SAVE)
            }
        }
    }
}
