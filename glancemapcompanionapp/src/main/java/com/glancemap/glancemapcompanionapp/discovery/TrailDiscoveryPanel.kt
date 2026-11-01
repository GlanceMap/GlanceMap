@file:Suppress("MatchingDeclarationName") // Compose panels and their action model share one focused file.

package com.glancemap.glancemapcompanionapp.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.delay

internal data class TrailDiscoveryActions(
    val onSearchArea: () -> Unit,
    val onSearchPlace: () -> Unit,
    val onSources: () -> Unit,
    val onOpen: (PublishedTrail) -> Unit,
    val map: TrailDiscoverySearchActions = TrailDiscoverySearchActions(),
    val onMapSelectionChanged: (PublishedTrail, Boolean) -> Unit = { _, _ -> },
)

internal data class PublishedTrailDetailActions(
    val onBack: () -> Unit,
    val onSave: (PublishedTrail) -> Unit,
    val onRetry: () -> Unit = {},
)

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Named callbacks keep map integration explicit.
internal fun TrailDiscoveryContent(
    viewModel: TrailDiscoveryViewModel,
    query: TrailDiscoveryQuery,
    onSearchPlace: () -> Unit,
    onPreview: (PublishedTrail?) -> Unit,
    onLibrary: () -> Unit,
    mapActions: TrailDiscoverySearchActions = TrailDiscoverySearchActions(),
) {
    val state by viewModel.controller.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val save by viewModel.saveState.collectAsState()
    val offline by viewModel.offlineState.collectAsState()
    val onlineDetail by viewModel.onlineDetail.collectAsState()
    val mapSelection by viewModel.mapSelection.state.collectAsState()
    var sourcesOpen by remember { mutableStateOf(false) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected =
        if (downloadsOpen) {
            offline.selectedTrail?.takeIf { it.id == selectedId }
        } else {
            selectedOnlineTrail(selectedId, state.reply.trails, mapSelection.requested, onlineDetail.trail)
        }
    LaunchedEffect(selected) { onPreview(selected) }
    LaunchedEffect(query.center, state.loading) {
        if (state.loading) {
            delay(600)
            viewModel.controller.cancelIfMoved(query.center)
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshOffline() }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.controller.cancel()
            viewModel.closeTrail()
            mapActions.onDismiss()
            onPreview(null)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.discovery_title), Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onLibrary) { Text(stringResource(R.string.discovery_library)) }
            TextButton(onClick = {
                selectedId = null
                viewModel.closeTrail()
                sourcesOpen = false
                downloadsOpen = !downloadsOpen
                if (downloadsOpen) {
                    viewModel.controller.cancel()
                    mapActions.onCancelPoint()
                    viewModel.refreshOffline()
                }
            }) {
                Text(stringResource(if (downloadsOpen) R.string.discovery_search_tab else R.string.discovery_downloads))
            }
        }
        Column(Modifier.weight(1f)) {
            when {
                sourcesOpen -> RouteSourcesPanel(settings, viewModel::saveSettings) { sourcesOpen = false }
                selected != null -> {
                    if (downloadsOpen) Text(stringResource(R.string.discovery_offline_reading))
                    offline.message?.takeIf { downloadsOpen }?.let { Text(it) }
                    PublishedTrailDetails(
                        selected,
                        save,
                        PublishedTrailDetailActions(
                            onBack = {
                                selectedId = null
                                viewModel.closeTrail()
                            },
                            onSave = viewModel::saveTrail,
                            onRetry = { viewModel.openTrail(selected) },
                        ),
                        detail = if (downloadsOpen) OnlineTrailDetailState() else onlineDetail,
                    )
                }
                downloadsOpen ->
                    OfflineTrailsPanel(offline) { record ->
                        selectedId = record.trail.id
                        viewModel.openOffline(record)
                    }
                else ->
                    TrailDiscoveryPanel(
                        state.copy(enabled = settings.enabled),
                        TrailDiscoveryActions(
                            onSearchArea = { viewModel.controller.submit(query, settings.enabled) },
                            onSearchPlace = onSearchPlace,
                            onSources = { sourcesOpen = true },
                            onOpen = {
                                selectedId = it.id
                                val loaded = mapSelection.requested[it.id]?.takeIf(PublishedTrail::canSave) ?: it
                                viewModel.openTrail(loaded)
                            },
                            map = mapActions,
                            onMapSelectionChanged = viewModel.mapSelection::setVisible,
                        ),
                        mapSelection = mapSelection,
                    )
            }
        }
    }
}

private fun selectedOnlineTrail(
    id: String?,
    trails: List<PublishedTrail>,
    requested: Map<String, PublishedTrail>,
    detail: PublishedTrail?,
): PublishedTrail? {
    val listing = trails.firstOrNull { it.id == id } ?: id?.let(requested::get) ?: return null
    return detail?.takeIf { it.id == id } ?: listing
}

