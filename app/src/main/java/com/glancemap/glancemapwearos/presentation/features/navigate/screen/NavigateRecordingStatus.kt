package com.glancemap.glancemapwearos.presentation.features.navigate

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.presentation.features.recording.RECORDING_START_PENDING_MESSAGE
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingViewModel
import kotlinx.coroutines.delay

@Composable
internal fun rememberRecordingStatusMessage(
    state: TraceRecordingUiState,
    traceRecordingViewModel: TraceRecordingViewModel,
): String? {
    var recordingStatusMessage by remember { mutableStateOf<String?>(null) }
    var recordingStatusMessageToken by remember { mutableLongStateOf(0L) }
    var recordingStatusMessagePersistent by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                recordingStatusMessage = message
                recordingStatusMessagePersistent = isPersistentRecordingStatusMessage(message)
                recordingStatusMessageToken = SystemClock.elapsedRealtime()
                if (!recordingStatusMessagePersistent) {
                    traceRecordingViewModel.consumeMessage(message)
                }
            }
            ?: run {
                if (recordingStatusMessagePersistent) {
                    recordingStatusMessage = null
                    recordingStatusMessagePersistent = false
                }
            }
    }
    LaunchedEffect(recordingStatusMessageToken, recordingStatusMessagePersistent) {
        if (
            recordingStatusMessageToken != 0L &&
            recordingStatusMessage != null &&
            !recordingStatusMessagePersistent
        ) {
            val token = recordingStatusMessageToken
            delay(RECORDING_STATUS_MESSAGE_DURATION_MS)
            if (recordingStatusMessageToken == token) {
                recordingStatusMessage = null
            }
        }
    }
    return recordingStatusMessage
}

internal fun isPersistentRecordingStatusMessage(
    message: String,
): Boolean = message == RECORDING_START_PENDING_MESSAGE

private const val RECORDING_STATUS_MESSAGE_DURATION_MS = 1_200L
