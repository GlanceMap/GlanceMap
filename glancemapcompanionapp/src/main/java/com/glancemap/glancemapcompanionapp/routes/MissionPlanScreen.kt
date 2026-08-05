@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MaxLineLength")

package com.glancemap.glancemapcompanionapp.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MissionPlanScreen(
    uiState: MissionPlanUiState,
    routes: List<RouteLibraryRoute>,
    onBack: () -> Unit,
    onAddDay: (String) -> Unit,
    onSetToday: (MissionPlanDayUi) -> Unit,
    onUpdateSegment: (dayId: String, startDistanceMeters: Double, endDistanceMeters: Double?) -> Unit,
    onRemoveDay: (String) -> Unit,
    onOpenRoutes: () -> Unit,
) {
    var editedDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to home",
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Mission Plan", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Plan each hiking day without copying GPX files",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        uiState.message?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Text(text = message, modifier = Modifier.padding(16.dp))
            }
        }

        if (uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "DAYS",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (uiState.days.isEmpty()) {
                    item {
                        Text(
                            text =
                                "Add a route below to start a multi-day plan. A day can also be " +
                                    "a section of one long GPX.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(uiState.days, key = { dayUi -> dayUi.day.id }) { dayUi ->
                        MissionPlanDayCard(
                            dayUi = dayUi,
                            selected = dayUi.day.id == uiState.selectedDayId,
                            onSetToday = { onSetToday(dayUi) },
                            onEditRange = { editedDay = dayUi },
                            onRemove = { onRemoveDay(dayUi.day.id) },
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ADD A DAY",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (routes.isEmpty()) {
                    item {
                        Text(
                            text = "Import GPX routes first, then add each one to your plan.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenRoutes) {
                            Text("Open route library")
                        }
                    }
                } else {
                    items(routes, key = RouteLibraryRoute::id) { route ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = route.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = route.summary.missionPlanRouteSummary(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                OutlinedButton(onClick = { onAddDay(route.id) }) {
                                    Text("Add")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editedDay?.let { dayUi ->
        MissionPlanSegmentDialog(
            dayUi = dayUi,
            onDismiss = { editedDay = null },
            onConfirm = { startDistanceMeters, endDistanceMeters ->
                onUpdateSegment(dayUi.day.id, startDistanceMeters, endDistanceMeters)
                editedDay = null
            },
        )
    }
}

@Composable
private fun MissionPlanDayCard(
    dayUi: MissionPlanDayUi,
    selected: Boolean,
    onSetToday: () -> Unit,
    onEditRange: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "DAY ${dayUi.day.dayNumber}${if (selected) " • TODAY" else ""}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = dayUi.route.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = dayUi.briefing.missionPlanBriefingSummary(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = dayUi.day.missionPlanRangeSummary(dayUi.route.summary.distanceMeters),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditRange, modifier = Modifier.weight(1f)) {
                    Text("Edit range")
                }
                Button(onClick = onSetToday, enabled = !selected, modifier = Modifier.weight(1f)) {
                    Text(if (selected) "Today" else "Set today")
                }
            }
            TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.End)) {
                Text("Remove day")
            }
        }
    }
}

@Composable
private fun MissionPlanSegmentDialog(
    dayUi: MissionPlanDayUi,
    onDismiss: () -> Unit,
    onConfirm: (startDistanceMeters: Double, endDistanceMeters: Double?) -> Unit,
) {
    var startKilometers by remember(dayUi.day.id) {
        mutableStateOf(dayUi.day.startDistanceMeters.toMissionPlanKilometers())
    }
    var endKilometers by remember(dayUi.day.id) {
        mutableStateOf(
            dayUi.day.endDistanceMeters
                ?.toMissionPlanKilometers()
                .orEmpty(),
        )
    }
    var error by remember(dayUi.day.id) { mutableStateOf<String?>(null) }
    val routeDistanceMeters = dayUi.route.summary.distanceMeters

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day ${dayUi.day.dayNumber} route range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text =
                        "Leave the end blank to use the rest of this GPX " +
                            "(${routeDistanceMeters.toMissionPlanDistance()}).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = startKilometers,
                    onValueChange = { startKilometers = it },
                    label = { Text("Start (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = endKilometers,
                    onValueChange = { endKilometers = it },
                    label = { Text("End (km, optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                error?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val startMeters = startKilometers.toDoubleOrNull()?.times(1_000.0)
                    val endMeters = endKilometers.takeIf(String::isNotBlank)?.toDoubleOrNull()?.times(1_000.0)
                    val actualEndMeters = endMeters ?: routeDistanceMeters
                    error =
                        when {
                            startMeters == null || !startMeters.isFinite() || startMeters < 0.0 ->
                                "Enter a valid non-negative start distance."
                            endMeters != null && (!endMeters.isFinite() || endMeters > routeDistanceMeters) ->
                                "The end must be within this GPX."
                            startMeters >= actualEndMeters -> "The end must be after the start."
                            else -> null
                        }
                    if (error == null) onConfirm(requireNotNull(startMeters), endMeters)
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

fun MissionPlanDayUi.missionPlanTodaySummary(): String = briefing.missionPlanBriefingSummary()

private fun RouteLibrarySummary.missionPlanRouteSummary(): String =
    listOf(
        distanceMeters.toMissionPlanDistance(),
        "+${elevationGainMeters.roundToInt()} m",
        estimatedDurationSeconds.toMissionPlanDuration(),
    ).joinToString("  •  ")

private fun MissionPlanDay.missionPlanRangeSummary(routeDistanceMeters: Double): String =
    "Route range  •  ${startDistanceMeters.toMissionPlanDistance()} – " +
        endDistanceFor(routeDistanceMeters).toMissionPlanDistance()

private fun com.glancemap.trailcore.profile.TrailWindow.missionPlanBriefingSummary(): String =
    listOf(
        distanceMeters.toMissionPlanDistance(),
        "+${ascentMeters.roundToInt()} m",
        estimatedDurationSeconds.toMissionPlanDuration(),
    ).joinToString("  •  ")

private fun Double.toMissionPlanDistance(): String = if (this < 1_000.0) "${roundToInt()} m" else "%.1f km".format(this / 1_000.0)

private fun Double.toMissionPlanDuration(): String {
    val totalMinutes = (this / 60.0).roundToInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0) "$minutes min" else "$hours h ${minutes.toString().padStart(2, '0')} min"
}

private fun Double.toMissionPlanKilometers(): String = "%.2f".format(this / 1_000.0)