@Composable
@Suppress("FunctionNaming") // Stateless panel supports fixture-based Compose tests.
internal fun TrailDiscoveryPanel(
    state: TrailDiscoveryUiState,
    actions: TrailDiscoveryActions,
    mapSelection: TrailMapSelectionState = TrailMapSelectionState(),
) {
    val results = state.reply.trails.filter { trail -> trail.sources.any { it.provider in state.enabled } }
    val resultIds = results.mapTo(mutableSetOf()) { it.id }
    val selectedElsewhere = mapSelection.requested.values.filterNot { it.id in resultIds }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { TrailDiscoverySearchControls(actions) }
        item {
            TextButton(onClick = actions.onSources) { Text(stringResource(R.string.discovery_source_settings)) }
            Text(stringResource(R.string.discovery_radius), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.discovery_map_selection_hint), style = MaterialTheme.typography.bodySmall)
            TrailDiscoveryStatus(state)
        }
        items(state.reply.statuses.filter { it.outcome != TrailSourceOutcome.SUCCESS }) { status ->
            Text(
                "${status.label}: ${status.reason ?: trailDiscoveryFailureMessage(status.failure)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (selectedElsewhere.isNotEmpty()) {
            item { Text(stringResource(R.string.discovery_selected_for_map)) }
            items(selectedElsewhere, key = { "selected/${it.id}" }) { trail ->
                TrailDiscoveryCard(trail, { actions.onOpen(trail) }, mapSelection, actions.onMapSelectionChanged)
            }
        }
        items(results, key = { "result/${it.id}" }) { trail ->
            TrailDiscoveryCard(trail, { actions.onOpen(trail) }, mapSelection, actions.onMapSelectionChanged)
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Search-origin controls stay usable in the split map panel.
private fun TrailDiscoverySearchControls(actions: TrailDiscoveryActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions.map.onNearMe,
                enabled = !actions.map.waitingForLocation,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.discovery_near_me))
            }
            OutlinedButton(onClick = actions.map.onChoosePoint, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.discovery_choose_point))
            }
        }
        if (actions.map.waitingForLocation) {
            Text(stringResource(R.string.discovery_location_waiting))
            TextButton(onClick = actions.map.onCancelPoint) { Text(stringResource(R.string.common_action_cancel)) }
        }
        if (actions.map.pickingPoint) {
            Text(stringResource(R.string.discovery_pick_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.map.onUseMapCenter) { Text(stringResource(R.string.discovery_use_center)) }
                TextButton(onClick = actions.map.onCancelPoint) { Text(stringResource(R.string.common_action_cancel)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onSearchArea, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.discovery_search_area))
                }
                OutlinedButton(onClick = actions.onSearchPlace, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.discovery_search_place))
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // The same attributed card serves online results and offline snapshots.
private fun TrailDiscoveryCard(
    trail: PublishedTrail,
    onOpen: () -> Unit,
    mapSelection: TrailMapSelectionState? = null,
    onMapSelectionChanged: (PublishedTrail, Boolean) -> Unit = { _, _ -> },
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trail.title, style = MaterialTheme.typography.titleMedium)
            Text(
                trail.sources
                    .map { it.provider.label }
                    .distinct()
                    .joinToString(" · "),
            )
            trail.distanceMeters?.let { Text(stringResource(R.string.discovery_distance, it / 1000.0)) }
            trail.sources.forEach { Text(it.attribution, style = MaterialTheme.typography.labelSmall) }
            if (mapSelection != null) {
                val label = stringResource(R.string.discovery_show_route_on_map, trail.title)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = trail.id in mapSelection.requested,
                        onCheckedChange = { onMapSelectionChanged(trail, it) },
                        enabled =
                            trail.id in mapSelection.requested ||
                                trail.canSave ||
                                trail.sources.any { it.provider == TrailProvider.WAYMARKED_TRAILS },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                    Text(stringResource(R.string.discovery_show_on_map))
                }
                if (trail.id in mapSelection.loading) Text(stringResource(R.string.discovery_map_loading))
                if (trail.id in mapSelection.failed) Text(stringResource(R.string.discovery_map_failed))
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // This page only reads local metadata and Library geometry.
internal fun OfflineTrailsPanel(state: OfflineTrailsUiState, onOpen: (OfflinePublishedTrail) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.discovery_downloads))
            Text(stringResource(R.string.discovery_offline_scope))
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it) }
            if (!state.loading && state.records.isEmpty()) Text(stringResource(R.string.discovery_offline_empty))
        }
        items(state.records, key = { it.trail.id }) { record ->
            TrailDiscoveryCard(record.trail, { onOpen(record) })
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose helper follows the panel naming convention.
private fun TrailDiscoveryStatus(state: TrailDiscoveryUiState) {
    when {
        state.loading -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.discovery_loading))
        }
        state.enabled.isEmpty() -> Text(stringResource(R.string.discovery_no_sources))
        state.reply.statuses.any { it.outcome != TrailSourceOutcome.SUCCESS } -> {
            val message =
                when {
                    state.reply.trails.isNotEmpty() -> R.string.discovery_partial
                    state.reply.statuses.any { it.outcome == TrailSourceOutcome.SUCCESS } ->
                        R.string.discovery_partial_empty
                    else -> R.string.discovery_unavailable
                }
            Text(stringResource(message))
        }
        state.searched && state.reply.trails.isEmpty() -> Text(stringResource(R.string.discovery_empty))
        !state.searched -> Text(stringResource(R.string.discovery_intro))
    }
}

@Composable
private fun trailDiscoveryFailureMessage(failure: TrailDiscoveryFailure?): String =
    stringResource(
        when (failure) {
            TrailDiscoveryFailure.TIMED_OUT -> R.string.discovery_source_timeout
            TrailDiscoveryFailure.RATE_LIMITED -> R.string.discovery_source_rate_limited
            TrailDiscoveryFailure.SERVICE_UNAVAILABLE -> R.string.discovery_source_unavailable
            TrailDiscoveryFailure.RESPONSE_TOO_LARGE -> R.string.discovery_source_too_large
            null -> R.string.discovery_source_failed
        },
    )
