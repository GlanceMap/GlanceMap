package com.glancemap.glancemapcompanionapp.map

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
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
import com.glancemap.trailcore.oam.OamDownloadArea
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
    onPause: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onRefreshSelected: () -> Unit,
    onToggleRefreshSelection: (String) -> Unit,
    onClearUpdateChecks: () -> Unit,
    initialDemSource: PhoneOfflineDemSource = PhoneOfflineDemSource.DEFAULT,
    initialAreaId: String? = null,
    initialIncludeRouting: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var selectedAreaId by remember(initialAreaId) { mutableStateOf(initialAreaId) }
    var selectedAreaFolder by remember { mutableStateOf<String?>(null) }
    var includeRouting by remember(initialIncludeRouting) { mutableStateOf(initialIncludeRouting) }
    var includeDem by remember { mutableStateOf(true) }
    var includeRefugesInfo by remember { mutableStateOf(false) }
    var demSource by remember(initialDemSource) { mutableStateOf(initialDemSource) }
    var showingBundleContent by remember { mutableStateOf(false) }
    val state = uiState.download
    val isDownloading = state is PhoneOfflineBundleDownloadState.Downloading
    val isBusy = isDownloading || uiState.isCheckingUpdates
    val showingRefreshResults = uiState.updateChecks.isNotEmpty()
    val canEditBundleContents = !isBusy && !showingRefreshResults && state is PhoneOfflineBundleDownloadState.Idle
    val selectedArea = OamDownloadCatalog.areas.firstOrNull { it.id == selectedAreaId }
    val selectorState =
        PhoneOfflineBundleSelectorState(
            query = query,
            selectedAreaId = selectedAreaId,
            selectedAreaFolder = selectedAreaFolder,
            installedAreaIds = uiState.installedAreaIds,
            statusByAreaId = uiState.statusByAreaId,
        )

    if (showingBundleContent) {
        PhoneOfflineBundleContentScreen(
            onBack = { showingBundleContent = false },
            onDismiss = onDismiss,
            includeRouting = includeRouting,
            onIncludeRoutingChanged = { includeRouting = it },
            includeDem = includeDem,
            onIncludeDemChanged = { includeDem = it },
            includeRefugesInfo = includeRefugesInfo,
            onIncludeRefugesInfoChanged = { includeRefugesInfo = it },
            demSource = demSource,
            onDemSourceChanged = { demSource = it },
        )
    } else {
        PhoneMapPopupDialog(
            title = stringResource(R.string.map_bundle_selector_title),
            onDismiss = onDismiss,
            dismissEnabled = !isBusy,
            titleAction = {
                IconButton(
                    onClick = { showingBundleContent = true },
                    enabled = canEditBundleContents,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.map_bundle_action_open_contents),
                    )
                }
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
                        onFolderSelected = { selectedAreaFolder = it },
                        onFolderCleared = { selectedAreaFolder = null },
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
                        onFolderSelected = { selectedAreaFolder = it },
                        onFolderCleared = { selectedAreaFolder = null },
                    )
                }
            },
            confirmButton = {
                when {
                    state is PhoneOfflineBundleDownloadState.Downloading ->
                        Row(horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onPause) {
                                Text(stringResource(R.string.map_bundle_action_pause))
                            }
                            TextButton(onClick = onStop) {
                                Text(stringResource(R.string.map_bundle_action_stop))
                            }
                        }
                    state is PhoneOfflineBundleDownloadState.Paused ->
                        TextButton(onClick = onResume) {
                            Text(stringResource(R.string.map_bundle_action_resume))
                        }
                    state is PhoneOfflineBundleDownloadState.Stopped ->
                        TextButton(onClick = onResume) {
                            Text(stringResource(R.string.map_bundle_action_restart))
                        }
                    state is PhoneOfflineBundleDownloadState.Failed ->
                        TextButton(onClick = onResume) {
                            Text(stringResource(R.string.map_bundle_action_retry))
                        }
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
                    phoneOfflineBundleDismissButton(
                        state = state,
                        onDismiss = onDismiss,
                        onCancel = onCancel,
                    )
                }
            },
        )
    }
}

@Composable
private fun phoneOfflineBundleDismissButton(
    state: PhoneOfflineBundleDownloadState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val canCancelDownload = state.canCancelSavedOperation()
    TextButton(onClick = if (canCancelDownload) onCancel else onDismiss) {
        Text(
            stringResource(
                if (canCancelDownload) R.string.common_action_cancel else R.string.common_action_close,
            ),
        )
    }
}

