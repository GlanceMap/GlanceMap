@file:Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList", "MaxLineLength", "TooManyFunctions")

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
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
    onLoadDayWeather: (dayId: String, forceRefresh: Boolean) -> Unit,
    onOpenRoutes: () -> Unit,
    onRetry: () -> Unit,
) {
    var editedDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }
    var timelineDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }
    var weatherDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }

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
                            weather =
                                uiState.weatherByDayId[dayUi.day.id]
                                    ?.takeIf { weather ->
                                        weather.plannedDate == dayUi.day.plannedDate &&
                                            weather.plannedStartTime == dayUi.day.plannedStartTime
                                    },
                            onSetToday = { onSetToday(dayUi) },
                            onOpenTimeline = { timelineDay = dayUi },
                            onOpenWeather = { weatherDay = dayUi },
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

    timelineDay?.let { dayUi ->
        MissionDayTimelineDialog(
            dayUi = dayUi,
            onDismiss = { timelineDay = null },
        )
    }

    weatherDay
        ?.let { requestedDay -> uiState.days.firstOrNull { dayUi -> dayUi.day.id == requestedDay.day.id } }
        ?.let { dayUi ->
            MissionDayWeatherDialog(
                dayUi = dayUi,
                weather =
                    uiState.weatherByDayId[dayUi.day.id]
                        ?.takeIf { state ->
                            state.plannedDate == dayUi.day.plannedDate &&
                                state.plannedStartTime == dayUi.day.plannedStartTime
                        },
                onLoad = { forceRefresh -> onLoadDayWeather(dayUi.day.id, forceRefresh) },
                onDismiss = { weatherDay = null },
            )
        }
}

