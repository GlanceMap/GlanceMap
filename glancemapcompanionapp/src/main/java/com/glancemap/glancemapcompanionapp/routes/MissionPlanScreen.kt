@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MaxLineLength", "TooManyFunctions")

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MissionPlanScreen(
    uiState: MissionPlanUiState,
    routes: List<RouteLibraryRoute>,
    onBack: () -> Unit,
    onAddDay: (String) -> Unit,
    onSetToday: (MissionPlanDayUi) -> Unit,
    onUpdateDay: (dayId: String, update: MissionPlanDayUpdate) -> Unit,
    onMoveDay: (dayId: String, targetIndex: Int) -> Unit,
    onRemoveDay: (String) -> Unit,
    onOpenRoutes: () -> Unit,
    onRetry: () -> Unit,
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = message)
                    TextButton(onClick = onRetry) {
                        Text("Try again")
                    }
                }
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
                if (uiState.unavailableDayCount > 0) {
                    item {
                        MissionPlanUnavailableDaysCard(uiState.unavailableDayCount)
                    }
                }
                if (uiState.days.isEmpty() && uiState.unavailableDayCount == 0) {
                    item {
                        MissionPlanEmptyState(routesAvailable = routes.isNotEmpty())
                    }
                } else {
                    itemsIndexed(uiState.days, key = { _, dayUi -> dayUi.day.id }) { index, dayUi ->
                        MissionPlanDayCard(
                            dayUi = dayUi,
                            selected = dayUi.day.id == uiState.selectedDayId,
                            onSetToday = { onSetToday(dayUi) },
                            onEditDay = { editedDay = dayUi },
                            onMoveUp = { onMoveDay(dayUi.day.id, index - 1) },
                            onMoveDown = { onMoveDay(dayUi.day.id, index + 1) },
                            canMoveUp = index > 0,
                            canMoveDown = index < uiState.days.lastIndex,
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
        MissionPlanDayEditorDialog(
            dayUi = dayUi,
            onDismiss = { editedDay = null },
            onConfirm = { update ->
                onUpdateDay(dayUi.day.id, update)
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
    onEditDay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
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
                text = dayUi.day.name ?: dayUi.route.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!dayUi.day.name.isNullOrBlank()) {
                Text(
                    text = "Route: ${dayUi.route.title}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            dayUi.day.plannedDate?.missionPlanDateLabel()?.let { date ->
                Text(
                    text = date,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
            dayUi.day.overnight?.let { overnight ->
                Text(
                    text = "Overnight: $overnight",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            dayUi.day.notes?.let { notes ->
                Text(
                    text = notes,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditDay, modifier = Modifier.weight(1f)) {
                    Text("Edit day")
                }
                Button(onClick = onSetToday, enabled = !selected, modifier = Modifier.weight(1f)) {
                    Text(if (selected) "Today" else "Set today")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Text("Move up")
                    }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Text("Move down")
                    }
                }
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun MissionPlanDayEditorDialog(
    dayUi: MissionPlanDayUi,
    onDismiss: () -> Unit,
    onConfirm: (MissionPlanDayUpdate) -> Unit,
) {
    var name by remember(dayUi.day.id) { mutableStateOf(dayUi.day.name.orEmpty()) }
    var plannedDate by remember(dayUi.day.id) { mutableStateOf(dayUi.day.plannedDate.orEmpty()) }
    var overnight by remember(dayUi.day.id) { mutableStateOf(dayUi.day.overnight.orEmpty()) }
    var notes by remember(dayUi.day.id) { mutableStateOf(dayUi.day.notes.orEmpty()) }
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
        title = { Text("Edit day ${dayUi.day.dayNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text =
                        "Name, date, overnight, and notes live with this mission. Leave the end blank " +
                            "to use the rest of this GPX (${routeDistanceMeters.toMissionPlanDistance()}).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Day name (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = plannedDate,
                    onValueChange = { plannedDate = it },
                    label = { Text("Date (YYYY-MM-DD, optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = startKilometers,
                    onValueChange = { startKilometers = it },
                    label = { Text("Start (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = overnight,
                    onValueChange = { overnight = it },
                    label = { Text("Overnight (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 4,
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
                    val normalizedDate = plannedDate.trim().takeIf(String::isNotEmpty)
                    error =
                        when {
                            normalizedDate != null && normalizedDate.toMissionPlanDate() == null ->
                                "Use a calendar date like 2026-08-12."
                            startMeters == null || !startMeters.isFinite() || startMeters < 0.0 ->
                                "Enter a valid non-negative start distance."
                            endMeters != null && (!endMeters.isFinite() || endMeters > routeDistanceMeters) ->
                                "The end must be within this GPX."
                            startMeters >= actualEndMeters -> "The end must be after the start."
                            else -> null
                        }
                    if (error == null) {
                        onConfirm(
                            MissionPlanDayUpdate(
                                name = name,
                                plannedDate = normalizedDate,
                                overnight = overnight,
                                notes = notes,
                                startDistanceMeters = requireNotNull(startMeters),
                                endDistanceMeters = endMeters,
                            ),
                        )
                    }
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

@Composable
private fun MissionPlanEmptyState(routesAvailable: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "Your mission starts here", style = MaterialTheme.typography.titleSmall)
            Text(
                text =
                    if (routesAvailable) {
                        "Add a route below for each hiking day, or add the same long GPX more than once and " +
                            "set the range for every day."
                    } else {
                        "Import a GPX route first. You can then make a day from the full route or just a range."
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MissionPlanUnavailableDaysCard(unavailableDayCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text =
                "$unavailableDayCount saved ${if (unavailableDayCount == 1) "day is" else "days are"} " +
                    "unavailable because its GPX route cannot be read. Re-import the matching route before " +
                    "sending that day to the watch.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
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

private fun String.toMissionPlanDate(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun String.missionPlanDateLabel(): String? = toMissionPlanDate()?.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
