package com.glancemap.glancemapcompanionapp.map

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.oam.OamDownloadCatalog

/** Phone offline bundle selector for map, POI, routing, and elevation data. */
@Composable
@Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
) // Compose screen functions keep dialog state and callbacks together.
internal fun PhoneOfflineBundleDialog(
    uiState: PhoneOfflineBundleUiState,
    onDismiss: () -> Unit,
    onStart: (PhoneOfflineBundleSelection) -> Unit,
    onCancel: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onRefreshSelected: () -> Unit,
    onToggleRefreshSelection: (String) -> Unit,
    onClearUpdateChecks: () -> Unit,
    initialDemSource: PhoneOfflineDemSource = PhoneOfflineDemSource.DEFAULT,
) {
    var query by remember { mutableStateOf("") }
    var selectedAreaId by remember { mutableStateOf<String?>(null) }
    var includeRouting by remember { mutableStateOf(true) }
    var includeDem by remember { mutableStateOf(true) }
    var includeRefugesInfo by remember { mutableStateOf(false) }
    var demSource by remember(initialDemSource) { mutableStateOf(initialDemSource) }
    val state = uiState.download
    val isDownloading = state is PhoneOfflineBundleDownloadState.Downloading
    val isBusy = isDownloading || uiState.isCheckingUpdates
    val showingRefreshResults = uiState.updateChecks.isNotEmpty()
    val selectedArea = OamDownloadCatalog.areas.firstOrNull { it.id == selectedAreaId }
    val selectorState =
        PhoneOfflineBundleSelectorState(
            query = query,
            selectedAreaId = selectedAreaId,
            installedAreaIds = uiState.installedAreaIds,
            statusByAreaId = uiState.statusByAreaId,
        )

    PhoneMapPopupDialog(
        title = stringResource(R.string.map_bundle_selector_title),
        onDismiss = onDismiss,
        dismissEnabled = !isBusy,
        titleAction = {
            IconButton(
                onClick = if (showingRefreshResults) onClearUpdateChecks else onCheckForUpdates,
                enabled = !isBusy && uiState.installedBundles.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Update,
                    contentDescription = stringResource(R.string.map_bundle_action_refresh),
                )
            }
        },
        text = {
            if (uiState.isCheckingUpdates) {
                Text(stringResource(R.string.map_bundle_refresh_checking))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (state is PhoneOfflineBundleDownloadState.Downloading) {
                phoneOfflineBundleDialogContent(
                    state = state,
                    selectorState = selectorState,
                    onQueryChanged = { query = it },
                    onAreaSelected = { selectedAreaId = it },
                    includeRouting = includeRouting,
                    onIncludeRoutingChanged = { includeRouting = it },
                    includeDem = includeDem,
                    onIncludeDemChanged = { includeDem = it },
                    includeRefugesInfo = includeRefugesInfo,
                    onIncludeRefugesInfoChanged = { includeRefugesInfo = it },
                    demSource = demSource,
                    onDemSourceChanged = { demSource = it },
                )
            } else if (showingRefreshResults) {
                phoneOfflineBundleRefreshResults(
                    checks = uiState.updateChecks,
                    selectedAreaIds = uiState.selectedRefreshAreaIds,
                    onToggleSelection = onToggleRefreshSelection,
                    onBack = onClearUpdateChecks,
                )
            } else {
                phoneOfflineBundleDialogContent(
                    state = state,
                    selectorState = selectorState,
                    onQueryChanged = { query = it },
                    onAreaSelected = { selectedAreaId = it },
                    includeRouting = includeRouting,
                    onIncludeRoutingChanged = { includeRouting = it },
                    includeDem = includeDem,
                    onIncludeDemChanged = { includeDem = it },
                    includeRefugesInfo = includeRefugesInfo,
                    onIncludeRefugesInfoChanged = { includeRefugesInfo = it },
                    demSource = demSource,
                    onDemSourceChanged = { demSource = it },
                )
            }
        },
        confirmButton = {
            when {
                state is PhoneOfflineBundleDownloadState.Downloading ->
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_action_cancel)) }
                uiState.isCheckingUpdates ->
                    TextButton(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.map_bundle_refresh_checking))
                    }
                showingRefreshResults ->
                    TextButton(
                        enabled = uiState.selectedRefreshAreaIds.isNotEmpty(),
                        onClick = onRefreshSelected,
                    ) {
                        Text(
                            stringResource(
                                R.string.map_bundle_action_refresh_selected,
                                uiState.selectedRefreshAreaIds.size,
                            ),
                        )
                    }
                else ->
                    TextButton(
                        enabled = selectedArea != null,
                        onClick = {
                            selectedArea?.let { area ->
                                onStart(
                                    PhoneOfflineBundleSelection(
                                        area = area,
                                        includeRouting = includeRouting,
                                        includeDem = includeDem,
                                        demSource = demSource,
                                        includeRefugesInfo = includeRefugesInfo,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.map_bundle_action_download))
                    }
            }
        },
        dismissButton = {
            if (!isBusy) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
            }
        },
    )
}

