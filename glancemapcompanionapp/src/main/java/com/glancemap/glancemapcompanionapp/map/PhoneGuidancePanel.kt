package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.guidance.TrailGuidanceCommand
import com.glancemap.trailcore.guidance.TrailGuidanceMode
import com.glancemap.trailcore.guidance.TrailGuidanceState
import com.glancemap.trailcore.guidance.message

@Composable
internal fun PhoneGuidancePanel() {
    val context = LocalContext.current
    val ui by PhoneGuidanceSessionStore.state.collectAsState()
    val metric = remember(context) { PhoneGeneralSettingsPreferences(context).load().isMetric }
    val feedbackPreferences = remember(context) { PhoneGuidanceFeedbackPreferences(context) }
    var feedbackSettings by remember(context) { mutableStateOf(feedbackPreferences.load()) }
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (PhoneMapLocationContext.hasPermission(context)) {
                PhoneRecordingService.request(context, PhoneRecordingService.ACTION_GUIDANCE_RETRY)
            }
        }
    val retryLocation = {
        if (PhoneMapLocationContext.hasPermission(context)) {
            PhoneRecordingService.request(context, PhoneRecordingService.ACTION_GUIDANCE_RETRY)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }
    val guidance = ui.guidance
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.phone_guidance_title), style = MaterialTheme.typography.titleMedium)
            when {
                ui.message == PhoneGuidanceMessage.LOADING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.phone_guidance_loading))
                    }
                }
                ui.message == PhoneGuidanceMessage.FAILED ->
                    Text(
                        stringResource(R.string.phone_guidance_start_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                ui.message == PhoneGuidanceMessage.GPS_UNAVAILABLE ||
                    ui.message == PhoneGuidanceMessage.PERMISSION_REQUIRED ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(
                                if (ui.message == PhoneGuidanceMessage.PERMISSION_REQUIRED) {
                                    R.string.phone_guidance_permission_required
                                } else {
                                    R.string.phone_guidance_gps_unavailable
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = retryLocation) {
                            Text(stringResource(R.string.phone_guidance_retry_location))
                        }
                    }
                guidance == null ->
                    Text(
                        stringResource(R.string.phone_guidance_idle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                else -> GuidanceSummary(context, guidance, metric)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phone_guidance_voice), style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = feedbackSettings.voiceEnabled,
                    onCheckedChange = { enabled ->
                        feedbackSettings = feedbackSettings.copy(voiceEnabled = enabled)
                        feedbackPreferences.save(feedbackSettings)
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phone_guidance_haptics), style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = feedbackSettings.hapticsEnabled,
                    onCheckedChange = { enabled ->
                        feedbackSettings = feedbackSettings.copy(hapticsEnabled = enabled)
                        feedbackPreferences.save(feedbackSettings)
                    },
                )
            }
            if (ui.session != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            PhoneRecordingService.request(
                                context,
                                if (ui.paused) PhoneRecordingService.ACTION_GUIDANCE_RESUME else PhoneRecordingService.ACTION_GUIDANCE_PAUSE,
                            )
                        },
                    ) {
                        Text(stringResource(if (ui.paused) R.string.common_action_resume else R.string.common_action_pause))
                    }
                    OutlinedButton(onClick = {
                        PhoneRecordingService.request(context, PhoneRecordingService.ACTION_GUIDANCE_STOP)
                    }) {
                        Text(stringResource(R.string.phone_guidance_stop))
                    }
                }
            }
        }
    }
    ui.pendingDecision?.let { decision ->
        val reverse = decision == PhoneGuidanceDecision.REVERSE_ROUTE
        AlertDialog(
            onDismissRequest = {
                PhoneRecordingService.request(context, PhoneRecordingService.ACTION_GUIDANCE_DISMISS_DECISION)
            },
            title = {
                Text(stringResource(if (reverse) R.string.phone_guidance_reverse_title else R.string.phone_guidance_start_here_title))
            },
            text = {
                Text(stringResource(if (reverse) R.string.phone_guidance_reverse_message else R.string.phone_guidance_start_here_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    PhoneRecordingService.request(
                        context,
                        if (reverse) PhoneRecordingService.ACTION_GUIDANCE_ACCEPT_REVERSE else PhoneRecordingService.ACTION_GUIDANCE_ACCEPT_START_HERE,
                    )
                }) {
                    Text(stringResource(if (reverse) R.string.phone_guidance_reverse_action else R.string.phone_guidance_start_here_action))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    PhoneRecordingService.request(context, PhoneRecordingService.ACTION_GUIDANCE_DISMISS_DECISION)
                }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
internal fun PhoneGuidanceQuickControl(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val ui by PhoneGuidanceSessionStore.state.collectAsState()
    val metric = remember(context) { PhoneGeneralSettingsPreferences(context).load().isMetric }
    val visible = ui.session != null
    val guidance = ui.guidance

    LaunchedEffect(visible) {
        if (!visible) onExpandedChange(false)
    }
    if (!visible) return

    val paused = ui.paused
    val background = if (paused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
    val foreground = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
    val instruction = guidance?.nextInstruction
    val command = instruction?.command
    val instructionLabel =
        when {
            ui.message == PhoneGuidanceMessage.GPS_UNAVAILABLE -> stringResource(R.string.phone_guidance_waiting_location)
            ui.message == PhoneGuidanceMessage.PERMISSION_REQUIRED -> stringResource(R.string.phone_guidance_permission_required)
            guidance == null -> stringResource(R.string.phone_guidance_loading)
            guidance.mode == TrailGuidanceMode.TO_START -> stringResource(R.string.phone_guidance_to_start)
            guidance.mode == TrailGuidanceMode.WAITING_FOR_LOCATION -> stringResource(R.string.phone_guidance_waiting_location)
            guidance.mode == TrailGuidanceMode.FINISHED -> stringResource(R.string.phone_guidance_arrived)
            else -> {
                val hintedMessage = instruction?.message?.takeIf { it != command?.message }
                hintedMessage ?: stringResource(command.resource())
            }
        }
    val instructionDistance =
        guidance?.let {
            if (it.mode == TrailGuidanceMode.TO_START) it.distanceToStartMeters else it.distanceToInstructionMeters
        }
    val arrow = guidance?.let(::guidanceArrow) ?: "↑"

    Surface(
        modifier = Modifier.testTag("phone-guidance-quick-control"),
        shape = RoundedCornerShape(20.dp),
        color = background,
        contentColor = foreground,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.clickable(role = Role.Button) { onExpandedChange(!expanded) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(arrow, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(instructionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (paused && instructionDistance != null) {
                        Text(
                            stringResource(
                                R.string.phone_guidance_paused_in_distance,
                                formatGuidanceDistance(context, instructionDistance, metric),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (instructionDistance != null && guidance?.mode != TrailGuidanceMode.FINISHED) {
                        Text(
                            stringResource(
                                R.string.phone_guidance_in_distance,
                                formatGuidanceDistance(context, instructionDistance, metric),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (paused) {
                        Text(stringResource(R.string.phone_guidance_notification_paused), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.phone_guidance_collapse_controls else R.string.phone_guidance_expand_controls,
                        ),
                )
            }
            if (expanded) {
                Text(instructionLabel, style = MaterialTheme.typography.bodyMedium)
                guidance?.let { state ->
                    Text(
                        state.trackTitle ?: ui.session?.trackTitle.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.offRoute) {
                        Text(
                            stringResource(R.string.phone_guidance_off_route),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.phone_guidance_remaining,
                                formatGuidanceDistance(context, state.distanceRemainingMeters, metric),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.estimatedRemainingSeconds?.let { seconds ->
                            Text(
                                stringResource(R.string.phone_guidance_time, formatGuidanceDuration(context, seconds)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            PhoneRecordingService.request(
                                context,
                                if (paused) PhoneRecordingService.ACTION_GUIDANCE_RESUME else PhoneRecordingService.ACTION_GUIDANCE_PAUSE,
                            )
                            onExpandedChange(false)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = foreground),
                    ) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(if (paused) R.string.common_action_resume else R.string.common_action_pause))
                    }
                    TextButton(
                        onClick = {
                            PhoneRecordingService.request(context, PhoneRecordingService.ACTION_GUIDANCE_STOP)
                            onExpandedChange(false)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = foreground),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.phone_guidance_end))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceSummary(
    context: Context,
    guidance: TrailGuidanceState,
    isMetric: Boolean,
) {
    val instruction = guidance.nextInstruction
    val command = instruction?.command
    val arrow = guidanceArrow(guidance)
    val instructionText =
        when (guidance.mode) {
            TrailGuidanceMode.TO_START -> stringResource(R.string.phone_guidance_to_start)
            TrailGuidanceMode.WAITING_FOR_LOCATION -> stringResource(R.string.phone_guidance_waiting_location)
            TrailGuidanceMode.FINISHED -> stringResource(R.string.phone_guidance_arrived)
            else -> {
                val hintedMessage = instruction?.message?.takeIf { message -> message != command?.message }
                if (hintedMessage != null) hintedMessage else stringResource(command.resource())
            }
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(54.dp).height(48.dp), contentAlignment = Alignment.Center) {
            Text(arrow, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                instructionText,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val turnDistance = if (guidance.mode == TrailGuidanceMode.TO_START) guidance.distanceToStartMeters else guidance.distanceToInstructionMeters
            turnDistance?.let { Text(stringResource(R.string.phone_guidance_in_distance, formatGuidanceDistance(context, it, isMetric)), style = MaterialTheme.typography.bodySmall) }
            if (guidance.offRoute) Text(stringResource(R.string.phone_guidance_off_route), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.phone_guidance_remaining, formatGuidanceDistance(context, guidance.distanceRemainingMeters, isMetric)), style = MaterialTheme.typography.bodySmall)
        guidance.estimatedRemainingSeconds?.let { seconds ->
            Text(stringResource(R.string.phone_guidance_time, formatGuidanceDuration(context, seconds)), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.phone_guidance_ascent, formatGuidanceDistance(context, guidance.remainingAscentMeters, isMetric)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.phone_guidance_descent, formatGuidanceDistance(context, guidance.remainingDescentMeters, isMetric)), style = MaterialTheme.typography.bodySmall)
    }
}

private fun guidanceArrow(guidance: TrailGuidanceState): String =
    when (guidance.mode) {
        TrailGuidanceMode.TO_START,
        TrailGuidanceMode.WAITING_FOR_LOCATION,
        -> "↑"
        TrailGuidanceMode.FINISHED -> "⚑"
        else ->
            when (guidance.nextInstruction?.command) {
                TrailGuidanceCommand.SLIGHT_LEFT -> "↖"
                TrailGuidanceCommand.LEFT -> "←"
                TrailGuidanceCommand.SHARP_LEFT -> "↰"
                TrailGuidanceCommand.SLIGHT_RIGHT -> "↗"
                TrailGuidanceCommand.RIGHT -> "→"
                TrailGuidanceCommand.SHARP_RIGHT -> "↱"
                TrailGuidanceCommand.FINISH -> "⚑"
                else -> "↑"
            }
    }

private fun TrailGuidanceCommand?.resource(): Int =
    when (this) {
        TrailGuidanceCommand.SLIGHT_LEFT -> R.string.phone_guidance_slight_left
        TrailGuidanceCommand.LEFT -> R.string.phone_guidance_left
        TrailGuidanceCommand.SHARP_LEFT -> R.string.phone_guidance_sharp_left
        TrailGuidanceCommand.SLIGHT_RIGHT -> R.string.phone_guidance_slight_right
        TrailGuidanceCommand.RIGHT -> R.string.phone_guidance_right
        TrailGuidanceCommand.SHARP_RIGHT -> R.string.phone_guidance_sharp_right
        TrailGuidanceCommand.FINISH -> R.string.phone_guidance_arrived
        else -> R.string.phone_guidance_continue
    }

private fun formatGuidanceDistance(
    context: Context,
    meters: Double?,
    isMetric: Boolean,
): String {
    if (meters == null || !meters.isFinite()) return context.getString(R.string.phone_guidance_unavailable)
    return if (isMetric) {
        if (meters < 1_000.0) {
            context.getString(R.string.phone_guidance_meters, meters.toInt())
        } else {
            context.getString(R.string.phone_guidance_kilometers, meters / 1_000.0)
        }
    } else {
        val miles = meters / 1_609.344
        if (miles < 0.1) {
            context.getString(R.string.phone_guidance_feet, (meters * 3.28084).toInt())
        } else {
            context.getString(R.string.phone_guidance_miles, miles)
        }
    }
}

private fun formatGuidanceDuration(
    context: Context,
    seconds: Long,
): String {
    val minutes = seconds.coerceAtLeast(0L) / 60L
    val hours = minutes / 60L
    return if (hours > 0L) {
        context.getString(R.string.phone_guidance_duration_hours, hours, minutes % 60L)
    } else {
        context.getString(R.string.phone_guidance_duration_minutes, minutes)
    }
}
