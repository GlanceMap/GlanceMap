package com.glancemap.glancemapwearos.presentation.features.routetools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.poi.PoiSearchUiState
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsOptionPickerHost
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearToolPanelDialog
import org.mapsforge.core.model.LatLong

@Composable
internal fun RouteToolsActionPanel(
    visible: Boolean,
    canModifyActiveGpx: Boolean,
    coordinateSeed: LatLong?,
    poiSearchState: PoiSearchUiState,
    options: RouteToolOptions,
    preflightMessage: String?,
    onOptionsChange: (RouteToolOptions) -> Unit,
    onSearchPoi: (String) -> Unit,
    onClearPoiSearch: () -> Unit,
    onDismiss: () -> Unit,
    onStartSelection: (RouteToolSession) -> Unit,
    onOpenGpxToolsSettings: () -> Unit,
) {
    val resources = LocalResources.current
    if (!visible) return

    var showCoordinateEditor by remember(visible) { mutableStateOf(false) }
    var showPoiSearchDialog by remember(visible) { mutableStateOf(false) }
    var coordinateStep by remember(visible) { mutableStateOf(CoordinateStep.ONE_THOUSANDTH) }
    val shouldUsePreflightPopup =
        preflightMessage == resources.getString(R.string.gpx_routing_packs_missing_for_selected_map) ||
            preflightMessage == resources.getString(R.string.gpx_routing_data_missing)
    var showPreflightPopup by remember(preflightMessage) { mutableStateOf(shouldUsePreflightPopup) }
    val startSelection: (RouteToolSession) -> Unit = startSelection@{ session ->
        val canStart = !session.options.requiresSingleActiveGpx || canModifyActiveGpx
        if (!canStart) return@startSelection
        onOptionsChange(session.options)
        onStartSelection(session)
    }
    val openCoordinateEditor: (RouteToolOptions) -> Unit = openCoordinateEditor@{ updatedOptions ->
        val seededOptions = updatedOptions.seedCoordinateEndpoints(coordinateSeed)
        onOptionsChange(seededOptions)
        showCoordinateEditor = true
    }

    WearToolPanelDialog(
        visible = true,
        title = stringResource(R.string.gpx_gpx_tools),
        onDismiss = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.90f),
    ) {
        if (!preflightMessage.isNullOrBlank() && !shouldUsePreflightPopup) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFFF6E4A6).copy(alpha = 0.96f),
                            RoundedCornerShape(14.dp),
                        ).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = preflightMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF3C2500),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        RouteToolKindSelector(
            selected = options.toolKind,
            onSelected = { kind ->
                onOptionsChange(options.copy(toolKind = kind))
            },
        )

        if (!canModifyActiveGpx && options.requiresSingleActiveGpx) {
            Text(
                text = stringResource(R.string.gpx_activate_exactly_one_gpx_first_to_use_this_tool),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC80),
                textAlign = TextAlign.Center,
            )
        }

        RouteActionSelector(
            options = options,
            canModifyActiveGpx = canModifyActiveGpx,
            coordinateSeed = coordinateSeed,
            onOptionsChange = onOptionsChange,
            onStartSelection = startSelection,
            onOpenCoordinateEditor = openCoordinateEditor,
            onOpenPoiSearchDialog = { showPoiSearchDialog = true },
        )

        if (options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.SEARCH
        ) {
            RouteSettingRow(
                title = stringResource(R.string.gpx_offline_poi),
                value = poiSearchSummary(poiSearchState, resources = resources),
                onClick = { showPoiSearchDialog = true },
            )
        }

        if (options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.LOOP_AROUND_HERE
        ) {
            LoopTargetModeSelector(
                selected = options.loopTargetMode,
                onSelected = { targetMode ->
                    onOptionsChange(options.copy(loopTargetMode = targetMode))
                },
            )
            LoopTargetEditor(
                targetMode = options.loopTargetMode,
                distanceKm = options.loopDistanceKm,
                durationMinutes = options.loopDurationMinutes,
                onDecrease = {
                    onOptionsChange(
                        when (options.loopTargetMode) {
                            LoopTargetMode.DISTANCE -> {
                                options.copy(
                                    loopDistanceKm =
                                        (options.loopDistanceKm - 1)
                                            .coerceAtLeast(2),
                                )
                            }

                            LoopTargetMode.TIME -> {
                                options.copy(
                                    loopDurationMinutes =
                                        (options.loopDurationMinutes - 15)
                                            .coerceAtLeast(30),
                                )
                            }
                        },
                    )
                },
                onIncrease = {
                    onOptionsChange(
                        when (options.loopTargetMode) {
                            LoopTargetMode.DISTANCE -> {
                                options.copy(
                                    loopDistanceKm =
                                        (options.loopDistanceKm + 1)
                                            .coerceAtMost(60),
                                )
                            }

                            LoopTargetMode.TIME -> {
                                options.copy(
                                    loopDurationMinutes =
                                        (options.loopDurationMinutes + 15)
                                            .coerceAtMost(480),
                                )
                            }
                        },
                    )
                },
            )
            LoopStartModeSelector(
                selected = options.loopStartMode,
                onSelected = { startMode ->
                    onOptionsChange(options.copy(loopStartMode = startMode))
                },
            )
            Button(
                onClick = {
                    startSelection(RouteToolSession(options = options))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (options.loopStartMode == LoopStartMode.CURRENT_LOCATION) {
                        stringResource(R.string.gpx_create_loop)
                    } else {
                        stringResource(R.string.gpx_pick_start_on_map)
                    },
                )
            }
        }

        Text(
            text = stringResource(options.activeSummaryRes),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.84f),
        )

        Text(
            text = routeToolsHintText(options, resources = resources),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.68f),
        )

        RouteStylePickerRow(
            selected = options.routeStyle,
            onSelected = { routeStyle -> onOptionsChange(options.copy(routeStyle = routeStyle)) },
        )

        if (options.toolKind == RouteToolKind.MODIFY) {
            RouteSaveBehaviorSelector(
                selected = options.saveBehavior,
                onSelected = { behavior ->
                    onOptionsChange(options.copy(saveBehavior = behavior))
                },
            )
        }

        RouteSettingRow(
            title = stringResource(R.string.gpx_gpx_tools_settings),
            value = stringResource(R.string.gpx_creation_defaults),
            icon = Icons.Filled.Folder,
            iconTint = Color(0xFFFFD54F),
            onClick = onOpenGpxToolsSettings,
        )
    }

    if (!preflightMessage.isNullOrBlank() && shouldUsePreflightPopup && showPreflightPopup) {
        WearActionDialog(
            visible = true,
            title = stringResource(R.string.gpx_routing_missing),
            message = preflightMessage,
            confirmText = stringResource(R.string.common_ok),
            onConfirm = { showPreflightPopup = false },
            onDismissRequest = { showPreflightPopup = false },
        )
    }

    RouteEndpointEditorDialog(
        visible = showCoordinateEditor,
        options = options,
        coordinateSeed = coordinateSeed,
        preflightMessage = preflightMessage,
        step = coordinateStep,
        onOptionsChange = onOptionsChange,
        onStepChange = { coordinateStep = it },
        onCreateRoute = {
            val seededOptions = options.seedCoordinateEndpoints(coordinateSeed)
            onOptionsChange(seededOptions)
            startSelection(RouteToolSession(options = seededOptions))
        },
        onDismiss = { showCoordinateEditor = false },
    )

    RoutePoiSearchDialog(
        visible = showPoiSearchDialog,
        state = poiSearchState,
        onDismiss = {
            showPoiSearchDialog = false
            onClearPoiSearch()
        },
        onSearch = onSearchPoi,
        onSelectResult = { result ->
            showPoiSearchDialog = false
            onClearPoiSearch()
            startSelection(
                RouteToolSession(
                    options = options,
                    destination = LatLong(result.lat, result.lon),
                ),
            )
        },
    )
}

@Composable
private fun RouteStylePickerRow(
    selected: RouteStylePreset,
    onSelected: (RouteStylePreset) -> Unit,
) {
    SettingsOptionPickerHost(
        title = stringResource(R.string.gpx_route_type),
        selectedValue = selected,
        options = routeStylePickerOptionsFor(selected).map { (value, labelRes) -> value to stringResource(labelRes) },
        onSelect = onSelected,
    ) { openPicker ->
        RouteSettingRow(
            title = stringResource(R.string.gpx_route_type),
            value = stringResource(selected.titleRes),
            onClick = openPicker,
        )
    }
    Text(
        text = stringResource(selected.summaryRes),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        color = Color.White.copy(alpha = 0.72f),
    )
}