@Composable
private fun MissionPlanDayCard(
    dayUi: MissionPlanDayUi,
    selected: Boolean,
    weather: MissionDayWeatherUiState?,
    onSetToday: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenWeather: () -> Unit,
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
            listOfNotNull(
                dayUi.day.plannedDate?.missionPlanDateLabel(),
                dayUi.day.plannedStartTime?.let { time -> "Start $time" },
            ).takeIf { schedule -> schedule.isNotEmpty() }?.let { schedule ->
                Text(
                    text = schedule.joinToString(" • "),
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
            OutlinedButton(onClick = onOpenTimeline, modifier = Modifier.fillMaxWidth()) {
                Text("View journey")
            }
            OutlinedButton(onClick = onOpenWeather, modifier = Modifier.fillMaxWidth()) {
                Text(weather.missionDayWeatherActionLabel())
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
private fun MissionDayWeatherDialog(
    dayUi: MissionPlanDayUi,
    weather: MissionDayWeatherUiState?,
    onLoad: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val plannedDate = dayUi.day.plannedDate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day ${dayUi.day.dayNumber} weather") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = dayUi.day.name ?: dayUi.route.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (plannedDate == null) {
                    Text(
                        text = "Add a date in Edit day to load a forecast for this planned hike.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text =
                            listOfNotNull(
                                plannedDate.missionPlanDateLabel() ?: plannedDate,
                                dayUi.day.plannedStartTime?.let { time -> "Start $time" },
                                "GPX start, midpoint, and finish",
                            ).joinToString(" • "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when {
                        weather?.isLoading == true -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Loading three route forecasts…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        weather?.samples?.isNotEmpty() == true -> {
                            weather.samples.forEach { sample ->
                                MissionDayWeatherSampleRow(sample, plannedDate)
                            }
                        }

                        else -> {
                            Text(
                                text = "Load forecasts when you are ready. They remain available from the local cache when offline.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    weather?.message?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "Weather is planning context, not a safety decision. Data by Open-Meteo.com.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (plannedDate != null && weather?.isLoading != true) {
                TextButton(onClick = { onLoad(weather?.samples?.isNotEmpty() == true) }) {
                    Text(if (weather?.samples?.isNotEmpty() == true) "Update" else "Load forecasts")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun MissionDayWeatherSampleRow(
    sample: MissionDayWeatherSampleUi,
    plannedDate: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${sample.target.position.label.uppercase()} • ${sample.target.distanceFromDayStartMeters.toMissionPlanDistance()}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
        when {
            sample.forecast == null -> {
                Text(
                    text = sample.message ?: "Forecast unavailable",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            sample.dailyOutlook == null && sample.scheduledOutlook == null -> {
                Text(
                    text = "No forecast is available for $plannedDate at this location.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> {
                val outlook = sample.dailyOutlook
                sample.scheduledOutlook?.let { scheduled ->
                    Text(
                        text = "Around ${sample.scheduledTimeIso8601?.toMissionWeatherTimeText() ?: "planned time"}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = weatherConditionText(scheduled.weatherCode),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val scheduledMetrics =
                        listOfNotNull(
                            scheduled.precipitationProbabilityPercent?.let { value -> "rain ${value.roundToInt()}%" },
                            scheduled.windGustKilometersPerHour?.let { value -> "gusts ${value.roundToInt()} km/h" },
                            scheduled.visibilityMeters?.let { value -> "visibility ${value.toMissionPlanDistance()}" },
                        )
                    if (scheduledMetrics.isNotEmpty()) {
                        Text(
                            text = scheduledMetrics.joinToString("  •  "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                outlook?.let { daily ->
                    Text(
                        text = if (sample.scheduledOutlook == null) "Daily outlook" else "Day range",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (sample.scheduledOutlook == null) {
                        Text(
                            text = weatherConditionText(daily.weatherCode),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val dailyMetrics =
                        listOfNotNull(
                            daily.minimumTemperatureCelsius?.let { value -> "low ${value.toMissionWeatherTemperature()}" },
                            daily.maximumTemperatureCelsius?.let { value -> "high ${value.toMissionWeatherTemperature()}" },
                            daily.precipitationProbabilityPercent?.let { value -> "rain ${value.roundToInt()}%" },
                            daily.windGustKilometersPerHour?.let { value -> "gusts ${value.roundToInt()} km/h" },
                        )
                    if (dailyMetrics.isNotEmpty()) {
                        Text(
                            text = dailyMetrics.joinToString("  •  "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text =
                        "Updated ${sample.forecast.fetchedAtEpochMillis.toMissionWeatherUpdatedText()}" +
                            " • ${sample.savedSnapshotCount} saved snapshot${if (sample.savedSnapshotCount == 1) "" else "s"}" +
                            when {
                                sample.isStale -> " • cached, unable to update"
                                sample.isCached -> " • cached"
                                else -> ""
                            },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MissionDayTimelineDialog(
    dayUi: MissionPlanDayUi,
    onDismiss: () -> Unit,
) {
    val timeline = dayUi.timeline
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day ${dayUi.day.dayNumber} journey") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = dayUi.day.name ?: dayUi.route.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text =
                        "From GPX route distance, elevation, and waypoints. It does not infer trail " +
                            "conditions or hazards.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                timeline.events.forEach { event ->
                    MissionDayTimelineEventRow(event)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun MissionDayTimelineEventRow(event: MissionDayTimelineEvent) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = event.type.timelineLabel(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(text = event.title, style = MaterialTheme.typography.bodyLarge)
        event.detail?.let { detail ->
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text =
                listOf(
                    event.distanceFromDayStartMeters.toMissionPlanDistance(),
                    event.estimatedOffsetSeconds.toMissionPlanOffset(),
                ).joinToString("  •  "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
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
    var plannedStartTime by remember(dayUi.day.id) { mutableStateOf(dayUi.day.plannedStartTime.orEmpty()) }
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
                        "Name, date, start time, overnight, and notes live with this mission. Leave the end blank " +
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
                    value = plannedStartTime,
                    onValueChange = { plannedStartTime = it },
                    label = { Text("Planned start (HH:mm, optional)") },
                    supportingText = { Text("Aligns hourly weather to the GPX start, midpoint, and finish.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                    val normalizedStartTime = plannedStartTime.trim().takeIf(String::isNotEmpty)
                    error =
                        when {
                            normalizedDate != null && normalizedDate.toMissionPlanDate() == null ->
                                "Use a calendar date like 2026-08-12."
                            normalizedStartTime != null && normalizedStartTime.toMissionPlanStartTime() == null ->
                                "Use a 24-hour start time like 07:30."
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
                                plannedStartTime = normalizedStartTime,
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

private fun Double.toMissionPlanOffset(): String = if (this <= 0.0) "Start" else "+${toMissionPlanDuration()}"

private fun Double.toMissionPlanKilometers(): String = "%.2f".format(this / 1_000.0)

private fun String.toMissionPlanDate(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun String.toMissionPlanStartTime(): LocalTime? = runCatching { LocalTime.parse(this) }.getOrNull()

private fun String.missionPlanDateLabel(): String? = toMissionPlanDate()?.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

private fun MissionDayTimelineEventType.timelineLabel(): String =
    when (this) {
        MissionDayTimelineEventType.START -> "START"
        MissionDayTimelineEventType.CLIMB -> "CLIMB"
        MissionDayTimelineEventType.WAYPOINT -> "GPX WAYPOINT"
        MissionDayTimelineEventType.FINISH -> "FINISH"
    }

private fun MissionDayWeatherUiState?.missionDayWeatherActionLabel(): String =
    when {
        this?.isLoading == true -> "Loading day weather…"
        this?.samples?.isNotEmpty() == true -> "View day weather"
        else -> "Day weather"
    }

private fun Double.toMissionWeatherTemperature(): String = "${roundToInt()}°C"

private fun Long.toMissionWeatherUpdatedText(): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))

private fun String.toMissionWeatherTimeText(): String =
    runCatching {
        java.time.LocalDateTime
            .parse(this)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(this)