@Composable
@Suppress("LongParameterList") // Bundle-content controls stay bound to the download selection state.
private fun PhoneOfflineBundleContentScreen(
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    includeRouting: Boolean,
    onIncludeRoutingChanged: (Boolean) -> Unit,
    includeDem: Boolean,
    onIncludeDemChanged: (Boolean) -> Unit,
    includeRefugesInfo: Boolean,
    onIncludeRefugesInfoChanged: (Boolean) -> Unit,
    demSource: PhoneOfflineDemSource,
    onDemSourceChanged: (PhoneOfflineDemSource) -> Unit,
) {
    PhoneMapPopupDialog(
        title = stringResource(R.string.map_bundle_content_title),
        onDismiss = onDismiss,
        titleAction = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.map_bundle_action_back_to_download),
                )
            }
        },
        text = {
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
        },
        confirmButton = {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.common_action_done))
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
@Suppress(
    "LongParameterList",
    "CyclomaticComplexMethod",
) // Dialog content renders the existing download state machine without changing its transitions.
private fun phoneOfflineBundleDialogContent(
    state: PhoneOfflineBundleDownloadState,
    selectorState: PhoneOfflineBundleSelectorState,
    onQueryChanged: (String) -> Unit,
    onAreaSelected: (String) -> Unit,
    onFolderSelected: (String) -> Unit,
    onFolderCleared: () -> Unit,
) {
    Column {
        when (state) {
            is PhoneOfflineBundleDownloadState.Downloading -> {
                Text(stringResource(state.progress.phase.stringResource()))
                if (state.progress.detail.isNotBlank()) Text(state.progress.detail)
                bundleProgress(state.progress)
            }
            is PhoneOfflineBundleDownloadState.Paused -> {
                Text(stringResource(R.string.map_bundle_status_paused))
                if (state.progress.detail.isNotBlank()) Text(state.progress.detail)
                bundleProgress(state.progress)
            }
            is PhoneOfflineBundleDownloadState.Stopped -> {
                Text(stringResource(R.string.map_bundle_status_stopped))
                if (state.progress.detail.isNotBlank()) Text(state.progress.detail)
                bundleProgress(state.progress)
            }
            is PhoneOfflineBundleDownloadState.Completed ->
                Text(stringResource(R.string.map_bundle_status_complete, state.bundle.areaLabel))
            is PhoneOfflineBundleDownloadState.Failed -> {
                Text(stringResource(R.string.map_bundle_status_retry))
                state.context?.let { context ->
                    Text(stringResource(context.component.stringResource()))
                    context.fileName?.takeIf(String::isNotBlank)?.let { fileName -> Text(fileName) }
                    context.detail.takeIf(String::isNotBlank)?.let { detail ->
                        Text(detail)
                    } ?: Text(stringResource(state.reason.stringResource()))
                } ?: Text(stringResource(state.reason.stringResource()))
            }
            PhoneOfflineBundleDownloadState.Cancelled ->
                Text(stringResource(R.string.map_bundle_status_cancelled))
            PhoneOfflineBundleDownloadState.Idle ->
                run {
                    phoneOfflineBundleAreaSelector(
                        selectorState = selectorState,
                        onQueryChanged = onQueryChanged,
                        onAreaSelected = onAreaSelected,
                        onFolderSelected = onFolderSelected,
                        onFolderCleared = onFolderCleared,
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
    onFolderSelected: (String) -> Unit,
    onFolderCleared: () -> Unit,
) {
    val areaFolders =
        remember { phoneOfflineBundleAreaFolders(OamDownloadCatalog.areas) }
    val areaSearchQueryNormalized = selectorState.query.trim()
    val matchingAreas =
        remember(areaSearchQueryNormalized, selectorState.selectedAreaFolder) {
            OamDownloadCatalog.areas
                .asSequence()
                .filter { area ->
                    selectorState.selectedAreaFolder == null ||
                        area.continent == selectorState.selectedAreaFolder
                }.filter { area ->
                    areaSearchQueryNormalized.isBlank() ||
                        area.region.contains(areaSearchQueryNormalized, ignoreCase = true) ||
                        area.continent.contains(areaSearchQueryNormalized, ignoreCase = true)
                }.sortedWith(compareBy<OamDownloadArea> { it.continent }.thenBy { it.region })
                .toList()
        }
    val showingAreaResults = areaSearchQueryNormalized.isNotBlank() || selectorState.selectedAreaFolder != null
    val countriesLabel = stringResource(R.string.map_bundle_countries)
    val regionsLabel = stringResource(R.string.map_bundle_regions)
    TextField(
        value = selectorState.query,
        onValueChange = onQueryChanged,
        label = { Text(stringResource(R.string.map_bundle_search_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
        if (!showingAreaResults) {
            val (countryFolders, regionFolders) =
                areaFolders.partition { (folder, _) -> folder in PHONE_BUNDLE_COUNTRY_FOLDERS }
            phoneOfflineBundleFolderGroup(
                label = countriesLabel,
                folders = countryFolders,
                selectedAreaId = selectorState.selectedAreaId,
                onFolderSelected = onFolderSelected,
            )
            phoneOfflineBundleFolderGroup(
                label = regionsLabel,
                folders = regionFolders,
                selectedAreaId = selectorState.selectedAreaId,
                onFolderSelected = onFolderSelected,
            )
        } else {
            if (selectorState.selectedAreaFolder != null) {
                item {
                    TextButton(onClick = onFolderCleared) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.map_bundle_back_to_folders))
                    }
                }
            }
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
    }
    if (showingAreaResults && matchingAreas.isEmpty()) {
        Text(
            text = stringResource(R.string.map_bundle_no_areas),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun LazyListScope.phoneOfflineBundleFolderGroup(
    label: String,
    folders: List<Pair<String, List<OamDownloadArea>>>,
    selectedAreaId: String?,
    onFolderSelected: (String) -> Unit,
) {
    if (folders.isEmpty()) return
    item { Text(text = label, modifier = Modifier.fillMaxWidth()) }
    folders.forEach { (folder, folderAreas) ->
        val hasSelectedArea = folderAreas.any { it.id == selectedAreaId }
        item {
            ListItem(
                headlineContent = { Text(folder) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (hasSelectedArea) {
                                R.string.map_bundle_folder_area_count_selected
                            } else {
                                R.string.map_bundle_folder_area_count
                            },
                            folderAreas.size,
                        ),
                    )
                },
                leadingContent = {
                    Icon(imageVector = Icons.Filled.Folder, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth().clickable { onFolderSelected(folder) },
            )
        }
    }
}

private data class PhoneOfflineBundleSelectorState(
    val query: String,
    val selectedAreaId: String?,
    val selectedAreaFolder: String?,
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
    Column(modifier = Modifier.fillMaxWidth()) {
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
        if (includeDem) {
            PhoneOfflineDemSource.entries.forEach { source ->
                ListItem(
                    headlineContent = { Text(source.shortLabel) },
                    leadingContent = {
                        RadioButton(
                            selected = source == demSource,
                            onClick = { onDemSourceChanged(source) },
                        )
                    },
                )
            }
        }
        ListItem(
            headlineContent = { Text("Refuges.info") },
            supportingContent = { Text("Download refuges and mountain POI for this map") },
            trailingContent = { Switch(checked = includeRefugesInfo, onCheckedChange = onIncludeRefugesInfoChanged) },
        )
    }
}

private val PHONE_BUNDLE_COUNTRY_FOLDERS = setOf("Canada", "Germany", "Russia", "USA")

internal fun phoneOfflineBundleAreaFolders(
    areas: List<OamDownloadArea>,
): List<Pair<String, List<OamDownloadArea>>> =
    areas
        .groupBy { it.continent }
        .toSortedMap()
        .map { (continent, folderAreas) -> continent to folderAreas.sortedBy { it.region } }

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

private fun PhoneOfflineBundleComponent.stringResource(): Int =
    when (this) {
        PhoneOfflineBundleComponent.MAP -> R.string.map_bundle_failure_map
        PhoneOfflineBundleComponent.POI -> R.string.map_bundle_failure_poi
        PhoneOfflineBundleComponent.ROUTING -> R.string.map_bundle_failure_routing
        PhoneOfflineBundleComponent.DEM -> R.string.map_bundle_failure_dem
        PhoneOfflineBundleComponent.REFUGES_INFO -> R.string.map_bundle_failure_refuges
    }
