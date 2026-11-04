package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import com.glancemap.trailcore.recording.RecordingSessionPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// These thresholds only choose the recap title; recording and GPX validation remain unchanged.
private const val SHORT_RECORDING_DURATION_MILLIS = 60_000L
private const val SHORT_RECORDING_DISTANCE_METERS = 10.0
private const val MIN_SAVE_POINTS = 2

internal fun phoneRecordingIsShort(
    pointCount: Int,
    elapsedMillis: Long,
    distanceMeters: Double,
): Boolean =
    pointCount < MIN_SAVE_POINTS ||
        elapsedMillis < SHORT_RECORDING_DURATION_MILLIS ||
        distanceMeters < SHORT_RECORDING_DISTANCE_METERS

/**
 * Mirrors the watch stop flow: stopping pauses an actively recording session and shows the recap.
 * Save stores it, Discard asks for confirmation before deleting it, and Cancel resumes only a
 * session that the prompt itself paused.
 */
@Composable
internal fun phoneRecordingStopAction(recording: PhoneRecordingUiState): () -> Unit {
    val context = LocalContext.current
    val generation = recording.session.generation
    var showRecap by rememberSaveable(generation) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(generation) { mutableStateOf(false) }
    var promptPausedRecording by rememberSaveable(generation) { mutableStateOf(false) }
    val canStop =
        recording.session.phase == RecordingSessionPhase.RECORDING ||
            recording.session.phase == RecordingSessionPhase.PAUSED
    val close = {
        showRecap = false
        confirmDiscard = false
        promptPausedRecording = false
    }
    if (showRecap && canStop) {
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(R.string.phone_rec_discard_confirm_title)) },
                text = { Text(stringResource(R.string.phone_rec_discard_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        PhoneRecordingService.request(context, PhoneRecordingService.ACTION_DISCARD)
                    }) { Text(stringResource(R.string.phone_rec_discard)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                },
            )
        } else {
            phoneRecordingStopRecapDialog(
                recording = recording,
                onSave = { title ->
                    close()
                    PhoneRecordingService.requestSave(context, title)
                },
                onDiscard = { confirmDiscard = true },
                onCancel = {
                    val resume = promptPausedRecording
                    close()
                    if (resume) PhoneRecordingService.request(context, PhoneRecordingService.ACTION_RESUME)
                },
            )
        }
    }
    return {
        if (canStop && !showRecap) {
            promptPausedRecording = recording.session.phase == RecordingSessionPhase.RECORDING
            if (promptPausedRecording) PhoneRecordingService.request(context, PhoneRecordingService.ACTION_PAUSE)
            showRecap = true
        }
    }
}

@Composable
private fun phoneRecordingStopRecapDialog(
    recording: PhoneRecordingUiState,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { PhoneGeneralSettingsPreferences(context).load() }
    val stoppedAtMillis = remember(recording.session.generation) { System.currentTimeMillis() }
    val steps by PhoneRecordingSessionStore.steps.collectAsState()
    val stepCount = steps.count.takeIf { steps.generation == recording.session.generation }
    // The prompt pauses recording, so the recap reflects the track at the moment Stop was pressed.
    val statistics by produceState<RecordedActivityStatistics?>(null, recording.pointsRevision, settings) {
        value =
            withContext(Dispatchers.Default) {
                val track = phoneRecordingTrackStatistics(recording, settings)
                phoneRecordingLiveStatistics(recording, track, stoppedAtMillis, stepCount)
            }
    }
    // Same default title the saved GPX and Library use, so the recap matches what gets saved.
    val title =
        remember(recording.session.generation, recording.activityProfile) {
            phoneRecordingDefaultTitle(
                profileLabel = profileLabel(context, recording.activityProfile),
                startedAtMillis = recording.session.startedAtMillis ?: stoppedAtMillis,
                endedAtMillis = stoppedAtMillis,
            )
        }
    val canSave = recording.points.size >= MIN_SAVE_POINTS
    val shortRecording =
        phoneRecordingIsShort(
            recording.points.size,
            recording.session.elapsedMillis(stoppedAtMillis),
            recording.distanceMeters,
        )
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("recording-stop-recap"),
        title = {
            Text(
                stringResource(
                    if (shortRecording) R.string.activity_short_recording else R.string.phone_rec_stop_title,
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("recording-stop-title"),
                )
                if (!canSave) Text(stringResource(R.string.phone_rec_no_track_stop_message))
                statistics?.let { phoneActivityStatisticsGrid(it, settings.isMetric) }
            }
        },
        // Both slots are emitted into the dialog's wrapping button row, so narrow screens stay readable.
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_action_cancel)) }
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.phone_rec_discard)) }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(title) }) { Text(stringResource(R.string.phone_rec_save)) }
        },
    )
}
