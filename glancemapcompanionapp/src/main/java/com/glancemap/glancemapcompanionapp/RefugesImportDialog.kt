@file:Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")

package com.glancemap.glancemapcompanionapp

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.filepicker.PageScrollbar
import com.glancemap.glancemapcompanionapp.filepicker.PoiAreaMethod
import com.glancemap.glancemapcompanionapp.filepicker.isAutoPoiFileName
import com.glancemap.glancemapcompanionapp.filepicker.suggestPoiImportFileName
import com.glancemap.glancemapcompanionapp.refuges.OsmPoiImportCategory
import com.glancemap.glancemapcompanionapp.refuges.RefugesGeoJsonPoiImporter
import com.glancemap.glancemapcompanionapp.refuges.RefugesImportRequest
import com.glancemap.glancemapcompanionapp.refuges.RefugesPointType
import com.glancemap.glancemapcompanionapp.refuges.RefugesRegionPreset
import com.glancemap.glancemapcompanionapp.refuges.buildOsmPoiCategoryPresets
import com.glancemap.glancemapcompanionapp.refuges.defaultOsmPoiCategoryIds
import com.glancemap.glancemapcompanionapp.refuges.osmPoiImportCategories

internal data class RefugesTypePreset(
    val label: String,
    val typeIds: Set<Int>,
)

