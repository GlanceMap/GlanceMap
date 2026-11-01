package com.glancemap.glancemapcompanionapp.hike

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneGeneralSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneGuidancePanel
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceSessionStore
import com.glancemap.glancemapcompanionapp.map.isPhoneGuidanceFixFresh
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PhoneHikeWorkspace(
    controller: PhoneHikeViewModel,
    watchContent: @Composable () -> Unit = {},
) {
    val ui by controller.state.collectAsState()
    val guidance by PhoneGuidanceSessionStore.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nowElapsedMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                nowElapsedMillis = SystemClock.elapsedRealtime()
                delay(1_000L)
            }
        }
    }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val metric = remember(context) { PhoneGeneralSettingsPreferences(context).load().isMetric }
    var scheduleOpen by rememberSaveable { mutableStateOf(false) }
    var selectedCheckpoints by rememberSaveable(ui.data.selectedId, ui.data.previewSelected) {
        mutableStateOf(emptyList<String>())
    }
    val model = ui.model
    val plan = ui.plan
    val guidanceMatches = controller.matchesGuidance(model)
    val current =
        ui.currentPosition.takeIf {
            isPhoneGuidanceFixFresh(ui.currentFixElapsedMillis, nowElapsedMillis, ui.currentDeliveryIntervalMillis) &&
                (
                    !guidanceMatches ||
                        !guidance.paused &&
                        guidance.message == com.glancemap.glancemapcompanionapp.map.PhoneGuidanceMessage.ACTIVE
                )
        }
    val selected = if (plan?.inspecting == true) ui.cursor else current ?: model?.atDistance(0.0)
    val live =
        !ui.data.previewSelected &&
            guidanceMatches &&
            !guidance.paused &&
            guidance.message == com.glancemap.glancemapcompanionapp.map.PhoneGuidanceMessage.ACTIVE
    val zone = ui.routeZone()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(!ui.data.previewSelected, {
                controller.selectTab(false)
            }, { Text(stringResource(R.string.briefing_today_s_hike)) }, modifier = Modifier.testTag("hike-today-tab"))
            if (ui.data.previewId != null) {
                FilterChip(ui.data.previewSelected && ui.data.selectedPreviewTabId == null, {
                    controller.selectTab(true)
                }, { Text(stringResource(R.string.hike_preview)) }, modifier = Modifier.testTag("hike-preview-tab"))
            }
            ui.data.previewTabs.forEach { tab ->
                FilterChip(
                    selected = ui.data.previewSelected && ui.data.selectedPreviewTabId == tab.routeId,
                    onClick = { controller.selectPreviewTab(tab.routeId) },
                    label = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("hike-route-tab-${tab.routeId}"),
                )
            }
        }
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.message?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!ui.ready) TextButton(onClick = { controller.reload() }) { Text(stringResource(R.string.hike_retry)) }
        if (model != null && plan != null && selected != null) {
            Text(
                model.route.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            val timeline = remember(model, plan.checkpoints) { PhoneHikeTimeline(model, plan) }
            val departure = plan.departureInstant(zone)
            val finish = departure?.plusSeconds(timeline.totalSeconds.toLong())
            val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
            val finishLabel =
                finish?.atZone(zone)?.let { finishTime ->
                    if (finishTime.toLocalDate() == departure.atZone(zone).toLocalDate()) {
                        finishTime.format(timeFormat)
                    } else {
                        finishTime.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
                    }
                } ?: "—"
            val selectedSeconds =
                ui.cursorPlannedSeconds?.takeIf { seconds ->
                    plan.inspecting &&
                        abs(timeline.atSeconds(seconds).distanceMeters - selected.distanceMeters) < 0.1
                } ?: timeline.secondsAt(selected)
            val arrival = departure?.plusSeconds(selectedSeconds.toLong())
            val lookup =
                remember(model, plan.checkpoints, ui.weather) {
                    PhoneHikeForecastLookup(model, plan, ui.weather)
                }
            val bands =
                remember(timeline, lookup, departure, zone, ui.weather) {
                    if (ui.weather.isEmpty()) emptyList() else phoneHikeWeatherBands(timeline, lookup, departure, zone)
                }
            val match = arrival?.let { lookup.at(selected, it, zone) }
            val sample = match?.sample
            val forecast = match?.outlook
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { scheduleOpen = true }, modifier = Modifier.testTag("hike-departure")) {
                    Text(stringResource(R.string.hike_planned_departure, plan.startTime ?: "—"))
                }
                Text(
                    stringResource(R.string.hike_planned_arrival, finishLabel),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(stringResource(R.string.hike_planned_timeline), style = MaterialTheme.typography.labelSmall)
            PhoneHikeGraph(
                PhoneHikeGraphInputs(model, plan, timeline, selectedSeconds, current, departure, zone, metric, bands),
                controller::inspectPlannedTime,
            )
            Text(
                listOfNotNull(
                    arrival?.atZone(zone)?.format(timeFormat),
                    hikeDistance(selected.distanceMeters, metric),
                    selected.elevationMeters?.let { hikeElevation(it, metric) },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("hike-inspection-summary"),
            )
            val weatherDetails =
                listOfNotNull(
                    forecast?.weatherCode?.let { weatherConditionText(LocalResources.current, it) },
                    forecast?.temperatureCelsius?.let { "${it.roundToInt()}°C" },
                    forecast?.precipitationProbabilityPercent?.let {
                        stringResource(R.string.hike_weather_rain_probability, it.roundToInt())
                    },
                    forecast?.windGustKilometersPerHour?.let {
                        stringResource(R.string.hike_weather_gusts, it.roundToInt())
                    },
                )
            if (weatherDetails.isNotEmpty()) {
                Text(
                    weatherDetails.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("hike-inspection-weather"),
                )
            } else {
                Text(
                    stringResource(
                        when {
                            departure == null -> R.string.hike_time_unavailable
                            ui.weather.isEmpty() -> R.string.hike_weather_not_loaded
                            else -> R.string.hike_forecast_graph_unavailable
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (ui.weather.isNotEmpty()) {
                PhoneHikeWeatherLegend(bands)
                Text(stringResource(R.string.hike_forecast_approximate), style = MaterialTheme.typography.labelSmall)
                if (ui.weather.any { it.stale }) {
                    Text(stringResource(R.string.hike_forecast_some_stale), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = controller::beginCheckpoint) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Text(stringResource(R.string.hike_checkpoint))
                }
                if (plan.inspecting) {
                    IconButton(onClick = controller::returnToLive, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MyLocation, stringResource(R.string.hike_return_live))
                    }
                }
            }
            if (ui.data.previewSelected) {
                OutlinedButton(
                    onClick = controller::requestUseForToday,
                    enabled = ui.ready,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.hike_use_today))
                }
            }
            TextButton(onClick = controller::pickCheckpointOnMap) { Text(stringResource(R.string.hike_checkpoint_map)) }
            if (ui.pickingCheckpoint) {
                Text(stringResource(R.string.hike_pick_map_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = controller::cancelCheckpoint,
                ) { Text(stringResource(R.string.common_action_cancel)) }
            }
            val checkpoints = model.orderedCheckpoints(plan)
            val next =
                current?.let { position ->
                    checkpoints.firstOrNull {
                        it.second.distanceMeters >
                            position.distanceMeters + 1.0
                    }
                }
            if (next != null) {
                Text(
                    stringResource(
                        R.string.hike_next_checkpoint,
                        next.first.name,
                        hikeDistance(
                            next.second.distanceMeters - current.distanceMeters,
                            metric,
                        ),
                        hikeDuration(
                            next.second.elapsedSeconds - current.elapsedSeconds,
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (checkpoints.isNotEmpty()) {
                Text(
                    stringResource(R.string.hike_live_estimate_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val comparison =
                checkpoints
                    .filter {
                        it.first.id in selectedCheckpoints
                    }.sortedBy { it.second.distanceMeters }
            if (comparison.size == 2) {
                val a = comparison[0]
                val b = comparison[1]
                Text(
                    stringResource(
                        R.string.hike_checkpoint_leg,
                        a.first.name,
                        b.first.name,
                        hikeDistance(
                            b.second.distanceMeters - a.second.distanceMeters,
                            metric,
                        ),
                        hikeDuration(
                            b.second.elapsedSeconds - a.second.elapsedSeconds,
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            checkpoints.forEachIndexed { index, (checkpoint, point) ->
                val previous = checkpoints.getOrNull(index - 1)?.second ?: model.atDistance(0.0)!!
                val eta =
                    phoneHikeArrivalInstant(
                        model,
                        plan,
                        point,
                        zone,
                        current.takeIf {
                            live
                        },
                        Instant.ofEpochMilli(nowMillis).takeIf { live },
                    )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checkpoint.id in selectedCheckpoints, { checked ->
                        selectedCheckpoints =
                            if (checked) {
                                (selectedCheckpoints + checkpoint.id).takeLast(2)
                            } else {
                                selectedCheckpoints -
                                    checkpoint.id
                            }
                    })
                    Column(Modifier.weight(1f).clickable { controller.inspect(point.distanceMeters) }) {
                        Text(checkpoint.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                hikeDistance(point.distanceMeters - previous.distanceMeters, metric),
                                hikeDuration(
                                    point.elapsedSeconds - previous.elapsedSeconds,
                                ),
                                eta?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")),
                                checkpoint.stopMinutes
                                    .takeIf {
                                        it >
                                            0
                                    }?.let { stringResource(R.string.hike_stop_minutes, it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = {
                        controller.editCheckpoint(checkpoint)
                    }) { Icon(Icons.Default.Edit, stringResource(R.string.hike_edit_checkpoint)) }
                    IconButton(onClick = {
                        controller.deleteCheckpoint(checkpoint.id)
                    }) { Icon(Icons.Default.Delete, stringResource(R.string.hike_delete_checkpoint)) }
                }
            }
            Text(
                stringResource(R.string.hike_times_estimated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    controller.loadWeather()
                }, enabled = !ui.weatherLoading) { Text(stringResource(R.string.hike_load_weather)) }
                if (ui.weatherLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (ui.weather.isNotEmpty()) {
                    TextButton(onClick = {
                        controller.loadWeather(true)
                    }, enabled = !ui.weatherLoading) { Text(stringResource(R.string.hike_refresh)) }
                }
            }
            if (ui.weather.isNotEmpty()) {
                Text(stringResource(R.string.hike_weather_sampled), style = MaterialTheme.typography.bodySmall)
                if (forecast != null && sample != null) {
                    Text(
                        weatherConditionText(LocalResources.current, forecast.weatherCode),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.hike_weather_metrics,
                            forecast.temperatureCelsius?.roundToInt()?.toString() ?: "—",
                            forecast.precipitationProbabilityPercent?.roundToInt()?.toString() ?: "—",
                            forecast.windGustKilometersPerHour?.roundToInt()?.toString() ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            if (sample.stale) R.string.hike_forecast_stale else R.string.hike_forecast_updated,
                            Instant
                                .ofEpochMilli(
                                    sample.forecast.fetchedAtEpochMillis,
                                ).atZone(zone)
                                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        stringResource(
                            if (arrival ==
                                null
                            ) {
                                R.string.hike_set_departure_weather
                            } else {
                                R.string.hike_weather_time_unavailable
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { runCatching { uriHandler.openUri("https://open-meteo.com/") } }) {
                    Text(stringResource(R.string.briefing_weather_data_by_open_meteo_com))
                }
            }
            if (!ui.data.previewSelected) {
                PhoneGuidancePanel()
                watchContent()
            }
        } else if (!ui.loading) {
            Text(stringResource(R.string.hike_choose_route), style = MaterialTheme.typography.bodyMedium)
            if (!ui.data.previewSelected) {
                PhoneGuidancePanel()
                watchContent()
            }
        }
    }
    if (scheduleOpen && plan != null) {
        PhoneHikeScheduleDialog(
            plan,
            ui.weather
                .firstOrNull()
                ?.forecast
                ?.timeZoneId,
            controller::saveSchedule,
        ) { scheduleOpen = false }
    }
    ui.checkpointDraft?.let { draft ->
        PhoneHikeCheckpointDialog(draft, controller::saveCheckpoint, controller::cancelCheckpoint)
    }
    if (ui.pendingTodayId != null) {
        AlertDialog(
            onDismissRequest = controller::dismissPromotion,
            title = { Text(stringResource(R.string.hike_replace_today_title)) },
            text = { Text(stringResource(R.string.hike_replace_today_message)) },
            confirmButton = {
                TextButton(
                    onClick = { controller.confirmPromotion() },
                ) { Text(stringResource(R.string.hike_replace_today)) }
            },
            dismissButton = {
                TextButton(
                    onClick = controller::dismissPromotion,
                ) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
private fun PhoneHikeScheduleDialog(
    plan: PhoneHikePlan,
    routeZone: String?,
    onSave: (String, String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(plan.date ?: LocalDate.now().toString()) }
    var time by rememberSaveable { mutableStateOf(plan.startTime ?: "08:00") }
    var zone by rememberSaveable { mutableStateOf(plan.zoneId.orEmpty()) }
    val valid =
        runCatching {
            LocalDate.parse(date)
            LocalTime.parse(time)
            if (zone.isNotBlank()) ZoneId.of(zone)
        }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hike_departure)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    date,
                    { date = it },
                    label = { Text(stringResource(R.string.hike_date)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    time,
                    { time = it },
                    label = { Text(stringResource(R.string.hike_start_time)) },
                    singleLine = true,
                )
                OutlinedTextField(zone, { zone = it }, label = {
                    Text(
                        stringResource(R.string.hike_time_zone),
                    )
                }, placeholder = {
                    Text(
                        routeZone ?: stringResource(R.string.hike_zone_auto),
                    )
                }, singleLine = true)
                Text(stringResource(R.string.hike_zone_help), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(date, time, zone.takeIf { it.isNotBlank() })
                onDismiss()
            }, enabled = valid) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

@Composable
private fun PhoneHikeCheckpointDialog(
    draft: PhoneHikeCheckpointDraft,
    onSave: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(draft.placeId) { mutableStateOf(draft.name) }
    var stop by rememberSaveable(draft.placeId, draft.checkpointId) { mutableStateOf(draft.stopMinutes.toString()) }
    val stopValue = stop.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (draft.checkpointId ==
                        null
                    ) {
                        R.string.hike_add_checkpoint
                    } else {
                        R.string.hike_edit_checkpoint
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, {
                    name = it
                }, label = { Text(stringResource(R.string.hike_checkpoint_name)) }, singleLine = true)
                OutlinedTextField(
                    stop,
                    { stop = it },
                    label = { Text(stringResource(R.string.hike_stop_duration)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.hike_checkpoint_route_time), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, stopValue ?: 0) },
                enabled =
                    stopValue != null && stopValue in 0..1_440,
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

@Composable
internal fun PhoneHikeCheckpointLibrary(
    controller: PhoneHikeViewModel,
    onOpenHike: () -> Unit = {},
) {
    val ui by controller.state.collectAsState()
    val plans = (listOfNotNull(ui.data.todayPlan) + ui.data.plans).groupBy { it.routeId }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.hike_checkpoints), style = MaterialTheme.typography.titleMedium)
        if (plans.values.flatten().all { it.checkpoints.isEmpty() }) Text(stringResource(R.string.hike_no_checkpoints))
        plans.forEach { (routeId, entries) ->
            val checkpoints = entries.flatMap { it.checkpoints }.distinctBy { it.id }
            if (checkpoints.isNotEmpty()) {
                Text(
                    ui.routeTitles[routeId].orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            checkpoints.forEach { checkpoint ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        controller.inspectCheckpoint(routeId, checkpoint)
                        onOpenHike()
                    }, modifier = Modifier.weight(1f)) { Text(checkpoint.name) }
                    if (ui.model != null) {
                        TextButton(onClick = {
                            controller.checkpointAt(
                                com.glancemap.trailcore.geo
                                    .GeoPoint(checkpoint.latitude, checkpoint.longitude),
                                checkpoint.name,
                                checkpoint.placeId,
                                requireOnRoute = false,
                            )
                            onOpenHike()
                        }) { Text(stringResource(R.string.hike_reuse_checkpoint)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun hikeDistance(
    meters: Double,
    metric: Boolean,
): String =
    stringResource(
        if (metric) R.string.hike_distance_km else R.string.hike_distance_mi,
        meters / if (metric) 1_000.0 else 1_609.344,
    )

@Composable
private fun hikeElevation(
    meters: Double,
    metric: Boolean,
): String =
    stringResource(
        if (metric) R.string.hike_elevation_m else R.string.hike_elevation_ft,
        (
            meters *
                if (metric) 1.0 else 3.28084
        ).roundToInt(),
    )

@Composable
private fun hikeDuration(seconds: Double): String {
    val minutes = (seconds.coerceAtLeast(0.0) / 60.0).roundToInt()
    return if (minutes <
        60
    ) {
        stringResource(R.string.hike_duration_min, minutes)
    } else {
        stringResource(
            R.string.hike_duration_hours,
            minutes / 60,
            minutes % 60,
        )
    }
}
