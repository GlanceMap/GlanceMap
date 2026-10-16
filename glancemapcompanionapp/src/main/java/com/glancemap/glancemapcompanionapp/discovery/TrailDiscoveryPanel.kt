@file:Suppress("MatchingDeclarationName") // Compose panels and their action model share one focused file.

package com.glancemap.glancemapcompanionapp.discovery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
    val onSourceChanged: (TrailProvider, Boolean) -> Unit = { _, _ -> },
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
    var sourcesOpen by remember { mutableStateOf(false) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected =
        if (downloadsOpen) {
            offline.selectedTrail?.takeIf { it.id == selectedId }
        } else {
            state.reply.trails.firstOrNull { it.id == selectedId }
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
                    PublishedTrailDetails(selected, save, { selectedId = null }, viewModel::saveTrail)
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
                            onOpen = { selectedId = it.id },
                            map = mapActions,
                            onSourceChanged = { provider, enabled ->
                                val sources = if (enabled) settings.enabled + provider else settings.enabled - provider
                                viewModel.saveSettings(settings.copy(enabled = sources))
                            },
                        ),
                    )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Stateless panel supports fixture-based Compose tests.
internal fun TrailDiscoveryPanel(state: TrailDiscoveryUiState, actions: TrailDiscoveryActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { TrailDiscoverySearchControls(actions) }
        item { TrailSourceFilters(state.enabled, actions.onSourceChanged) }
        item {
            TextButton(onClick = actions.onSources) { Text(stringResource(R.string.discovery_source_settings)) }
            Text(stringResource(R.string.discovery_radius), style = MaterialTheme.typography.bodySmall)
            TrailDiscoveryStatus(state)
        }
        items(state.reply.statuses.filter { it.outcome != TrailSourceOutcome.SUCCESS }) { status ->
            Text(
                "${status.label}: ${status.reason ?: stringResource(R.string.discovery_source_failed)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(
            state.reply.trails.filter { trail -> trail.sources.any { it.provider in state.enabled } },
            key = PublishedTrail::id,
        ) { trail -> TrailDiscoveryCard(trail) { actions.onOpen(trail) } }
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
@Suppress("FunctionNaming") // Persistent source filters are visible before a search is submitted.
internal fun TrailSourceFilters(enabled: Set<TrailProvider>, onChanged: (TrailProvider, Boolean) -> Unit) {
    Column {
        Text(stringResource(R.string.discovery_sources))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrailProvider.entries.forEach { source ->
                val label = stringResource(R.string.discovery_search_source, source.label)
                FilterChip(
                    selected = source in enabled,
                    onClick = { onChanged(source, source !in enabled) },
                    label = { Text(source.label) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // The same attributed card serves online results and offline snapshots.
private fun TrailDiscoveryCard(trail: PublishedTrail, onOpen: () -> Unit) {
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
            TrailDiscoveryCard(record.trail) { onOpen(record) }
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
            Text(stringResource(R.string.discovery_partial))
        }
        state.searched && state.reply.trails.isEmpty() -> Text(stringResource(R.string.discovery_empty))
        !state.searched -> Text(stringResource(R.string.discovery_intro))
    }
}

@Composable
@Suppress("FunctionNaming") // Compose details entry point.
internal fun PublishedTrailDetails(
    trail: PublishedTrail,
    save: DiscoveredTrailSaveState,
    onBack: () -> Unit,
    onSave: (PublishedTrail) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.discovery_back_results)) }
            Text(trail.title, style = MaterialTheme.typography.titleLarge)
            Text(trail.activity)
            if (trail.description.isNotBlank()) Text(trail.description)
            trail.difficulty?.let { Text(it) }
            trail.distanceMeters?.let { Text(stringResource(R.string.discovery_distance, it / 1000.0)) }
            PublishedTrailMetrics(trail)
            Text(stringResource(if (trail.canSave) R.string.discovery_preview else R.string.discovery_no_geometry))
            if (trail.geometry.any { it.startsNewSegment }) Text(stringResource(R.string.discovery_segments))
            Button(
                onClick = { onSave(trail) },
                enabled = trail.sources.isNotEmpty() && !save.saving && trail.id !in save.savedTrailIds,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label =
                    if (trail.id in save.savedTrailIds) {
                        R.string.discovery_available_offline
                    } else {
                        R.string.discovery_download
                    }
                Text(stringResource(label))
            }
            val scope =
                if (trail.canSave) {
                    R.string.discovery_download_route_scope
                } else {
                    R.string.discovery_download_info_scope
                }
            Text(stringResource(scope))
            if (save.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            save.message?.let { Text(it) }
        }
        items(trail.sources) { source ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.provider.label, style = MaterialTheme.typography.titleSmall)
                source.publisherName?.let { Text(it) }
                Text(source.attribution)
                Text(source.license, style = MaterialTheme.typography.bodySmall)
                source.updatedAt?.let { Text(stringResource(R.string.discovery_updated, it)) }
                Text(source.providerTrailId, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { uriHandler.openUri(source.sourceUrl) }) {
                    Text(stringResource(R.string.discovery_original))
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose helper follows the panel naming convention.
private fun PublishedTrailMetrics(trail: PublishedTrail) {
    trail.profile?.let { profile ->
        Text(stringResource(R.string.discovery_duration, profile.estimatedDurationSeconds / 3600.0))
        if (trail.geometry.all { it.elevationMeters != null }) {
            Text(stringResource(R.string.discovery_elevation, profile.totalAscentMeters, profile.totalDescentMeters))
        }
    }
}