@Composable
internal fun RefugesImportDialog(
    context: Context,
    adaptive: CompanionAdaptiveSpec,
    viewModel: FileTransferViewModel,
    uiState: FileTransferUiState,
    isImportingRefuges: Boolean,
    poiImportProgress: PoiImportProgressState,
    lastRefugesRequest: RefugesImportRequest?,
    refugesRegionPresets: List<RefugesRegionPreset>,
    useDetailedRefugesRegionPresets: Boolean,
    onUseDetailedRefugesRegionPresetsChange: (Boolean) -> Unit,
    watchInstalledMaps: List<WatchInstalledMap>,
    watchInstalledCoverageAreas: List<WatchInstalledCoverageArea>,
    isLoadingWatchInstalledMaps: Boolean,
    watchInstalledMapsStatusMessage: String?,
    lastImportedPoiFile: GeneratedPhoneFile?,
    saveGeneratedFilesOnPhone: (List<GeneratedPhoneFile>) -> Unit,
    onDismiss: () -> Unit,
) {
    val pointTypeOptions =
        remember {
            RefugesGeoJsonPoiImporter.defaultPointTypes()
        }
    val allPointTypeIds =
        remember(pointTypeOptions) {
            pointTypeOptions.map { it.id }.toSet()
        }
    val hutsOnlyLabel = stringResource(R.string.refuges_preset_huts_only)
    val hutsAndWaterLabel = stringResource(R.string.refuges_preset_huts_water)
    val allTypesLabel = stringResource(R.string.refuges_preset_all)
    val pointTypePresets =
        remember(allPointTypeIds, hutsOnlyLabel, hutsAndWaterLabel, allTypesLabel) {
            buildRefugesTypePresets(
                allPointTypeIds = allPointTypeIds,
                hutsOnlyLabel = hutsOnlyLabel,
                hutsAndWaterLabel = hutsAndWaterLabel,
                allTypesLabel = allTypesLabel,
            )
        }
    val osmCategoryOptions =
        remember {
            osmPoiImportCategories()
        }
    val allOsmCategoryIds =
        remember(osmCategoryOptions) {
            osmCategoryOptions.map { it.id }.toSet()
        }
    val osmCategoryPresets =
        remember(allOsmCategoryIds) {
            buildOsmPoiCategoryPresets(allOsmCategoryIds)
        }
    var bboxInput by remember(lastRefugesRequest?.bbox) {
        mutableStateOf(lastRefugesRequest?.bbox.orEmpty())
    }
    var fileNameInput by remember(lastRefugesRequest?.fileName) {
        mutableStateOf(lastRefugesRequest?.fileName ?: "osm-mountain.poi")
    }
    var selectedTypeIds by remember(lastRefugesRequest?.typePointIds) {
        mutableStateOf(lastRefugesRequest?.typePointIds ?: allPointTypeIds)
    }
    var selectedSource by remember { mutableStateOf(PoiImportSource.OSM) }
    var enrichWithOsm by remember { mutableStateOf(false) }

    fun selectSource(source: PoiImportSource) {
        if (selectedSource == source) return

        selectedSource = source
        if (source == PoiImportSource.OSM) {
            enrichWithOsm = false
        }
        if (!poiImportProgress.isRunning) {
            viewModel.resetPoiImportProgress()
        }
    }

    var selectedOsmCategoryIds by remember {
        mutableStateOf(defaultOsmPoiCategoryIds())
    }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    val selectRegionHint = stringResource(R.string.refuges_select_region_hint)
    val customRegionLabel = stringResource(R.string.refuges_custom_region)
    var selectedRegionLabel by remember(lastRefugesRequest?.bbox, refugesRegionPresets, selectRegionHint) {
        mutableStateOf(
            refugesRegionPresets
                .firstOrNull {
                    it.bbox.isNotBlank() && it.bbox == lastRefugesRequest?.bbox
                }?.label ?: selectRegionHint,
        )
    }
    var mapMenuExpanded by remember { mutableStateOf(false) }
    var watchMenuExpanded by remember { mutableStateOf(false) }
    var areaMethodMenuExpanded by remember { mutableStateOf(false) }
    var showBboxMapPicker by remember { mutableStateOf(false) }
    var areaMethod by remember { mutableStateOf(PoiAreaMethod.WATCH_MAP) }
    var lastSuggestedPoiFileName by remember { mutableStateOf("") }
    var selectedWatchMapKey by remember(uiState.selectedWatch?.id) {
        mutableStateOf("")
    }

    val selectedMapCandidate =
        if (uiState.selectedWatch == null) {
            null
        } else {
            watchInstalledMaps.firstOrNull { it.filePath == selectedWatchMapKey }
        }
    val mapPickerInitialBbox =
        bboxInput
            .trim()
            .ifBlank { selectedMapCandidate?.bbox.orEmpty() }
    val selectedWatchReachable =
        isSelectedWatchReachable(
            selectedWatch = uiState.selectedWatch,
            availableWatches = uiState.availableWatches,
        )
    val watchSelectionLabel =
        when {
            uiState.selectedWatch == null -> stringResource(R.string.refuges_select_watch_hint)
            selectedWatchReachable -> uiState.selectedWatch.displayName
            else ->
                stringResource(
                    R.string.refuges_watch_disconnected,
                    uiState.selectedWatch.displayName,
                )
        }
    val watchIsSelected = uiState.selectedWatch != null
    val mapSelectionLabel =
        when {
            uiState.selectedWatch == null -> stringResource(R.string.refuges_select_watch_first)
            !selectedWatchReachable -> stringResource(R.string.refuges_reconnect_watch_first)
            selectedMapCandidate != null -> selectedMapCandidate.fileName
            else -> stringResource(R.string.refuges_select_map_hint)
        }
    val suggestedPoiFileName =
        remember(
            selectedMapCandidate?.fileName,
            selectedSource,
            enrichWithOsm,
        ) {
            suggestPoiImportFileName(
                mapFileName = selectedMapCandidate?.fileName,
                source = selectedSource,
                enrichWithOsm = selectedSource == PoiImportSource.REFUGES && enrichWithOsm,
            )
        }
    val areaMethodLabel =
        when (areaMethod) {
            PoiAreaMethod.WATCH_MAP -> stringResource(R.string.refuges_area_method_watch_map)
            PoiAreaMethod.REFUGES_PRESET -> stringResource(R.string.refuges_area_method_region)
            PoiAreaMethod.MAP_PICKER -> stringResource(R.string.refuges_area_method_map_picker)
            PoiAreaMethod.MANUAL_BBOX -> stringResource(R.string.refuges_area_method_manual_bbox)
        }
    val areaMethodDescription =
        when (areaMethod) {
            PoiAreaMethod.WATCH_MAP -> stringResource(R.string.refuges_area_method_watch_map_description)
            PoiAreaMethod.REFUGES_PRESET -> stringResource(R.string.refuges_area_method_region_description)
            PoiAreaMethod.MAP_PICKER -> stringResource(R.string.refuges_area_method_map_picker_description)
            PoiAreaMethod.MANUAL_BBOX -> stringResource(R.string.refuges_area_method_manual_bbox_description)
        }
    val resolvedImportBbox =
        when (areaMethod) {
            PoiAreaMethod.WATCH_MAP -> selectedMapCandidate?.bbox.orEmpty()
            PoiAreaMethod.REFUGES_PRESET,
            PoiAreaMethod.MAP_PICKER,
            PoiAreaMethod.MANUAL_BBOX,
            -> bboxInput.trim()
        }
    val requiresRefugesTypeSelection = selectedSource == PoiImportSource.REFUGES
    val requiresOsmTypeSelection =
        selectedSource == PoiImportSource.OSM ||
            (selectedSource == PoiImportSource.REFUGES && enrichWithOsm)
    val canImport =
        !isImportingRefuges &&
            (!requiresRefugesTypeSelection || selectedTypeIds.isNotEmpty()) &&
            (!requiresOsmTypeSelection || selectedOsmCategoryIds.isNotEmpty()) &&
            resolvedImportBbox.isNotBlank()
    val importCompletedSuccessfully =
        poiImportProgress.completed &&
            poiImportProgress.success &&
            !poiImportProgress.isRunning
    val importPoiScrollState = rememberScrollState()
    val importStatusBringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(suggestedPoiFileName) {
        val currentName = fileNameInput.trim()
        val shouldApplySuggestion =
            currentName.isBlank() ||
                currentName == lastSuggestedPoiFileName ||
                isAutoPoiFileName(currentName)
        if (shouldApplySuggestion && currentName != suggestedPoiFileName) {
            fileNameInput = suggestedPoiFileName
        }
        lastSuggestedPoiFileName = suggestedPoiFileName
    }

    LaunchedEffect(
        poiImportProgress.isRunning,
        poiImportProgress.completed,
        poiImportProgress.message,
    ) {
        if (poiImportProgress.isRunning || poiImportProgress.completed) {
            importStatusBringIntoViewRequester.bringIntoView()
        }
    }

    if (showBboxMapPicker) {
        BboxMapPickerDialog(
            initialBbox = mapPickerInitialBbox,
            selectedSource = selectedSource,
            watchInstalledCoverageAreas =
                watchInstalledCoverageAreas.filter { it.kind == WatchInstalledCoverageKind.POI },
            onDismiss = { showBboxMapPicker = false },
            onConfirm = { pickedBbox ->
                bboxInput = pickedBbox
                selectedRegionLabel = customRegionLabel
                areaMethod = PoiAreaMethod.MAP_PICKER
                showBboxMapPicker = false
                if (!poiImportProgress.isRunning) {
                    viewModel.resetPoiImportProgress()
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (!isImportingRefuges) onDismiss()
        },
        title = { Text(stringResource(R.string.refuges_import_title)) },
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
                            .verticalScroll(importPoiScrollState),
                ) {
                    Text(
                        stringResource(R.string.refuges_select_watch_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { watchMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isImportingRefuges && uiState.availableWatches.isNotEmpty(),
                                colors =
                                    ButtonDefaults.outlinedButtonColors(
                                        contentColor =
                                            when {
                                                selectedWatchReachable -> Color(0xFF2E7D32)
                                                watchIsSelected -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                        containerColor =
                                            when {
                                                selectedWatchReachable -> Color(0x1A2E7D32)
                                                watchIsSelected -> MaterialTheme.colorScheme.errorContainer
                                                else -> Color.Transparent
                                            },
                                    ),
                            ) {
                                Text(
                                    watchSelectionLabel,
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
                            enabled = !isImportingRefuges,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription =
                                    stringResource(
                                        R.string.common_refresh_watch_list_content_description,
                                    ),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (uiState.selectedWatch != null && !selectedWatchReachable) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(
                                R.string.refuges_watch_disconnected_hint,
                                selectedWatchDisconnectedStatusMessage(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (uiState.availableWatches.isEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.refuges_no_watches_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (uiState.selectedWatch == null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.refuges_select_watch_hint_for_map),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.refuges_source_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedSource == PoiImportSource.OSM,
                            onClick = {
                                selectSource(PoiImportSource.OSM)
                            },
                            label = { Text("OSM") },
                            colors = companionFilterChipColors(),
                        )
                        FilterChip(
                            selected = selectedSource == PoiImportSource.REFUGES,
                            onClick = {
                                selectSource(PoiImportSource.REFUGES)
                            },
                            label = { Text("Refuges.info") },
                            colors = companionFilterChipColors(),
                        )
                    }
                    if (selectedSource == PoiImportSource.REFUGES) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.refuges_source_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.refuges_enrich_with_osm),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Switch(
                                checked = enrichWithOsm,
                                onCheckedChange = { enrichWithOsm = it },
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.refuges_osm_categories_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (requiresOsmTypeSelection) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.refuges_osm_categories_label),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OsmPoiCategoryPresetChips(
                            presets = osmCategoryPresets,
                            selectedIds = selectedOsmCategoryIds,
                            onPresetSelected = { presetIds ->
                                selectedOsmCategoryIds = presetIds
                            },
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OsmPoiCategorySelectionGroup(
                            options = osmCategoryOptions,
                            selectedIds = selectedOsmCategoryIds,
                            onCheckedChange = { categoryId, isChecked ->
                                selectedOsmCategoryIds =
                                    if (isChecked) {
                                        selectedOsmCategoryIds + categoryId
                                    } else {
                                        selectedOsmCategoryIds - categoryId
                                    }
                            },
                        )
                        if (selectedOsmCategoryIds.isEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.refuges_osm_category_error),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (selectedSource == PoiImportSource.REFUGES) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.refuges_point_types_label),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        RefugesTypePresetChips(
                            presets = pointTypePresets,
                            selectedIds = selectedTypeIds,
                            onPresetSelected = { presetIds ->
                                selectedTypeIds = presetIds
                            },
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        RefugesTypeSelectionGroup(
                            options = pointTypeOptions,
                            selectedIds = selectedTypeIds,
                            onCheckedChange = { typeId, isChecked ->
                                selectedTypeIds =
                                    if (isChecked) {
                                        selectedTypeIds + typeId
                                    } else {
                                        selectedTypeIds - typeId
                                    }
                            },
                        )
                        if (selectedTypeIds.isEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.refuges_select_type_error),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.refuges_select_area_source_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { areaMethodMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isImportingRefuges,
                        ) {
                            Text(
                                areaMethodLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = areaMethodMenuExpanded,
                            onDismissRequest = { areaMethodMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refuges_area_method_watch_map)) },
                                onClick = {
                                    areaMethod = PoiAreaMethod.WATCH_MAP
                                    regionMenuExpanded = false
                                    areaMethodMenuExpanded = false
                                },
                                enabled = selectedWatchReachable,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refuges_area_method_region)) },
                                onClick = {
                                    areaMethod = PoiAreaMethod.REFUGES_PRESET
                                    mapMenuExpanded = false
                                    areaMethodMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refuges_area_method_map_picker)) },
                                onClick = {
                                    areaMethod = PoiAreaMethod.MAP_PICKER
                                    mapMenuExpanded = false
                                    regionMenuExpanded = false
                                    areaMethodMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refuges_area_method_manual_bbox)) },
                                onClick = {
                                    areaMethod = PoiAreaMethod.MANUAL_BBOX
                                    mapMenuExpanded = false
                                    regionMenuExpanded = false
                                    areaMethodMenuExpanded = false
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        areaMethodDescription,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    when (areaMethod) {
                        PoiAreaMethod.WATCH_MAP -> {
                            Text(
                                stringResource(R.string.refuges_select_map_on_watch_label),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
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
                                        Text(mapSelectionLabel)
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
                                        Icons.Default.Refresh,
                                        contentDescription =
                                            stringResource(
                                                R.string.refuges_refresh_watch_maps_content_description,
                                            ),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (uiState.selectedWatch != null && !selectedWatchReachable) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    stringResource(
                                        R.string.refuges_watch_map_disconnected_hint,
                                        selectedWatchDisconnectedStatusMessage(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (isLoadingWatchInstalledMaps) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.refuges_loading_watch_maps),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (!watchInstalledMapsStatusMessage.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    watchInstalledMapsStatusMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (uiState.selectedWatch != null && watchInstalledMaps.isEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.refuges_no_watch_maps_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (selectedMapCandidate != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    stringResource(
                                        R.string.refuges_area_bbox,
                                        selectedMapCandidate.bbox,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        PoiAreaMethod.REFUGES_PRESET -> {
                            Text(
                                stringResource(R.string.refuges_region_preset_label),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text =
                                        if (useDetailedRefugesRegionPresets) {
                                            stringResource(R.string.refuges_detailed_massifs)
                                        } else {
                                            stringResource(R.string.refuges_compact_zones)
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Switch(
                                    checked = useDetailedRefugesRegionPresets,
                                    onCheckedChange = { enabled ->
                                        regionMenuExpanded = false
                                        onUseDetailedRefugesRegionPresetsChange(enabled)
                                    },
                                    enabled = !isImportingRefuges,
                                )
                            }
                            Text(
                                text =
                                    if (useDetailedRefugesRegionPresets) {
                                        stringResource(R.string.refuges_detailed_massifs_hint)
                                    } else {
                                        stringResource(R.string.refuges_compact_zones_hint)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { regionMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        selectedRegionLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                DropdownMenu(
                                    expanded = regionMenuExpanded,
                                    onDismissRequest = { regionMenuExpanded = false },
                                    modifier = Modifier.heightIn(max = 320.dp),
                                ) {
                                    refugesRegionPresets.forEach { preset ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    preset.label,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            onClick = {
                                                selectedRegionLabel = preset.label
                                                if (preset.bbox.isNotBlank()) {
                                                    bboxInput = preset.bbox
                                                }
                                                regionMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            if (bboxInput.isBlank()) {
                                Text(
                                    stringResource(R.string.refuges_select_preset_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.refuges_area_bbox, bboxInput),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        PoiAreaMethod.MAP_PICKER -> {
                            OutlinedButton(
                                onClick = { showBboxMapPicker = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isImportingRefuges,
                            ) {
                                Text(
                                    if (bboxInput.isBlank()) {
                                        stringResource(R.string.refuges_action_open_map_picker)
                                    } else {
                                        stringResource(R.string.refuges_action_edit_area_map)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            if (bboxInput.isBlank()) {
                                Text(
                                    stringResource(R.string.refuges_no_map_area_selected),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.refuges_area_bbox, bboxInput),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        PoiAreaMethod.MANUAL_BBOX -> {
                            Text(
                                stringResource(R.string.refuges_manual_bbox_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = bboxInput,
                                onValueChange = { bboxInput = it },
                                label = { Text(stringResource(R.string.refuges_bbox_label)) },
                                singleLine = true,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        label = { Text(stringResource(R.string.refuges_file_name_label)) },
                        singleLine = true,
                    )
                    if (poiImportProgress.isRunning || poiImportProgress.completed) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(importStatusBringIntoViewRequester),
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            val progressValue = (poiImportProgress.progressPercent / 100f).coerceIn(0f, 1f)
                            val currentStatus =
                                poiImportProgress.status.ifBlank {
                                    if (poiImportProgress.isRunning) {
                                        stringResource(R.string.refuges_importing_status)
                                    } else {
                                        stringResource(R.string.refuges_import_finished_status)
                                    }
                                }
                            val showBusySpinner =
                                poiImportProgress.isRunning &&
                                    (
                                        currentStatus.contains("Connecting", ignoreCase = true) ||
                                            currentStatus.contains("Waiting", ignoreCase = true)
                                    )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (showBusySpinner) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text(
                                    currentStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progressValue },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "${poiImportProgress.progressPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (poiImportProgress.message.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    poiImportProgress.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (poiImportProgress.success) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                )
                            }
                            if (importCompletedSuccessfully) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.refuges_import_complete_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                PageScrollbar(
                    scrollState = importPoiScrollState,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                )
            }
        },
        confirmButton = {
            if (importCompletedSuccessfully) {
                TextButton(
                    onClick = {
                        onDismiss()
                        viewModel.resetPoiImportProgress()
                        viewModel.sendFiles(context)
                    },
                ) {
                    Text(stringResource(R.string.common_action_send))
                }
            } else {
                TextButton(
                    onClick = {
                        viewModel.resetPoiImportProgress()
                        viewModel.importPoiByBbox(
                            context = context,
                            bbox = resolvedImportBbox,
                            fileName = fileNameInput,
                            source = selectedSource,
                            enrichWithOsm =
                                selectedSource == PoiImportSource.REFUGES &&
                                    enrichWithOsm,
                            selectedRefugesTypeIds = selectedTypeIds,
                            selectedOsmCategoryIds = selectedOsmCategoryIds,
                            appendToSelection = true,
                        )
                    },
                    enabled = canImport && !isImportingRefuges,
                ) {
                    Text(
                        stringResource(
                            if (isImportingRefuges) {
                                R.string.refuges_action_importing
                            } else {
                                R.string.refuges_action_import
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            if (importCompletedSuccessfully) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.resetPoiImportProgress()
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                    TextButton(
                        onClick = {
                            lastImportedPoiFile?.let { file ->
                                saveGeneratedFilesOnPhone(listOf(file))
                            }
                        },
                        enabled = lastImportedPoiFile != null,
                    ) {
                        Text(stringResource(R.string.refuges_action_save_on_phone))
                    }
                }
            } else {
                TextButton(
                    onClick = {
                        if (isImportingRefuges) {
                            viewModel.cancelPoiImport()
                        } else {
                            viewModel.resetPoiImportProgress()
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

@Composable
internal fun RefugesTypePresetChips(
    presets: List<RefugesTypePreset>,
    selectedIds: Set<Int>,
    onPresetSelected: (Set<Int>) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = selectedIds == preset.typeIds,
                onClick = { onPresetSelected(preset.typeIds) },
                label = { Text(preset.label) },
                colors = companionFilterChipColors(),
            )
        }
    }
}

@Composable
internal fun RefugesTypeSelectionGroup(
    options: List<RefugesPointType>,
    selectedIds: Set<Int>,
    onCheckedChange: (typeId: Int, isChecked: Boolean) -> Unit,
) {
    options.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { type ->
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = type.id in selectedIds,
                        onCheckedChange = { checked ->
                            onCheckedChange(type.id, checked)
                        },
                    )
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun buildRefugesTypePresets(
    allPointTypeIds: Set<Int>,
    hutsOnlyLabel: String,
    hutsAndWaterLabel: String,
    allTypesLabel: String,
): List<RefugesTypePreset> {
    val hutsOnly = allPointTypeIds.intersect(setOf(7, 10, 9))
    val hutsAndWater = allPointTypeIds.intersect(setOf(7, 10, 9, 23))
    return listOf(
        RefugesTypePreset(
            label = hutsOnlyLabel,
            typeIds = hutsOnly.ifEmpty { allPointTypeIds },
        ),
        RefugesTypePreset(
            label = hutsAndWaterLabel,
            typeIds = hutsAndWater.ifEmpty { allPointTypeIds },
        ),
        RefugesTypePreset(
            label = allTypesLabel,
            typeIds = allPointTypeIds,
        ),
    )
}

@Composable
internal fun OsmPoiCategoryPresetChips(
    presets: List<com.glancemap.glancemapcompanionapp.refuges.OsmPoiImportCategoryPreset>,
    selectedIds: Set<String>,
    onPresetSelected: (Set<String>) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = selectedIds == preset.categoryIds,
                onClick = { onPresetSelected(preset.categoryIds) },
                label = { Text(preset.label) },
                colors = companionFilterChipColors(),
            )
        }
    }
}

@Composable
private fun companionFilterChipColors() =
    FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        containerColor = MaterialTheme.colorScheme.surface,
        labelColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
internal fun OsmPoiCategorySelectionGroup(
    options: List<OsmPoiImportCategory>,
    selectedIds: Set<String>,
    onCheckedChange: (categoryId: String, isChecked: Boolean) -> Unit,
) {
    options.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { category ->
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = category.id in selectedIds,
                        onCheckedChange = { checked ->
                            onCheckedChange(category.id, checked)
                        },
                    )
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