@Composable
private fun phoneOfflineBundleRefreshResults(
    checks: List<PhoneOfflineBundleUpdateCheck>,
    selectedAreaIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column {
        Text(stringResource(R.string.map_bundle_refresh_results))
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(checks, key = { it.bundle.areaId }) { check ->
                val selectable = check.status != PhoneOfflineBundleUpdateStatus.UP_TO_DATE
                ListItem(
                    headlineContent = { Text(check.bundle.areaLabel) },
                    supportingContent = { Text(check.status.refreshLabel()) },
                    trailingContent = {
                        Switch(
                            checked = check.bundle.areaId in selectedAreaIds,
                            onCheckedChange = { onToggleSelection(check.bundle.areaId) },
                            enabled = selectable,
                        )
                    },
                )
            }
        }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.map_bundle_action_back_to_download))
        }
    }
}

private fun PhoneOfflineBundleUpdateStatus.refreshLabel(): String =
    when (this) {
        PhoneOfflineBundleUpdateStatus.REPAIR_NEEDED -> "Repair needed"
        PhoneOfflineBundleUpdateStatus.UPDATE_AVAILABLE -> "Update available"
        PhoneOfflineBundleUpdateStatus.UP_TO_DATE -> "Up to date"
        PhoneOfflineBundleUpdateStatus.UNKNOWN -> "Could not verify — retry refresh"
    }

@Composable
@Suppress("LongParameterList") // Dialog content needs independent state callbacks for each option.
private fun phoneOfflineBundleDialogContent(
    state: PhoneOfflineBundleDownloadState,
    selectorState: PhoneOfflineBundleSelectorState,
    onQueryChanged: (String) -> Unit,
    onAreaSelected: (String) -> Unit,
    includeRouting: Boolean,
    onIncludeRoutingChanged: (Boolean) -> Unit,
    includeDem: Boolean,
    onIncludeDemChanged: (Boolean) -> Unit,
    includeRefugesInfo: Boolean,
    onIncludeRefugesInfoChanged: (Boolean) -> Unit,
    demSource: PhoneOfflineDemSource,
    onDemSourceChanged: (PhoneOfflineDemSource) -> Unit,
) {
    Column {
        when (state) {
            is PhoneOfflineBundleDownloadState.Downloading -> {
                Text(stringResource(state.progress.phase.stringResource()))
                if (state.progress.detail.isNotBlank()) Text(state.progress.detail)
                bundleProgress(state.progress)
            }
            is PhoneOfflineBundleDownloadState.Completed ->
                Text(stringResource(R.string.map_bundle_status_complete, state.bundle.areaLabel))
            is PhoneOfflineBundleDownloadState.Failed -> Text(stringResource(state.reason.stringResource()))
            PhoneOfflineBundleDownloadState.Cancelled ->
                Text(stringResource(R.string.map_bundle_status_cancelled))
            PhoneOfflineBundleDownloadState.Idle ->
                run {
                    phoneOfflineBundleAreaSelector(
                        selectorState = selectorState,
                        onQueryChanged = onQueryChanged,
                        onAreaSelected = onAreaSelected,
                    )
                    bundleOptions(
                        includeRouting = includeRouting,
                        onIncludeRoutingChanged = onIncludeRoutingChanged,
                        includeDem = includeDem,
                        onIncludeDemChanged = onIncludeDemChanged,
                        includeRefugesInfo = includeRefugesInfo,
                        onIncludeRefugesInfoChanged = onIncludeRefugesInfoChanged,
                        demSource = demSource,
                        onDemSourceChanged = onDemSourceChanged,
                    )
                }
        }
    }
}

