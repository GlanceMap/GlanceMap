@file:Suppress("FunctionNaming")

package com.glancemap.glancemapcompanionapp.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
) // A single save validates the complete adventure before generating calendar days.
@Composable
internal fun MissionAdventureEditor(
    adventure: MissionAdventure?,
    existingDays: Int,
    onDismiss: () -> Unit,
    onSave: (MissionAdventureUpdate) -> Unit,
) {
    var name by remember { mutableStateOf(adventure?.name.orEmpty()) }
    var start by remember { mutableStateOf(adventure?.startDate ?: LocalDate.now().toString()) }
    var end by remember {
        mutableStateOf(
            adventure?.endDate
                ?: LocalDate.parse(start).plusDays((existingDays - 1).coerceAtLeast(0).toLong()).toString(),
        )
    }
    var people by remember { mutableStateOf((adventure?.people ?: 1).toString()) }
    var notes by remember { mutableStateOf(adventure?.notes.orEmpty()) }
    var dateField by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (adventure == null) R.string.adventure_new else R.string.adventure_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.adventure_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adventure_start_date), style = MaterialTheme.typography.labelSmall)
                        OutlinedButton(onClick = { dateField = 0 }) { Text(start.missionPlanDateLabel() ?: start) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adventure_end_date), style = MaterialTheme.typography.labelSmall)
                        OutlinedButton(onClick = { dateField = 1 }) { Text(end.missionPlanDateLabel() ?: end) }
                    }
                }
                OutlinedTextField(
                    people,
                    { people = it },
                    label = { Text(stringResource(R.string.adventure_people)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    notes,
                    { notes = it },
                    label = { Text(stringResource(R.string.mission_plan_notes_optional)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val count = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1
                val party = people.toIntOrNull()
                error =
                    when {
                        name.isBlank() -> R.string.adventure_name_required
                        party == null || party !in 1..99 -> R.string.adventure_people_invalid
                        count !in 1..366 -> R.string.adventure_dates_invalid
                        count < existingDays -> R.string.adventure_dates_preserve_days
                        else -> null
                    }
                if (error == null) onSave(MissionAdventureUpdate(name.trim(), start, end, requireNotNull(party), notes))
            }) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
    dateField?.let { field ->
        MissionDatePicker(
            date = if (field == 0) start else end,
            onPick = { date ->
                if (field == 0) {
                    val span = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)).coerceAtLeast(0)
                    start = date
                    end = LocalDate.parse(date).plusDays(span).toString()
                } else {
                    end = date
                }
                dateField = null
            },
            onDismiss = { dateField = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionDatePicker(
    date: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis =
                LocalDate
                    .parse(date)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    onPick(
                        Instant
                            .ofEpochMilli(it)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .toString(),
                    )
                }
            }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.common_action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    ) { DatePicker(picker) }
}

@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
) // Validate optional segment and weather coordinates together before saving.
@Composable
internal fun MissionDayDetailsEditor(
    day: MissionPlanDay,
    routeDay: MissionPlanDayUi?,
    inheritedLocation: WeatherForecastLocation?,
    onDismiss: () -> Unit,
    onSave: (MissionPlanDayUpdate) -> Unit,
) {
    var name by remember(day.id) { mutableStateOf(day.name.orEmpty()) }
    var startTime by remember(day.id) { mutableStateOf(day.plannedStartTime.orEmpty()) }
    var overnight by remember(day.id) { mutableStateOf(day.overnight.orEmpty()) }
    var notes by remember(day.id) { mutableStateOf(day.notes.orEmpty()) }
    var range by remember(day.id) { mutableStateOf(false) }
    var reviewedStop by remember(day.id) { mutableStateOf(false) }
    var startKm by remember(day.id) { mutableStateOf(day.startDistanceMeters.toMissionPlanKilometers()) }
    var endKm by remember(day.id) { mutableStateOf(day.endDistanceMeters?.toMissionPlanKilometers().orEmpty()) }
    var customLocation by remember(day.id) { mutableStateOf(day.weatherLocation != null) }
    var latitude by remember(day.id) {
        mutableStateOf(
            day.weatherLocation
                ?.latitude
                ?.toString()
                .orEmpty(),
        )
    }
    var longitude by remember(day.id) {
        mutableStateOf(
            day.weatherLocation
                ?.longitude
                ?.toString()
                .orEmpty(),
        )
    }
    var error by remember { mutableStateOf<Int?>(null) }
    val weatherLabel = stringResource(R.string.adventure_weather_stay)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mission_plan_edit_day_value, day.dayNumber)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.mission_plan_day_name_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    overnight,
                    { overnight = it },
                    label = { Text(stringResource(R.string.adventure_place_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    notes,
                    { notes = it },
                    label = { Text(stringResource(R.string.mission_plan_notes_optional)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    startTime,
                    { startTime = it },
                    label = { Text(stringResource(R.string.mission_plan_planned_start_hh_mm_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (day.stopReviewRequired) {
                    Text(stringResource(R.string.stage_review_stay), color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = reviewedStop, onCheckedChange = { reviewedStop = it })
                        Text(stringResource(R.string.stage_reviewed_stay))
                    }
                }
                if (day.isRestDay) {
                    Text(
                        inheritedLocation?.label ?: stringResource(R.string.adventure_rest_weather_location),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = customLocation, onCheckedChange = { customLocation = it })
                        Text(stringResource(R.string.adventure_custom_weather_location))
                    }
                    if (customLocation) {
                        OutlinedTextField(
                            latitude,
                            { latitude = it },
                            label = { Text(stringResource(R.string.adventure_latitude)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            longitude,
                            { longitude = it },
                            label = { Text(stringResource(R.string.adventure_longitude)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (routeDay != null && day.stageGroupId == null) {
                    TextButton(onClick = { range = !range }) { Text(stringResource(R.string.adventure_gpx_segment)) }
                    if (range) {
                        OutlinedTextField(
                            startKm,
                            { startKm = it },
                            label = { Text(stringResource(R.string.mission_plan_start_km)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            endKm,
                            { endKm = it },
                            label = { Text(stringResource(R.string.mission_plan_end_km_optional)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                error?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val time = startTime.trim().takeIf(String::isNotEmpty)
                val start =
                    if (range) {
                        startKm
                            .replace(
                                ',',
                                '.',
                            ).toDoubleOrNull()
                            ?.times(1_000.0)
                    } else {
                        day.startDistanceMeters
                    }
                val end =
                    if (range) {
                        endKm
                            .takeIf(String::isNotBlank)
                            ?.replace(',', '.')
                            ?.toDoubleOrNull()
                            ?.times(1_000.0)
                    } else {
                        day.endDistanceMeters
                    }
                val location =
                    if (day.isRestDay && customLocation) {
                        runCatching {
                            WeatherForecastLocation(
                                latitude.replace(',', '.').toDouble(),
                                longitude
                                    .replace(
                                        ',',
                                        '.',
                                    ).toDouble(),
                                null,
                                overnight.trim().ifEmpty { weatherLabel },
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                val routeEnd = routeDay?.route?.summary?.distanceMeters
                error =
                    when {
                        time != null &&
                            runCatching { LocalTime.parse(time) }.isFailure ->
                            R.string.mission_plan_use_a_24_hour_start_time_like
                        range &&
                            (
                                start == null ||
                                    !start.isFinite() ||
                                    start < 0 ||
                                    (
                                        endKm.isNotBlank() &&
                                            end == null
                                    ) ||
                                    (end != null && !end.isFinite()) ||
                                    (
                                        routeEnd != null &&
                                            (
                                                end
                                                    ?: routeEnd
                                            ) > routeEnd
                                    ) ||
                                    start >= (end ?: routeEnd ?: 0.0)
                            ) -> R.string.adventure_segment_invalid
                        day.isRestDay && customLocation && location == null -> R.string.adventure_coordinates_invalid
                        else -> null
                    }
                if (error == null) {
                    onSave(
                        day.toUpdate().copy(
                            name = name,
                            plannedStartTime = time,
                            overnight = overnight,
                            notes = notes,
                            startDistanceMeters = requireNotNull(start),
                            endDistanceMeters = end,
                            weatherLocation = if (day.isRestDay) location else day.weatherLocation,
                            reviewedStop = reviewedStop,
                        ),
                    )
                }
            }) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}
