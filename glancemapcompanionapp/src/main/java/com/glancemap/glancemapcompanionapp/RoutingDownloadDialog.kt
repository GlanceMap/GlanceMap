@file:Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")

package com.glancemap.glancemapcompanionapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.filepicker.PageScrollbar
import com.glancemap.glancemapcompanionapp.filepicker.RoutingAreaMethod
import com.glancemap.glancemapcompanionapp.routing.BRouterTileMath

@Composable
internal fun RoutingDownloadDialog(
    context: Context,
    adaptive: CompanionAdaptiveSpec,
    viewModel: FileTransferViewModel,
    uiState: FileTransferUiState,
    isDownloadingRouting: Boolean,
    routingDownloadProgress: RoutingDownloadProgressState,
    watchInstalledMaps: List<WatchInstalledMap>,
    watchInstalledCoverageAreas: List<WatchInstalledCoverageArea>,
    isLoadingWatchInstalledMaps: Boolean,
    watchInstalledMapsStatusMessage: String?,
    lastRoutingDownloadedFiles: List<GeneratedPhoneFile>,
    saveGeneratedFilesOnPhone: (List<GeneratedPhoneFile>) -> Unit,
    onDismiss: () -> Unit,
) {
    var bboxInput by remember { mutableStateOf("") }
    var mapMenuExpanded by remember { mutableStateOf(false) }
    var watchMenuExpanded by remember { mutableStateOf(false) }
    var areaMethodMenuExpanded by remember { mutableStateOf(false) }
    var showRoutingTilePicker by remember { mutableStateOf(false) }
    var areaMethod by remember { mutableStateOf(RoutingAreaMethod.WATCH_MAP) }
    var selectedWatchMapKey by remember(uiState.selectedWatch?.id) {
        mutableStateOf(watchInstalledMaps.firstOrNull()?.filePath.orEmpty())
    }

    val selectedRoutingMapCandidate =
        remember(uiState.selectedWatch?.id, selectedWatchMapKey, watchInstalledMaps) {
            if (uiState.selectedWatch == null) {
                null
            } else {
                watchInstalledMaps.firstOrNull { it.filePath == selectedWatchMapKey }
                    ?: watchInstalledMaps.firstOrNull()
            }
        }
    val routingTilePickerInitialBbox =
        bboxInput
            .trim()
            .ifBlank { selectedRoutingMapCandidate?.bbox.orEmpty() }
    val resolvedRoutingBbox =
        remember(areaMethod, bboxInput, selectedRoutingMapCandidate) {
            when (areaMethod) {
                RoutingAreaMethod.WATCH_MAP -> selectedRoutingMapCandidate?.bbox.orEmpty()
                RoutingAreaMethod.TILE_PICKER,
                RoutingAreaMethod.MANUAL_BBOX,
                -> bboxInput.trim()
            }
        }
    val routingTiles =
        remember(resolvedRoutingBbox) {
            runCatching {
                if (resolvedRoutingBbox.isBlank()) {
                    emptyList()
                } else {
                    BRouterTileMath.tileFileNamesForBbox(resolvedRoutingBbox)
                }
            }.getOrDefault(emptyList())
        }
    val routingBboxError =
        remember(resolvedRoutingBbox) {
            if (resolvedRoutingBbox.isBlank()) {
                null
            } else {
                runCatching {
                    BRouterTileMath.parseBbox(resolvedRoutingBbox)
                }.exceptionOrNull()?.localizedMessage
            }
        }
    val routingAreaMethodLabel =
        when (areaMethod) {
            RoutingAreaMethod.WATCH_MAP -> stringResource(R.string.routing_area_method_watch_map)
            RoutingAreaMethod.TILE_PICKER -> stringResource(R.string.routing_area_method_tile_picker)
            RoutingAreaMethod.MANUAL_BBOX -> stringResource(R.string.routing_area_method_manual_bbox)
        }
    val routingAreaMethodDescription =
        when (areaMethod) {
            RoutingAreaMethod.WATCH_MAP -> stringResource(R.string.routing_area_method_watch_map_description)
            RoutingAreaMethod.TILE_PICKER -> stringResource(R.string.routing_area_method_tile_picker_description)
            RoutingAreaMethod.MANUAL_BBOX -> stringResource(R.string.routing_area_method_manual_bbox_description)
        }
    val routingWatchIsSelected = uiState.selectedWatch != null
    val selectedWatchReachable =
        isSelectedWatchReachable(
            selectedWatch = uiState.selectedWatch,
            availableWatches = uiState.availableWatches,
        )
    val routingWatchSelectionLabel =
        when {
            uiState.selectedWatch == null && uiState.availableWatches.isEmpty() ->
                stringResource(R.string.routing_no_watch_found)
            uiState.selectedWatch == null -> stringResource(R.string.routing_select_watch)
            selectedWatchReachable -> uiState.selectedWatch.displayName
            else ->
                stringResource(
                    R.string.routing_watch_disconnected,
                    uiState.selectedWatch.displayName,
                )
        }
    val routingMapSelectionLabel =
        selectedRoutingMapCandidate?.fileName ?: when {
            uiState.selectedWatch == null -> stringResource(R.string.routing_select_watch_first)
            !selectedWatchReachable -> stringResource(R.string.routing_reconnect_watch_first)
            isLoadingWatchInstalledMaps -> stringResource(R.string.routing_loading_watch_maps)
            else -> stringResource(R.string.routing_select_map_on_watch_label)
        }
    val routingCompletedSuccessfully =
        routingDownloadProgress.completed && routingDownloadProgress.success
    val canDownloadRouting =
        !isDownloadingRouting &&
            routingBboxError == null &&
            resolvedRoutingBbox.isNotBlank() &&
            routingTiles.isNotEmpty()
    val routingScrollState = rememberScrollState()
    val routingStatusBringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(
        routingDownloadProgress.isRunning,
        routingDownloadProgress.completed,
        routingDownloadProgress.message,
    ) {
        if (routingDownloadProgress.isRunning || routingDownloadProgress.completed) {
            routingStatusBringIntoViewRequester.bringIntoView()
        }
    }

    if (showRoutingTilePicker) {
        RoutingTilePickerDialog(
            initialBbox = routingTilePickerInitialBbox,
            watchInstalledCoverageAreas =
                watchInstalledCoverageAreas.filter { it.kind == WatchInstalledCoverageKind.ROUTING },
            onDismiss = { showRoutingTilePicker = false },
            onConfirm = { pickedBbox ->
                bboxInput = pickedBbox
                areaMethod = RoutingAreaMethod.TILE_PICKER
                showRoutingTilePicker = false
                if (!routingDownloadProgress.isRunning) {
                    viewModel.resetRoutingDownloadProgress()
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (!isDownloadingRouting) onDismiss()
        },
        title = { Text(stringResource(R.string.routing_download_title)) },
        text = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = adaptive.refugesDialogMaxHeight),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 10.dp)
                            .verticalScroll(routingScrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.routing_download_intro),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        stringResource(R.string.routing_download_files_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        stringResource(R.string.routing_select_watch_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    androidx.compose.foundation.layout
                        .Spacer(modifier = Modifier.size(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { watchMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isDownloadingRouting && uiState.availableWatches.isNotEmpty(),
                                colors =
                                    ButtonDefaults.outlinedButtonColors(
                                        contentColor =
                                            when {
                                                selectedWatchReachable -> Color(0xFF2E7D32)
                                                routingWatchIsSelected -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                        containerColor =
                                            when {
                                                selectedWatchReachable -> Color(0x1A2E7D32)
                                                routingWatchIsSelected -> MaterialTheme.colorScheme.errorContainer
                                                else -> Color.Transparent
                                            },
                                    ),
                            ) {
                                Text(
                                    routingWatchSelectionLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(
                                expanded = watchMenuExpanded,
                                onDismissRequest = { watchMenuExpanded = false },
                            ) {
                                uiState.availableWatches.forEach { watch ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                watch.displayName,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = {
                                            viewModel.onWatchSelected(context, watch)
                                            selectedWatchMapKey = ""
                                            mapMenuExpanded = false
                                            watchMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { viewModel.findWatchNodes() },
                            enabled = !isDownloadingRouting,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Update,
                                contentDescription =
                                    stringResource(
                                        R.string.routing_update_watch_list_content_description,
                                    ),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (uiState.selectedWatch != null && !selectedWatchReachable) {
                        Text(
                            stringResource(
                                R.string.routing_watch_disconnected_hint,
                                selectedWatchDisconnectedStatusMessage(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (uiState.availableWatches.isEmpty()) {
                        Text(
                            stringResource(R.string.routing_no_watches_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (uiState.selectedWatch == null) {
                        Text(
                            stringResource(R.string.routing_select_watch_hint_for_map),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(
                        stringResource(R.string.routing_select_area_source_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { areaMethodMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloadingRouting,
                        ) {
                            Text(routingAreaMethodLabel)
                        }
                        DropdownMenu(
                            expanded = areaMethodMenuExpanded,
                            onDismissRequest = { areaMethodMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.routing_area_method_watch_map)) },
                                onClick = {
                                    areaMethod = RoutingAreaMethod.WATCH_MAP
                                    areaMethodMenuExpanded = false
                                },
                                enabled = selectedWatchReachable,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.routing_area_method_tile_picker)) },
                                onClick = {
                                    areaMethod = RoutingAreaMethod.TILE_PICKER
                                    mapMenuExpanded = false
                                    areaMethodMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.routing_area_method_manual_bbox)) },
                                onClick = {
                                    areaMethod = RoutingAreaMethod.MANUAL_BBOX
                                    mapMenuExpanded = false
                                    areaMethodMenuExpanded = false
                                },
                            )
                        }
                    }
                    Text(
                        routingAreaMethodDescription,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    when (areaMethod) {
                        RoutingAreaMethod.WATCH_MAP -> {
                            Text(
                                stringResource(R.string.routing_select_map_on_watch_label),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { mapMenuExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = selectedWatchReachable && !isLoadingWatchInstalledMaps,
                                    ) {
                                        Text(routingMapSelectionLabel)
                                    }
                                    DropdownMenu(
                                        expanded = mapMenuExpanded,
                                        onDismissRequest = { mapMenuExpanded = false },
                                    ) {
                                        watchInstalledMaps.forEach { candidate ->
                                            DropdownMenuItem(
                                                text = { Text(candidate.fileName) },
                                                onClick = {
                                                    selectedWatchMapKey = candidate.filePath
                                                    mapMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.refreshWatchInstalledMaps(
                                            context = context,
                                            showToastIfUnavailable = true,
                                        )
                                    },
                                    enabled = selectedWatchReachable && !isLoadingWatchInstalledMaps,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Update,
                                        contentDescription =
                                            stringResource(
                                                R.string.routing_update_watch_maps_content_description,
                                            ),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            when {
                                uiState.selectedWatch != null && !selectedWatchReachable -> {
                                    Text(
                                        stringResource(
                                            R.string.routing_watch_disconnected_hint,
                                            selectedWatchDisconnectedStatusMessage(),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                isLoadingWatchInstalledMaps -> {
                                    Text(
                                        stringResource(R.string.routing_loading_watch_maps),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                uiState.selectedWatch == null -> {
                                    Text(
                                        stringResource(R.string.routing_select_watch_hint_for_map),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                !watchInstalledMapsStatusMessage.isNullOrBlank() -> {
                                    Text(
                                        watchInstalledMapsStatusMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                watchInstalledMaps.isEmpty() -> {
                                    Text(
                                        stringResource(R.string.routing_no_watch_maps_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                selectedRoutingMapCandidate != null -> {
                                    Text(
                                        stringResource(
                                            R.string.routing_area_bbox,
                                            selectedRoutingMapCandidate.bbox,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }

                        RoutingAreaMethod.TILE_PICKER -> {
                            OutlinedButton(
                                onClick = { showRoutingTilePicker = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isDownloadingRouting,
                            ) {
                                Text(
                                    if (bboxInput.isBlank()) {
                                        stringResource(R.string.routing_action_open_tile_picker)
                                    } else {
                                        stringResource(R.string.routing_action_edit_tiles)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (bboxInput.isBlank()) {
                                Text(
                                    stringResource(R.string.routing_no_tiles_selected),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.routing_tile_bbox, bboxInput),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        RoutingAreaMethod.MANUAL_BBOX -> {
                            Text(
                                stringResource(R.string.routing_manual_bbox_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = bboxInput,
                                onValueChange = { bboxInput = it },
                                label = { Text(stringResource(R.string.routing_bbox_label)) },
                                singleLine = true,
                            )
                        }
                    }

                    if (routingBboxError != null) {
                        Text(
                            routingBboxError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (routingTiles.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                R.plurals.routing_packs_title,
                                routingTiles.size,
                                routingTiles.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            routingTiles.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (routingDownloadProgress.isRunning || routingDownloadProgress.completed) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(routingStatusBringIntoViewRequester),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val progressValue =
                                (routingDownloadProgress.progressPercent / 100f).coerceIn(0f, 1f)
                            Text(
                                routingDownloadProgress.status.ifBlank {
                                    if (routingDownloadProgress.isRunning) {
                                        stringResource(R.string.routing_downloading_status)
                                    } else {
                                        stringResource(R.string.routing_download_finished_status)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LinearProgressIndicator(
                                progress = { progressValue },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (routingDownloadProgress.detail.isNotBlank()) {
                                    Text(
                                        routingDownloadProgress.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } else {
                                    Spacer(modifier = Modifier)
                                }
                                Text(
                                    "${routingDownloadProgress.progressPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (routingDownloadProgress.message.isNotBlank()) {
                                Text(
                                    routingDownloadProgress.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (routingDownloadProgress.success) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                )
                            }
                            if (routingCompletedSuccessfully) {
                                Text(
                                    stringResource(R.string.routing_download_complete_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                PageScrollbar(
                    scrollState = routingScrollState,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                )
            }
        },
        confirmButton = {
            if (routingCompletedSuccessfully) {
                TextButton(
                    onClick = {
                        onDismiss()
                        viewModel.resetRoutingDownloadProgress()
                        viewModel.sendFiles(context)
                    },
                ) {
                    Text(stringResource(R.string.common_action_send))
                }
            } else {
                TextButton(
                    onClick = {
                        viewModel.resetRoutingDownloadProgress()
                        viewModel.downloadRoutingByBbox(
                            context = context,
                            bbox = resolvedRoutingBbox,
                            appendToSelection = true,
                        )
                    },
                    enabled = canDownloadRouting && !isDownloadingRouting,
                ) {
                    Text(
                        stringResource(
                            if (isDownloadingRouting) {
                                R.string.routing_action_downloading
                            } else {
                                R.string.common_action_download
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            if (routingCompletedSuccessfully) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.resetRoutingDownloadProgress()
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                    TextButton(
                        onClick = { saveGeneratedFilesOnPhone(lastRoutingDownloadedFiles) },
                        enabled = lastRoutingDownloadedFiles.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.routing_action_save_on_phone))
                    }
                }
            } else {
                TextButton(
                    onClick = {
                        if (isDownloadingRouting) {
                            viewModel.cancelRoutingDownload()
                        } else {
                            viewModel.resetRoutingDownloadProgress()
                            onDismiss()
                        }
                    },
                    enabled = true,
                ) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            }
        },
    )
}
