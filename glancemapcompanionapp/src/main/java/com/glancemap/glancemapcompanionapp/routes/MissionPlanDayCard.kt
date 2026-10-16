@file:Suppress("FunctionNaming")

package com.glancemap.glancemapcompanionapp.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import kotlin.math.roundToInt

@Suppress(
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
) // All day actions remain local to this expandable card.
@Composable
internal fun MissionCompactDayCard(
    day: MissionPlanDay,
    routeDay: MissionPlanDayUi?,
    people: Int,
    weather: MissionDayWeatherUiState?,
    weatherLocation: WeatherForecastLocation?,
    expanded: Boolean,
    selected: Boolean,
    onExpand: () -> Unit,
    onUpdate: ((MissionPlanDay) -> MissionPlanDayUpdate) -> Unit,
    onChooseRoute: () -> Unit,
    onDetails: () -> Unit,
    onChangeArrival: () -> Unit,
    onLoadWeather: (Boolean) -> Unit,
    onSetToday: () -> Unit,
    onJourney: () -> Unit,
    onProfile: () -> Unit,
    onMove: (Int) -> Unit,
    index: Int,
    dayCount: Int,
    onRemove: () -> Unit,
) {
    val weatherDay = if (day.isRestDay) day.copy(weatherLocation = weatherLocation) else day
    val hasWeatherLocation = if (day.isRestDay) weatherLocation != null else routeDay != null
    val canLoadWeather = day.hasForecastDate() && hasWeatherLocation
    LaunchedEffect(expanded, weatherDay.weatherKey(), day.plannedDate, day.plannedStartTime) {
        if (expanded && weather == null && canLoadWeather) {
            onLoadWeather(false)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (
                        selected
                    ) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        MissionCompactDayHeader(day, routeDay, people, weather, weatherLocation, expanded, onExpand)
        if (expanded) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !day.isRestDay,
                        onClick = { onUpdate { it.toUpdate().copy(isRestDay = false) } },
                        label = { Text(stringResource(R.string.adventure_hiking_day)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = day.isRestDay,
                        onClick = { onUpdate { it.toUpdate().copy(isRestDay = true) } },
                        label = { Text(stringResource(R.string.adventure_day_off)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (day.stageGroupId != null) {
                    Text(stringResource(R.string.stage_linked_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onChangeArrival) { Text(stringResource(R.string.stage_change_arrival)) }
                }
                if (!day.isRestDay) {
                    OutlinedButton(onClick = onChooseRoute, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            routeDay?.route?.displayName ?: stringResource(R.string.adventure_choose_route),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                MissionInlineWeather(weatherDay, weather, canLoadWeather, onLoadWeather, onDetails)
                MissionDayPlanningFields(day, onUpdate)
                TextButton(onClick = onDetails) { Text(stringResource(R.string.adventure_details_notes)) }
                day.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (routeDay != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = onSetToday,
                            enabled = !selected,
                        ) {
                            Text(
                                stringResource(
                                    if (selected) R.string.mission_plan_today_2 else R.string.mission_plan_set_today,
                                ),
                            )
                        }
                        TextButton(onClick = onJourney) { Text(stringResource(R.string.mission_plan_view_journey)) }
                        TextButton(
                            onClick = onProfile,
                        ) { Text(stringResource(R.string.mission_plan_elevation_weather_profile)) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onMove(index - 1) },
                        enabled = index > 0,
                    ) { Text(stringResource(R.string.mission_plan_move_up)) }
                    TextButton(
                        onClick = { onMove(index + 1) },
                        enabled = index < dayCount - 1,
                    ) { Text(stringResource(R.string.mission_plan_move_down)) }
                    TextButton(
                        onClick = onRemove,
                        enabled = dayCount > 1,
                    ) { Text(stringResource(R.string.mission_plan_remove)) }
                }
            }
        }
    }
}

@Suppress("LongParameterList") // The header uses the same immutable day state as the card.
@Composable
private fun MissionCompactDayHeader(
    day: MissionPlanDay,
    routeDay: MissionPlanDayUi?,
    people: Int,
    weather: MissionDayWeatherUiState?,
    weatherLocation: WeatherForecastLocation?,
    expanded: Boolean,
    onExpand: () -> Unit,
) {
    val expandedLabel = stringResource(if (expanded) R.string.adventure_expanded else R.string.adventure_collapsed)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { stateDescription = expandedLabel }
                .clickable(role = Role.Button, onClick = onExpand)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            day.dayNumber.toString(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            day.plannedDate?.missionPlanDateLabel()?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Text(
                day.name ?: if (day.isRestDay) {
                    stringResource(R.string.adventure_day_off)
                } else {
                    routeDay?.route?.displayName ?: stringResource(R.string.adventure_route_to_choose)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (day.isRestDay) {
                    weatherLocation?.label ?: stringResource(R.string.adventure_location_needed)
                } else {
                    routeDay?.let {
                        "${it.briefing.distanceMeters.toMissionPlanDistance()} · " +
                            "↑ ${it.briefing.ascentMeters.roundToInt()} m"
                    } ?: stringResource(R.string.adventure_route_missing)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            MissionStopReviewWarning(day)
            Text(
                stringResource(day.accommodation.labelResource()) + " · " +
                    stringResource(R.string.adventure_to_pack, day.meals.count { it == MissionMeal.PACK } * people),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            weather.compactForecast(day),
            modifier = Modifier.weight(.55f),
            style = MaterialTheme.typography.labelSmall,
        )
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun MissionDayPlanningFields(
    day: MissionPlanDay,
    onUpdate: ((MissionPlanDay) -> MissionPlanDayUpdate) -> Unit,
) {
    Text(stringResource(R.string.adventure_meals_per_person), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            R.string.adventure_breakfast,
            R.string.adventure_lunch,
            R.string.adventure_dinner,
        ).forEachIndexed { mealIndex, label ->
            MissionChoice(
                label = stringResource(label),
                value = day.meals[mealIndex],
                choices = MissionMeal.entries.map { it to stringResource(it.labelResource()) },
                onChange = { meal ->
                    onUpdate { current ->
                        val update = current.toUpdate()
                        when (mealIndex) {
                            0 -> update.copy(breakfast = meal)
                            1 -> update.copy(lunch = meal)
                            else -> update.copy(dinner = meal)
                        }
                    }
                },
            )
        }
    }
    MissionChoice(
        label = stringResource(R.string.adventure_overnight),
        value = day.accommodation,
        choices = MissionOvernight.entries.map { it to stringResource(it.labelResource()) },
        onChange = { onUpdate { current -> current.toUpdate().copy(overnightType = it) } },
    )
    day.overnight?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (day.accommodation in listOf(MissionOvernight.REFUGE, MissionOvernight.CAMPSITE, MissionOvernight.OTHER)) {
        MissionChoice(
            label = stringResource(R.string.adventure_booking),
            value = day.booking ?: MissionBooking.NOT_REQUIRED,
            choices = MissionBooking.entries.map { it to stringResource(it.labelResource()) },
            onChange = { onUpdate { current -> current.toUpdate().copy(booking = it) } },
        )
    }
}

@Composable
internal fun <T> MissionChoice(
    label: String,
    value: T,
    choices: List<Pair<T, String>>,
    onChange: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(onClick = { open = true }) { Text(choices.first { it.first == value }.second) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (choice, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onChange(choice)
                })
            }
        }
    }
}

@Composable
private fun MissionInlineWeather(
    day: MissionPlanDay,
    weather: MissionDayWeatherUiState?,
    canLoad: Boolean,
    onLoad: (Boolean) -> Unit,
    onDetails: () -> Unit,
) {
    var showDetails by rememberSaveable(day.id) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(R.string.mission_plan_day_weather) + " · " + weather.compactForecast(day))
            }
        }
        if (day.isRestDay) {
            Text(
                day.weatherLocation?.label ?: stringResource(R.string.adventure_rest_weather_location),
                style = MaterialTheme.typography.bodySmall,
            )
            if (
                day.weatherLocation == null
            ) {
                TextButton(onClick = onDetails) { Text(stringResource(R.string.adventure_set_location)) }
            }
        }
        if (weather?.isLoading == true) CircularProgressIndicator()
        if (showDetails) {
            weather?.samples?.forEach { sample -> MissionDayWeatherSampleRow(sample, day.plannedDate.orEmpty()) }
            weather?.message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = { onLoad(true) },
                enabled =
                    canLoad &&
                        weather?.isLoading != true,
            ) { Text(stringResource(R.string.mission_plan_update)) }
            Text(stringResource(R.string.adventure_weather_attribution), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MissionDayWeatherUiState?.compactForecast(day: MissionPlanDay): String =
    when {
        day.plannedDate == null -> stringResource(R.string.adventure_weather_date_needed)
        day.forecastTooFarAhead() -> stringResource(R.string.adventure_forecast_later)
        this?.isLoading == true -> stringResource(R.string.mission_plan_loading_day_weather)
        else -> forecastMetrics()
    }

@Composable
private fun MissionDayWeatherUiState?.forecastMetrics(): String {
    val daily = this?.samples.orEmpty().mapNotNull(MissionDayWeatherSampleUi::dailyOutlook)
    val low = daily.mapNotNull { it.minimumTemperatureCelsius }.minOrNull()
    val high = daily.mapNotNull { it.maximumTemperatureCelsius }.maxOrNull()
    val rain = daily.mapNotNull { it.precipitationProbabilityPercent }.maxOrNull()
    val metrics =
        listOfNotNull(
            if (low != null && high != null) "${low.roundToInt()}–${high.roundToInt()}°C" else null,
            rain?.let { stringResource(R.string.mission_plan_rain_value, it.roundToInt()) },
            if (this?.samples?.any { it.isCached } == true) stringResource(R.string.adventure_saved_forecast) else null,
        )
    return metrics.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: stringResource(
        if (
            this?.samples?.isNotEmpty() == true
        ) {
            R.string.mission_plan_forecast_unavailable
        } else {
            R.string.mission_plan_day_weather
        },
    )
}

internal fun MissionMeal.labelResource(): Int =
    when (this) {
        MissionMeal.UNPLANNED -> R.string.adventure_unplanned
        MissionMeal.PACK -> R.string.adventure_pack
        MissionMeal.PROVIDED -> R.string.adventure_provided
        MissionMeal.NOT_NEEDED -> R.string.adventure_skip
    }

internal fun MissionOvernight.labelResource(): Int =
    when (this) {
        MissionOvernight.UNPLANNED -> R.string.adventure_unplanned
        MissionOvernight.BIVOUAC -> R.string.adventure_bivouac
        MissionOvernight.REFUGE -> R.string.adventure_refuge
        MissionOvernight.CAMPSITE -> R.string.adventure_campsite
        MissionOvernight.OTHER -> R.string.adventure_other_stay
        MissionOvernight.NONE -> R.string.adventure_no_stay
    }

internal fun MissionBooking.labelResource(): Int =
    when (this) {
        MissionBooking.NOT_REQUIRED -> R.string.adventure_booking_not_required
        MissionBooking.TO_BOOK -> R.string.adventure_to_book
        MissionBooking.BOOKED -> R.string.adventure_booked
    }
