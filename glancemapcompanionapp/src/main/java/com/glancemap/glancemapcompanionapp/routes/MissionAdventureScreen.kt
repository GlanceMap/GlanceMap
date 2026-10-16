@file:Suppress("FunctionNaming")

package com.glancemap.glancemapcompanionapp.routes

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.roundToInt

@Suppress(
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
) // This screen wires the adventure list, day editor, and existing route dialogs.
@Composable
fun MissionPlanScreen(
    uiState: MissionPlanUiState,
    routes: List<RouteLibraryRoute>,
    onBack: () -> Unit,
    onSaveAdventure: (String?, MissionAdventureUpdate) -> Unit,
    onSelectAdventure: (String) -> Unit,
    onAddBlankDay: (Boolean) -> Unit,
    onAssignRoute: (String, String) -> Unit,
    onImportRoute: (String, Uri) -> Unit,
    onCreateRoute: (String) -> Unit,
    onSetToday: (MissionPlanDayUi) -> Unit,
    onUpdateDay: (String, MissionPlanDayUpdate) -> Unit,
    onChangeDay: (String, (MissionPlanDay) -> MissionPlanDayUpdate) -> Unit,
    onMoveDay: (String, Int) -> Unit,
    onRemoveDay: (String) -> Unit,
    onLoadDayWeather: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
    onLoadStageSource: suspend (String, String) -> MissionRouteStageSource,
    onImportStageRoute: suspend (Uri) -> String,
    onApplyStages: suspend (String, String, List<String>, List<Double>) -> Unit,
    stagePlannerRequested: Boolean = false,
    stagePlannerRouteId: String? = null,
    onStagePlannerRequestConsumed: () -> Unit = {},
) {
    var showAdventures by rememberSaveable { mutableStateOf(uiState.selectedAdventureId == null) }
    var expandedDayId by rememberSaveable(uiState.selectedAdventureId) { mutableStateOf<String?>(null) }
    var editAdventure by remember { mutableStateOf(false) }
    var adventureToEdit by remember { mutableStateOf<MissionAdventure?>(null) }
    var routeDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var importDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var timelineDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var showStagePlanner by rememberSaveable { mutableStateOf(false) }
    var stageFocusDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var stageInitialRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(stagePlannerRequested) {
        if (stagePlannerRequested) {
            stageInitialRouteId = stagePlannerRouteId
            stageFocusDayId = null
            showStagePlanner = true
            onStagePlannerRequestConsumed()
        }
    }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val dayId = importDayId
            importDayId = null
            if (uri != null && dayId != null) onImportRoute(dayId, uri)
        }
    val adventure = uiState.selectedAdventure
    val listVisible = showAdventures || adventure == null
    BackHandler(enabled = !listVisible && !editAdventure) { showAdventures = true }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = { if (listVisible) onBack() else showAdventures = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.common_back_to_home_content_description),
                )
            }
            Text(
                if (listVisible) stringResource(R.string.adventure_list) else requireNotNull(adventure).name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = {
                adventureToEdit = if (listVisible) null else adventure
                editAdventure = true
            }) {
                Text(stringResource(if (listVisible) R.string.adventure_new else R.string.adventure_edit))
            }
        }
        uiState.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.mission_plan_try_again)) }
        }
        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else if (listVisible) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.adventures.isEmpty()) {
                    item {
                        Text(stringResource(R.string.adventure_empty), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            adventureToEdit = null
                            editAdventure = true
                        }) { Text(stringResource(R.string.adventure_new)) }
                    }
                }
                items(uiState.adventures, key = MissionAdventure::id) { item ->
                    Card(onClick = {
                        onSelectAdventure(item.id)
                        showAdventures = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(item.adventureSchedule(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(
                        requireNotNull(adventure).adventureSchedule(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    adventure.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = {
                        stageInitialRouteId = null
                        stageFocusDayId = null
                        showStagePlanner = true
                    }) { Text(stringResource(R.string.stage_plan_across_days)) }
                }
                itemsIndexed(uiState.planDays, key = { _, day -> day.id }) { index, day ->
                    val routeDay = uiState.days.firstOrNull { it.day.id == day.id }
                    MissionCompactDayCard(
                        day = day,
                        routeDay = routeDay,
                        people = adventure.people,
                        weather = uiState.weatherFor(day),
                        weatherLocation = uiState.restLocations[day.id],
                        expanded = expandedDayId == day.id,
                        selected = day.id == uiState.selectedDayId,
                        onExpand = { expandedDayId = if (expandedDayId == day.id) null else day.id },
                        onUpdate = { onChangeDay(day.id, it) },
                        onChooseRoute = { routeDayId = day.id },
                        onDetails = { detailsDayId = day.id },
                        onChangeArrival = {
                            stageInitialRouteId = day.routeId
                            stageFocusDayId = day.id
                            showStagePlanner = true
                        },
                        onLoadWeather = { force -> onLoadDayWeather(day.id, force) },
                        onSetToday = { routeDay?.let(onSetToday) },
                        onJourney = { timelineDayId = day.id },
                        onProfile = { profileDayId = day.id },
                        onMove = { onMoveDay(day.id, it) },
                        index = index,
                        dayCount = uiState.planDays.size,
                        onRemove = { removeDayId = day.id },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onAddBlankDay(false) },
                        ) { Text(stringResource(R.string.adventure_add_hike)) }
                        TextButton(
                            onClick = { onAddBlankDay(true) },
                        ) { Text(stringResource(R.string.adventure_add_rest)) }
                    }
                }
                item { MissionAdventureOverview(uiState.adventureTotals()) }
            }
        }
    }
    if (showStagePlanner) {
        MissionStagePlanner(
            adventures = uiState.adventures,
            routes = routes,
            initialAdventureId = uiState.selectedAdventureId,
            initialRouteId = stageInitialRouteId,
            focusDayId = stageFocusDayId,
            onLoad = onLoadStageSource,
            onImport = onImportStageRoute,
            onApply = onApplyStages,
            onDismiss = { showStagePlanner = false },
            onApplied = {
                showStagePlanner = false
                showAdventures = false
            },
        )
    }
    if (editAdventure) {
        MissionAdventureEditor(
            adventure = adventureToEdit,
            existingDays = if (adventureToEdit == null) 0 else uiState.planDays.size,
            onDismiss = { editAdventure = false },
            onSave = { update ->
                onSaveAdventure(adventureToEdit?.id, update)
                showAdventures = false
                editAdventure = false
            },
        )
    }
    routeDayId?.let { id ->
        MissionRoutePicker(
            routes = routes,
            onPick = {
                onAssignRoute(id, it)
                routeDayId = null
            },
            onImport = {
                importDayId = id
                routeDayId = null
                importLauncher.launch(arrayOf("*/*"))
            },
            onCreate = {
                routeDayId = null
                onCreateRoute(id)
            },
            onDismiss = { routeDayId = null },
        )
    }
    uiState.planDays.firstOrNull { it.id == detailsDayId }?.let { day ->
        MissionDayDetailsEditor(
            day = day,
            routeDay = uiState.days.firstOrNull { it.day.id == day.id },
            inheritedLocation = uiState.restLocations[day.id],
            onDismiss = { detailsDayId = null },
            onSave = {
                onUpdateDay(day.id, it)
                detailsDayId = null
            },
        )
    }
    uiState.days.firstOrNull { it.day.id == timelineDayId }?.let {
        MissionDayTimelineDialog(it) { timelineDayId = null }
    }
    uiState.days.firstOrNull { it.day.id == profileDayId }?.let {
        MissionDayPlanProfileDialog(
            it,
            uiState.weatherFor(it.day),
        ) { profileDayId = null }
    }
    removeDayId?.let { id ->
        AlertDialog(
            onDismissRequest = { removeDayId = null },
            title = { Text(stringResource(R.string.mission_plan_remove)) },
            text = { Text(stringResource(R.string.adventure_remove_day_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveDay(id)
                    removeDayId = null
                }) { Text(stringResource(R.string.mission_plan_remove)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { removeDayId = null },
                ) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
private fun MissionAdventure.adventureSchedule(): String =
    listOfNotNull(
        startDate?.missionPlanDateLabel(),
        endDate?.missionPlanDateLabel(),
        stringResource(R.string.adventure_people_count, people),
    ).joinToString(" · ")

@Composable
private fun MissionRoutePicker(
    routes: List<RouteLibraryRoute>,
    onPick: (String) -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adventure_choose_route)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport) { Text(stringResource(R.string.adventure_import_gpx)) }
                    OutlinedButton(onClick = onCreate) { Text(stringResource(R.string.adventure_create_route)) }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(routes, key = RouteLibraryRoute::id) { route ->
                        TextButton(
                            onClick = { onPick(route.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(route.displayName) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

@Composable
private fun MissionAdventureOverview(totals: MissionAdventureTotals) {
    var showMeals by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.adventure_overview), style = MaterialTheme.typography.titleSmall)
            Text(
                "${totals.distanceMeters.toMissionPlanDistance()} · ↑ ${totals.ascentMeters.roundToInt()} m · " +
                    "↓ ${totals.descentMeters.roundToInt()} m",
            )
            Text(
                stringResource(
                    R.string.adventure_day_counts,
                    totals.hikingDays,
                    totals.restDays,
                    totals.nights,
                ) + " · " + totals.walkingSeconds.toMissionPlanDuration(),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { showMeals = !showMeals },
            ) { Text(stringResource(R.string.adventure_meals_to_carry, totals.packedMeals)) }
            if (showMeals) {
                Text(
                    stringResource(
                        R.string.adventure_meal_totals,
                        totals.breakfasts,
                        totals.lunches,
                        totals.dinners,
                        totals.providedMeals,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            MissionAdventureOutstanding(totals)
        }
    }
}

@Composable
private fun MissionAdventureOutstanding(totals: MissionAdventureTotals) {
    if (totals.missingRoutes > 0) {
        Text(
            stringResource(
                R.string.adventure_incomplete_distance,
                totals.missingRoutes,
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (totals.unbookedNights > 0) {
        Text(
            stringResource(
                R.string.adventure_nights_to_book,
                totals.unbookedNights,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (totals.unplannedItems > 0) {
        Text(
            stringResource(
                R.string.adventure_unplanned_items,
                totals.unplannedItems,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
