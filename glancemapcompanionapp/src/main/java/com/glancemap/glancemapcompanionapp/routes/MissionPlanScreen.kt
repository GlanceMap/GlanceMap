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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
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
    val textResources = LocalResources.current
    var editedDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }
    var timelineDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }
    var weatherDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }
    var profileDay by remember { mutableStateOf<MissionPlanDayUi?>(null) }

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
                    contentDescription = textResources.getString(R.string.common_back_to_home_content_description),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = textResources.getString(R.string.menu_mission_plan),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = textResources.getString(R.string.mission_plan_plan_each_hiking_day_without_copying_gpx),
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
                        Text(textResources.getString(R.string.mission_plan_try_again))
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
                        text = textResources.getString(R.string.mission_plan_days),
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
                            onOpenProfile = { profileDay = dayUi },
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
                        text = textResources.getString(R.string.mission_plan_add_a_day),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (routes.isEmpty()) {
                    item {
                        Text(
                            text = textResources.getString(R.string.mission_plan_import_gpx_routes_first_then_add_each),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenRoutes) {
                            Text(textResources.getString(R.string.mission_plan_open_route_library))
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
                                    Text(text = route.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = route.summary.missionPlanRouteSummary(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                OutlinedButton(onClick = { onAddDay(route.id) }) {
                                    Text(textResources.getString(R.string.live_tracking_action_add))
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

    profileDay
        ?.let { requestedDay -> uiState.days.firstOrNull { dayUi -> dayUi.day.id == requestedDay.day.id } }
        ?.let { dayUi ->
            MissionDayPlanProfileDialog(
                dayUi = dayUi,
                weather =
                    uiState.weatherByDayId[dayUi.day.id]
                        ?.takeIf { state ->
                            state.plannedDate == dayUi.day.plannedDate &&
                                state.plannedStartTime == dayUi.day.plannedStartTime
                        },
                onDismiss = { profileDay = null },
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
    onOpenProfile: () -> Unit,
    onOpenWeather: () -> Unit,
    onEditDay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
) {
    val textResources = LocalResources.current
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
                text =
                    textResources.getString(
                        R.string.mission_plan_day_valuevalue,
                        dayUi.day.dayNumber,
                        if (selected) textResources.getString(R.string.mission_plan_today) else "",
                    ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = dayUi.day.name ?: dayUi.route.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!dayUi.day.name.isNullOrBlank()) {
                Text(
                    text = textResources.getString(R.string.mission_plan_route_value, dayUi.route.displayName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            listOfNotNull(
                dayUi.day.plannedDate?.missionPlanDateLabel(),
                dayUi.day.plannedStartTime?.let { time ->
                    textResources.getString(R.string.mission_plan_start_value, time)
                },
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
                    text = textResources.getString(R.string.mission_plan_overnight_value, overnight),
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
                Text(textResources.getString(R.string.mission_plan_view_journey))
            }
            OutlinedButton(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) {
                Text(textResources.getString(R.string.mission_plan_elevation_weather_profile))
            }
            OutlinedButton(onClick = onOpenWeather, modifier = Modifier.fillMaxWidth()) {
                Text(weather.missionDayWeatherActionLabel())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditDay, modifier = Modifier.weight(1f)) {
                    Text(textResources.getString(R.string.mission_plan_edit_day))
                }
                Button(onClick = onSetToday, enabled = !selected, modifier = Modifier.weight(1f)) {
                    Text(
                        if (selected) {
                            textResources.getString(
                                R.string.mission_plan_today_2,
                            )
                        } else {
                            textResources.getString(R.string.mission_plan_set_today)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Text(textResources.getString(R.string.mission_plan_move_up))
                    }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Text(textResources.getString(R.string.mission_plan_move_down))
                    }
                }
                TextButton(onClick = onRemove) {
                    Text(textResources.getString(R.string.mission_plan_remove))
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
    val textResources = LocalResources.current
    val plannedDate = dayUi.day.plannedDate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(textResources.getString(R.string.mission_plan_day_value_weather, dayUi.day.dayNumber)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = dayUi.day.name ?: dayUi.route.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (plannedDate == null) {
                    Text(
                        text = textResources.getString(R.string.mission_plan_add_a_date_in_edit_day_to),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text =
                            listOfNotNull(
                                plannedDate.missionPlanDateLabel() ?: plannedDate,
                                dayUi.day.plannedStartTime?.let { time ->
                                    textResources.getString(R.string.mission_plan_start_value, time)
                                },
                                textResources.getString(R.string.mission_plan_gpx_start_midpoint_and_finish),
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
                                    text = textResources.getString(R.string.mission_plan_loading_three_route_forecasts),
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
                                text =
                                    textResources.getString(
                                        R.string.mission_plan_load_forecasts_when_you_are_ready_they,
                                    ),
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
                        text = textResources.getString(R.string.mission_plan_weather_is_planning_context_not_a_safety),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (plannedDate != null && weather?.isLoading != true) {
                TextButton(onClick = { onLoad(weather?.samples?.isNotEmpty() == true) }) {
                    Text(
                        if (weather?.samples?.isNotEmpty() ==
                            true
                        ) {
                            textResources.getString(R.string.mission_plan_update)
                        } else {
                            textResources.getString(R.string.mission_plan_load_forecasts)
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(textResources.getString(R.string.common_action_done))
            }
        },
    )
}

@Composable
private fun MissionDayWeatherSampleRow(
    sample: MissionDayWeatherSampleUi,
    plannedDate: String,
) {
    val textResources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${textResources.getString(
                sample.target.position.labelResource(),
            ).uppercase()} • ${sample.target.distanceFromDayStartMeters.toMissionPlanDistance()}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
        when {
            sample.forecast == null -> {
                Text(
                    text = sample.message ?: textResources.getString(R.string.mission_plan_forecast_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            sample.dailyOutlook == null && sample.scheduledOutlook == null -> {
                Text(
                    text =
                        textResources.getString(
                            R.string.mission_plan_no_forecast_is_available_for_value_at,
                            plannedDate,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> {
                val outlook = sample.dailyOutlook
                sample.scheduledOutlook?.let { scheduled ->
                    Text(
                        text =
                            textResources.getString(
                                R.string.mission_plan_around_value,
                                sample.scheduledTimeIso8601?.toMissionWeatherTimeText()
                                    ?: textResources.getString(R.string.mission_plan_planned_time),
                            ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = weatherConditionText(textResources, scheduled.weatherCode),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val scheduledMetrics =
                        listOfNotNull(
                            scheduled.precipitationProbabilityPercent?.let { value ->
                                textResources.getString(R.string.mission_plan_rain_value, value.roundToInt())
                            },
                            scheduled.windGustKilometersPerHour?.let { value ->
                                textResources.getString(R.string.mission_plan_gusts_value_km_h, value.roundToInt())
                            },
                            scheduled.visibilityMeters?.let { value ->
                                textResources.getString(
                                    R.string.mission_plan_visibility_value,
                                    value.toMissionPlanDistance(),
                                )
                            },
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
                        text =
                            if (sample.scheduledOutlook ==
                                null
                            ) {
                                textResources.getString(R.string.mission_plan_daily_outlook)
                            } else {
                                textResources.getString(R.string.mission_plan_day_range)
                            },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (sample.scheduledOutlook == null) {
                        Text(
                            text = weatherConditionText(textResources, daily.weatherCode),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val dailyMetrics =
                        listOfNotNull(
                            daily.minimumTemperatureCelsius?.let { value ->
                                textResources.getString(
                                    R.string.mission_plan_low_value,
                                    value.toMissionWeatherTemperature(),
                                )
                            },
                            daily.maximumTemperatureCelsius?.let { value ->
                                textResources.getString(
                                    R.string.mission_plan_high_value,
                                    value.toMissionWeatherTemperature(),
                                )
                            },
                            daily.precipitationProbabilityPercent?.let { value ->
                                textResources.getString(R.string.mission_plan_rain_value, value.roundToInt())
                            },
                            daily.windGustKilometersPerHour?.let { value ->
                                textResources.getString(R.string.mission_plan_gusts_value_km_h, value.roundToInt())
                            },
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
                        textResources.getString(
                            R.string.mission_plan_updated_value,
                            sample.forecast.fetchedAtEpochMillis.toMissionWeatherUpdatedText(),
                        ) +
                            textResources.getQuantityString(
                                R.plurals.weather_saved_snapshots,
                                sample.savedSnapshotCount,
                                sample.savedSnapshotCount,
                            ) +
                            when {
                                sample.isStale -> textResources.getString(R.string.mission_plan_cached_unable_to_update)
                                sample.isCached -> textResources.getString(R.string.mission_plan_cached)
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
    val textResources = LocalResources.current
    val timeline = dayUi.timeline
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(textResources.getString(R.string.mission_plan_day_value_journey, dayUi.day.dayNumber)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = dayUi.day.name ?: dayUi.route.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text =
                        textResources.getString(R.string.mission_plan_from_gpx_route_distance_elevation_and_waypoints),
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
                Text(textResources.getString(R.string.common_action_done))
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
        Text(text = event.localizedTitle(), style = MaterialTheme.typography.bodyLarge)
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
                    event.estimatedOffsetSeconds.toMissionPlanOffset(stringResource(R.string.mission_timeline_start)),
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
    val textResources = LocalResources.current
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
        title = { Text(textResources.getString(R.string.mission_plan_edit_day_value, dayUi.day.dayNumber)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text =
                        textResources.getString(R.string.mission_plan_name_date_start_time_overnight_and_notes) +
                            textResources.getString(
                                R.string.mission_plan_to_use_the_rest_of_this_gpx,
                                routeDistanceMeters.toMissionPlanDistance(),
                            ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(textResources.getString(R.string.mission_plan_day_name_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = plannedDate,
                    onValueChange = { plannedDate = it },
                    label = { Text(textResources.getString(R.string.mission_plan_date_yyyy_mm_dd_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = plannedStartTime,
                    onValueChange = { plannedStartTime = it },
                    label = { Text(textResources.getString(R.string.mission_plan_planned_start_hh_mm_optional)) },
                    supportingText = {
                        Text(
                            textResources.getString(R.string.mission_plan_aligns_hourly_weather_to_the_gpx_start),
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = startKilometers,
                    onValueChange = { startKilometers = it },
                    label = { Text(textResources.getString(R.string.mission_plan_start_km)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = overnight,
                    onValueChange = { overnight = it },
                    label = { Text(textResources.getString(R.string.mission_plan_overnight_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(textResources.getString(R.string.mission_plan_notes_optional)) },
                    minLines = 2,
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = endKilometers,
                    onValueChange = { endKilometers = it },
                    label = { Text(textResources.getString(R.string.mission_plan_end_km_optional)) },
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
                                textResources.getString(R.string.mission_plan_use_a_calendar_date_like_2026_08)
                            normalizedStartTime != null && normalizedStartTime.toMissionPlanStartTime() == null ->
                                textResources.getString(R.string.mission_plan_use_a_24_hour_start_time_like)
                            startMeters == null || !startMeters.isFinite() || startMeters < 0.0 ->
                                textResources.getString(R.string.mission_plan_enter_a_valid_non_negative_start_distance)
                            endMeters != null && (!endMeters.isFinite() || endMeters > routeDistanceMeters) ->
                                textResources.getString(R.string.mission_plan_the_end_must_be_within_this_gpx)
                            startMeters >= actualEndMeters ->
                                textResources.getString(
                                    R.string.mission_plan_the_end_must_be_after_the_start,
                                )
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
                Text(textResources.getString(R.string.common_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(textResources.getString(R.string.common_action_cancel))
            }
        },
    )
}

@Composable
private fun MissionPlanEmptyState(routesAvailable: Boolean) {
    val textResources = LocalResources.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = textResources.getString(R.string.mission_plan_your_mission_starts_here),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text =
                    if (routesAvailable) {
                        textResources.getString(R.string.mission_plan_add_a_route_below_for_each_hiking)
                    } else {
                        textResources.getString(R.string.mission_plan_import_a_gpx_route_first_you_can)
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MissionPlanUnavailableDaysCard(unavailableDayCount: Int) {
    val textResources = LocalResources.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text =
                textResources.getQuantityString(
                    R.plurals.mission_unavailable_days,
                    unavailableDayCount,
                    unavailableDayCount,
                ),
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

@Composable
private fun MissionPlanDay.missionPlanRangeSummary(routeDistanceMeters: Double): String {
    val textResources = LocalResources.current
    return textResources.getString(
        R.string.mission_plan_route_range,
        startDistanceMeters.toMissionPlanDistance(),
        endDistanceFor(routeDistanceMeters).toMissionPlanDistance(),
    )
}

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

private fun Double.toMissionPlanOffset(label: String) = if (this <= 0.0) label else "+${toMissionPlanDuration()}"

private fun Double.toMissionPlanKilometers(): String = "%.2f".format(this / 1_000.0)

private fun String.toMissionPlanDate(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun String.toMissionPlanStartTime(): LocalTime? = runCatching { LocalTime.parse(this) }.getOrNull()

private fun String.missionPlanDateLabel(): String? = toMissionPlanDate()?.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

@Composable
private fun MissionDayTimelineEventType.timelineLabel(): String {
    val textResources = LocalResources.current
    return when (this) {
        MissionDayTimelineEventType.START -> textResources.getString(R.string.mission_plan_start)
        MissionDayTimelineEventType.CLIMB -> textResources.getString(R.string.mission_plan_climb)
        MissionDayTimelineEventType.WAYPOINT -> textResources.getString(R.string.mission_plan_gpx_waypoint)
        MissionDayTimelineEventType.FINISH -> textResources.getString(R.string.mission_plan_finish)
    }
}

@Composable
private fun MissionDayWeatherUiState?.missionDayWeatherActionLabel(): String {
    val textResources = LocalResources.current
    return when {
        this?.isLoading == true -> textResources.getString(R.string.mission_plan_loading_day_weather)
        this?.samples?.isNotEmpty() == true -> textResources.getString(R.string.mission_plan_view_day_weather)
        else -> textResources.getString(R.string.mission_plan_day_weather)
    }
}

private fun Double.toMissionWeatherTemperature(): String = "${roundToInt()}°C"

private fun Long.toMissionWeatherUpdatedText(): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))

private fun String.toMissionWeatherTimeText(): String =
    runCatching {
        java.time.LocalDateTime
            .parse(this)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(this)

@Composable
private fun MissionDayTimelineEvent.localizedTitle(): String =
    when (type) {
        MissionDayTimelineEventType.START -> stringResource(R.string.mission_timeline_start)
        MissionDayTimelineEventType.CLIMB -> stringResource(R.string.mission_timeline_climb)
        MissionDayTimelineEventType.FINISH -> stringResource(R.string.mission_timeline_finish)
        MissionDayTimelineEventType.WAYPOINT -> title
    }
