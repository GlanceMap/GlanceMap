package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale

@Composable
internal fun CompactRecordingControls(
    state: TraceRecordingUiState,
    modifier: Modifier,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .width(148.dp)
                .height(48.dp)
                .background(Color.Black.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        cappedFontScale(maxFontScale = 1f) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                CompactControlButton(
                    selected = false,
                    onClick = onDismiss,
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.recording_close_recording_controls),
                    tint = Color.White.copy(alpha = 0.82f),
                )
                CompactControlButton(
                    selected = true,
                    onClick = onPauseResume,
                    icon = if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription =
                        if (state.paused) {
                            stringResource(
                                R.string.navigate_resume_recording,
                            )
                        } else {
                            stringResource(R.string.navigate_pause_recording)
                        },
                )
                CompactControlButton(
                    selected = false,
                    onClick = onStop,
                    icon = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.navigate_stop_recording),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CompactControlButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else tint,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
