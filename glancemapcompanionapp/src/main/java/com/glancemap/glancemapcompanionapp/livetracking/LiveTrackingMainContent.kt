@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
)

package com.glancemap.glancemapcompanionapp.livetracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.CompanionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.delay

@Composable
internal fun ColumnScope.MainTrackingContent(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenGuide: () -> Unit,
    isConnected: Boolean,
    group: String,
    hasSelectedGpx: Boolean,
    selectedGpxName: String,
    comments: String,
    onCommentsChange: (String) -> Unit,
    onPickGpx: () -> Unit,
    onClearGpx: () -> Unit,
    showSendPlan: Boolean,
    canSendPlan: Boolean,
    isSendingPlan: Boolean,
    planSent: Boolean,
    onSendPlan: () -> Unit,
    sessionState: LiveTrackingUiState,
    updateIntervalSeconds: Int,
    isStartingSession: Boolean,
    validationMessage: String?,
    sendStatusMessage: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    userName: String,
    groupTrackUrl: String,
    userTrackUrl: String,
    recordedTrackDownloadStatusMessage: String?,
    isDownloadingRecordedTrack: Boolean,
    isDeletingTracks: Boolean,
    deleteTracksStatusMessage: String?,
    onDeleteRecordedTracks: () -> Unit,
    onDownloadUserTrack: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    contentSpacing: androidx.compose.ui.unit.Dp,
    isCompactLayout: Boolean,
    isCompactScreen: Boolean,
    adaptive: CompanionAdaptiveSpec,
) {
    val context = LocalContext.current
    val isArkluzNotificationPending = sessionState.status.contains("Arkluz notification pending")
    var showArkluzPendingWarning by remember { mutableStateOf(false) }

    LaunchedEffect(isArkluzNotificationPending) {
        if (isArkluzNotificationPending) {
            delay(ARKLUZ_PENDING_WARNING_DELAY_MS)
            showArkluzPendingWarning = true
        } else {
            showArkluzPendingWarning = false
        }
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = adaptive.helpIconButtonSize),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.size(adaptive.helpIconButtonSize),
            colors = companionTonalIconButtonColors(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back_to_home_content_description),
                modifier = Modifier.size(adaptive.helpIconSize),
            )
        }
        Spacer(modifier = Modifier.size(adaptive.helpIconButtonSize))
        Text(
            text = stringResource(R.string.live_tracking_title),
            style =
                if (isCompactScreen) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.headlineSmall
                },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(
            onClick = onOpenGuide,
            modifier = Modifier.size(adaptive.helpIconButtonSize),
            colors = companionTonalIconButtonColors(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = stringResource(R.string.live_tracking_guide_content_description),
                modifier = Modifier.size(adaptive.helpIconSize),
            )
        }
    }

    ScrollableScreenContent(
        scrollState = scrollState,
        contentSpacing = contentSpacing,
    ) {
        TrackingPanel(title = stringResource(R.string.live_tracking_session_title)) {
            Text(
                text =
                    stringResource(
                        R.string.live_tracking_gps_update_frequency,
                        formatUpdateInterval(updateIntervalSeconds),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick =
                        when {
                            sessionState.isPaused -> onResume
                            sessionState.isTracking -> onPause
                            else -> onStart
                        },
                    enabled = !isStartingSession,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector =
                            if (sessionState.isTracking && !sessionState.isPaused) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        when {
                            isStartingSession -> stringResource(R.string.live_tracking_action_starting)
                            sessionState.isPaused -> stringResource(R.string.common_action_resume)
                            sessionState.isTracking -> stringResource(R.string.common_action_pause)
                            else -> stringResource(R.string.live_tracking_action_start)
                        },
                    )
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = sessionState.isTracking,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.common_action_stop))
                }
            }
            Text(
                text = sessionStatusText(sessionState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showArkluzPendingWarning) {
                Text(
                    text = stringResource(R.string.live_tracking_arkluz_pending_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            validationMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            sessionState.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { emailArkluzSupport(context, error) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.live_tracking_action_email_arkluz))
                }
            }
        }

        TrackingPanel(title = stringResource(R.string.live_tracking_planned_route_title)) {
            Text(
                text = stringResource(R.string.live_tracking_plan_optional_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onPickGpx,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Route,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.live_tracking_action_select_gpx))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (hasSelectedGpx) {
                            stringResource(
                                R.string.live_tracking_selected_gpx_name,
                                selectedGpxName.ifBlank {
                                    stringResource(R.string.live_tracking_selected_gpx_fallback)
                                },
                            )
                        } else {
                            stringResource(R.string.live_tracking_no_gpx_selected)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasSelectedGpx) {
                    IconButton(
                        onClick = onClearGpx,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription =
                                stringResource(
                                    R.string.live_tracking_clear_gpx_content_description,
                                ),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (sessionState.isTracking) {
                Text(
                    text = stringResource(R.string.live_tracking_update_planned_route_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = comments,
                onValueChange = onCommentsChange,
                label = { Text(stringResource(R.string.live_tracking_comments_label)) },
                placeholder = { Text(stringResource(R.string.live_tracking_comments_placeholder)) },
                minLines = if (isCompactLayout) 2 else 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (sessionState.isTracking) {
                Text(
                    text = stringResource(R.string.live_tracking_active_update_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showSendPlan) {
                Button(
                    onClick = onSendPlan,
                    enabled = canSendPlan && !isSendingPlan && !planSent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            isSendingPlan -> stringResource(R.string.live_tracking_action_sending)
                            planSent -> stringResource(R.string.live_tracking_action_comment_sent)
                            else -> stringResource(R.string.live_tracking_action_send_update)
                        },
                    )
                }
            }
            sendStatusMessage?.let { message ->
                val isSendError = message.startsWith("Send failed", ignoreCase = true)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isSendError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (isSendError) {
                    OutlinedButton(
                        onClick = { emailArkluzSupport(context, message) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.live_tracking_action_email_arkluz))
                    }
                }
            }
        }

        TrackingPanel(title = stringResource(R.string.live_tracking_tracks_title)) {
            TrackLinkRow(
                label = userName.trim().ifBlank { stringResource(R.string.live_tracking_participant_fallback) },
                url = userTrackUrl,
                onView = { openUrl(context, userTrackUrl) },
                onShare = { shareUrl(context, userTrackUrl) },
            )
            TrackLinkRow(
                label = stringResource(R.string.live_tracking_group),
                url = groupTrackUrl,
                onView = { openUrl(context, groupTrackUrl) },
                onShare = { shareUrl(context, groupTrackUrl) },
            )
            OutlinedButton(
                onClick = onDownloadUserTrack,
                enabled = !isDownloadingRecordedTrack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        stringResource(
                            if (isDownloadingRecordedTrack) {
                                R.string.live_tracking_action_downloading
                            } else {
                                R.string.live_tracking_action_download_my_gpx
                            },
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            recordedTrackDownloadStatusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (message.startsWith("Download failed", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            OutlinedButton(
                onClick = onDeleteRecordedTracks,
                enabled = !isDeletingTracks,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    stringResource(
                        if (isDeletingTracks) {
                            R.string.live_tracking_action_deleting
                        } else {
                            R.string.live_tracking_action_delete_recorded_tracks
                        },
                    ),
                )
            }
            Text(
                text = stringResource(R.string.live_tracking_tracks_retention_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            deleteTracksStatusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (message.startsWith("Delete failed", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }

        LiveTrackingDiagnosticsPanel()
    }

    Button(
        onClick = onOpenSetup,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text =
                stringResource(
                    if (isConnected) {
                        R.string.live_tracking_action_edit_setup
                    } else {
                        R.string.live_tracking_action_set_up
                    },
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (isConnected) {
        Text(
            text =
                stringResource(
                    R.string.live_tracking_connected_to_group,
                    group.trim().ifBlank { stringResource(R.string.live_tracking_private_group) },
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val ARKLUZ_PENDING_WARNING_DELAY_MS = 1_000L

@Composable
private fun TrackLinkRow(
    label: String,
    url: String,
    onView: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalIconButton(
            onClick = onView,
            enabled = url.isNotBlank(),
            modifier = Modifier.size(48.dp),
            colors = companionTonalIconButtonColors(),
        ) {
            Icon(
                imageVector = Icons.Filled.TravelExplore,
                contentDescription = stringResource(R.string.live_tracking_view_track_content_description, label),
                modifier = Modifier.size(18.dp),
            )
        }
        FilledTonalIconButton(
            onClick = onShare,
            enabled = url.isNotBlank(),
            modifier = Modifier.size(48.dp),
            colors = companionTonalIconButtonColors(),
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.live_tracking_share_track_content_description, label),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun companionTonalIconButtonColors() =
    IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
