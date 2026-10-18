package com.glancemap.glancemapcompanionapp.hike

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
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
            PhoneHikeGraph(model, plan, selected, current, controller::inspect)
            val arrival =
                phoneHikeArrivalInstant(
                    model,
                    plan,
                    selected,
                    zone,
                    current.takeIf {
                        live
                    },
                    Instant.ofEpochMilli(nowMillis).takeIf { live },
                )
            val sample =
                ui.weather.minByOrNull { sample ->
                    abs(
                        (model.position(sample.segmentIndex, sample.fraction)?.distanceMeters ?: Double.MAX_VALUE) -
                            selected.distanceMeters,
                    )
                }
            val forecast = arrival?.let { sample?.outlookAt(it, zone) }
            Text(
                listOfNotNull(
                    hikeDistance(selected.distanceMeters, metric),
                    selected.elevationMeters?.let { hikeElevation(it, metric) },
                    arrival?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")),
                    forecast?.temperatureCelsius?.let { "${it.roundToInt()}°C" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("hike-inspection-summary"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = controller::beginCheckpoint) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Text(stringResource(R.string.hike_checkpoint))
                }
                TextButton(onClick = { scheduleOpen = true }) {
                    Text(
                        plan.startTime ?: stringResource(R.string.hike_departure),
                    )
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
private fun PhoneHikeGraph(
    model: PhoneHikeRouteModel,
    plan: PhoneHikePlan,
    selected: PhoneHikePosition,
    current: PhoneHikePosition?,
    onInspect: (Double) -> Unit,
) {
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val latestInspect by rememberUpdatedState(onInspect)
    var programmatic by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val cursorColor = Color(0xFF9333EA)
    val profileDescription = stringResource(R.string.hike_profile_description)
    val checkpoints = remember(model, plan.checkpoints) { model.orderedCheckpoints(plan) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(106.dp).testTag("hike-profile").semantics {
            contentDescription = profileDescription
            progressBarRangeInfo = ProgressBarRangeInfo(selected.distanceMeters.toFloat(), 0f..model.totalDistanceMeters.toFloat())
            setProgress { distance ->
                latestInspect(distance.toDouble().coerceIn(0.0, model.totalDistanceMeters))
                true
            }
        },
    ) {
        val viewport = with(LocalDensity.current) { maxWidth.toPx() }
        val contentWidth = maxWidth * 3f
        val graphWidth = viewport * 2f
        LaunchedEffect(model, scroll, graphWidth) {
            var userScroll = false
            snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, dragged) }.distinctUntilChanged().collect { (value, scrolling, dragging) ->
                if (dragging) userScroll = true
                if (userScroll &&
                    !programmatic
                ) {
                    latestInspect(
                        (value / graphWidth * model.totalDistanceMeters).coerceIn(0.0, model.totalDistanceMeters),
                    )
                }
                if (!scrolling) userScroll = false
            }
        }
        LaunchedEffect(model, selected.distanceMeters, scroll.isScrollInProgress, graphWidth) {
            if (!scroll.isScrollInProgress) {
                programmatic = true
                try {
                    scroll.scrollTo(
                        (
                            selected.distanceMeters /
                                model.totalDistanceMeters.coerceAtLeast(
                                    1.0,
                                ) * graphWidth
                        ).roundToInt(),
                    )
                } finally {
                    programmatic = false
                }
            }
        }
        Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Canvas(Modifier.width(contentWidth).fillMaxHeight()) {
                val finite = model.graphPoints.mapNotNull { it.elevationMeters?.takeIf(Double::isFinite) }
                val minimum = finite.minOrNull() ?: 0.0
                val span = ((finite.maxOrNull() ?: minimum) - minimum).coerceAtLeast(1.0)

                fun x(distance: Double): Float = viewport / 2f + graphWidth * (distance / model.totalDistanceMeters.coerceAtLeast(1.0)).toFloat()
                val path = Path()
                var drawing = false
                model.graphPoints.forEach { point ->
                    val elevation = point.elevationMeters
                    if (elevation == null || !elevation.isFinite()) {
                        drawing = false
                    } else {
                        val y =
                            8.dp.toPx() + (size.height - 20.dp.toPx()) * (1.0 - (elevation - minimum) / span).toFloat()
                        if (!drawing ||
                            point.startsNewSegment
                        ) {
                            path.moveTo(x(point.distanceMeters), y)
                        } else {
                            path.lineTo(x(point.distanceMeters), y)
                        }
                        drawing = true
                    }
                }
                drawPath(path, primary, style = Stroke(2.dp.toPx()))
                drawLine(
                    Color.Gray.copy(alpha = .5f),
                    Offset(viewport / 2f, size.height - 6.dp.toPx()),
                    Offset(
                        viewport / 2f + graphWidth,
                        size.height - 6.dp.toPx(),
                    ),
                )
                checkpoints.forEach { (_, point) ->
                    drawCircle(
                        primary,
                        4.dp.toPx(),
                        Offset(
                            x(point.distanceMeters),
                            size.height - 6.dp.toPx(),
                        ),
                    )
                }
                current?.let {
                    drawLine(
                        Color(0xFF0284C7),
                        Offset(x(it.distanceMeters), 0f),
                        Offset(x(it.distanceMeters), size.height),
                        2.dp.toPx(),
                    )
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawLine(cursorColor, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 2.dp.toPx())
        }
        if (model.graphPoints.none {
                it.elevationMeters != null
            }
        ) {
            Text(
                stringResource(R.string.hike_elevation_unavailable),
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(R.string.hike_profile_scroll_hint),
            Modifier.align(Alignment.TopStart),
            style = MaterialTheme.typography.labelSmall,
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