@Composable
private fun phoneOfflineBundleAreaSelector(
    selectorState: PhoneOfflineBundleSelectorState,
    onQueryChanged: (String) -> Unit,
    onAreaSelected: (String) -> Unit,
) {
    val matchingAreas =
        remember(selectorState.query) {
            OamDownloadCatalog.areas.filter { area ->
                area.region.contains(selectorState.query, ignoreCase = true) ||
                    area.continent.contains(selectorState.query, ignoreCase = true)
            }
        }
    TextField(
        value = selectorState.query,
        onValueChange = onQueryChanged,
        label = { Text(stringResource(R.string.map_bundle_search_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
        items(matchingAreas, key = { it.id }) { area ->
            TextButton(
                onClick = { onAreaSelected(area.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(area.region)
                    Text(
                        stringResource(
                            R.string.map_bundle_area_sizes,
                            area.mapSizeLabel,
                            area.poiSizeLabel,
                        ),
                    )
                    if (area.id in selectorState.installedAreaIds) {
                        Text(stringResource(R.string.map_bundle_area_installed))
                    } else if (
                        selectorState.statusByAreaId[area.id]?.status == PhoneOfflineBundleStatus.RECOVERY_NEEDED
                    ) {
                        Text(stringResource(R.string.map_bundle_area_recovery))
                    } else if (selectorState.statusByAreaId[area.id]?.status == PhoneOfflineBundleStatus.PARTIAL) {
                        Text(stringResource(R.string.map_bundle_area_partial))
                    } else if (area.id == selectorState.selectedAreaId) {
                        Text(stringResource(R.string.map_bundle_area_selected))
                    }
                }
            }
        }
    }
    if (matchingAreas.isEmpty()) {
        Text(
            text = stringResource(R.string.map_bundle_no_areas),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private data class PhoneOfflineBundleSelectorState(
    val query: String,
    val selectedAreaId: String?,
    val installedAreaIds: Set<String>,
    val statusByAreaId: Map<String, PhoneOfflineBundleHealth>,
)

@Composable
@Suppress("LongParameterList") // Each option remains directly bound to its control.
private fun bundleOptions(
    includeRouting: Boolean,
    onIncludeRoutingChanged: (Boolean) -> Unit,
    includeDem: Boolean,
    onIncludeDemChanged: (Boolean) -> Unit,
    includeRefugesInfo: Boolean,
    onIncludeRefugesInfoChanged: (Boolean) -> Unit,
    demSource: PhoneOfflineDemSource,
    onDemSourceChanged: (PhoneOfflineDemSource) -> Unit,
) {
    Text("Bundle contents")
    ListItem(
        headlineContent = { Text("BRouter routing") },
        supportingContent = { Text("Download routing packs for this map") },
        trailingContent = { Switch(checked = includeRouting, onCheckedChange = onIncludeRoutingChanged) },
    )
    ListItem(
        headlineContent = { Text("Elevation") },
        supportingContent = { Text(demSource.label) },
        trailingContent = { Switch(checked = includeDem, onCheckedChange = onIncludeDemChanged) },
    )
    ListItem(
        headlineContent = { Text("Refuges.info") },
        supportingContent = { Text("Download refuges and mountain POI for this map") },
        trailingContent = { Switch(checked = includeRefugesInfo, onCheckedChange = onIncludeRefugesInfoChanged) },
    )
    if (includeDem) {
        Row(modifier = Modifier.fillMaxWidth()) {
            PhoneOfflineDemSource.entries.forEach { source ->
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = source == demSource,
                        onClick = { onDemSourceChanged(source) },
                    )
                    Text(source.shortLabel)
                }
            }
        }
    }
}

@Composable
private fun bundleProgress(progress: PhoneOfflineBundleProgress) {
    val context = LocalContext.current
    progress.percent?.let { percent ->
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("$percent%")
        return
    }
    val current = Formatter.formatFileSize(context, progress.bytesDownloaded)
    val totalBytes = progress.totalBytes
    if (totalBytes == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.map_bundle_progress_unknown, current))
    } else {
        val total = Formatter.formatFileSize(context, totalBytes)
        LinearProgressIndicator(
            progress = { (progress.bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.map_bundle_progress_known, current, total))
    }
}

private fun PhoneOfflineBundlePhase.stringResource(): Int =
    when (this) {
        PhoneOfflineBundlePhase.DOWNLOADING_MAP -> R.string.map_bundle_phase_downloading_map
        PhoneOfflineBundlePhase.INSTALLING_MAP -> R.string.map_bundle_phase_installing_map
        PhoneOfflineBundlePhase.DOWNLOADING_POI -> R.string.map_bundle_phase_downloading_poi
        PhoneOfflineBundlePhase.INSTALLING_POI -> R.string.map_bundle_phase_installing_poi
        PhoneOfflineBundlePhase.DOWNLOADING_ROUTING -> R.string.map_bundle_phase_downloading_routing
        PhoneOfflineBundlePhase.DOWNLOADING_DEM -> R.string.map_bundle_phase_downloading_dem
        PhoneOfflineBundlePhase.DOWNLOADING_REFUGES -> R.string.map_bundle_phase_downloading_refuges
    }

private fun PhoneOfflineBundleFailure.stringResource(): Int =
    when (this) {
        PhoneOfflineBundleFailure.NETWORK -> R.string.map_bundle_error_network
        PhoneOfflineBundleFailure.HTTP -> R.string.map_bundle_error_http
        PhoneOfflineBundleFailure.STORAGE -> R.string.map_bundle_error_storage
        PhoneOfflineBundleFailure.ARCHIVE -> R.string.map_bundle_error_archive
        PhoneOfflineBundleFailure.INVALID_MAP -> R.string.map_bundle_error_invalid_map
        PhoneOfflineBundleFailure.INVALID_POI -> R.string.map_bundle_error_invalid_poi
        PhoneOfflineBundleFailure.INVALID_REFUGES_INFO -> R.string.map_bundle_error_invalid_refuges
        PhoneOfflineBundleFailure.CANCELLED -> R.string.map_bundle_status_cancelled
    }
