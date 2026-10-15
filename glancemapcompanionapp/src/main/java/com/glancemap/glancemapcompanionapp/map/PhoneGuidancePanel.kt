package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
            if (guidance != null) {
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
private fun GuidanceSummary(
    context: Context,
    guidance: TrailGuidanceState,
    isMetric: Boolean,
) {
    val instruction = guidance.nextInstruction
    val command = instruction?.command
    val arrow =
        when (guidance.mode) {
            TrailGuidanceMode.TO_START,
            TrailGuidanceMode.WAITING_FOR_LOCATION,
            -> "↑"
            TrailGuidanceMode.FINISHED -> "⚑"
            else ->
                when (command) {
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
