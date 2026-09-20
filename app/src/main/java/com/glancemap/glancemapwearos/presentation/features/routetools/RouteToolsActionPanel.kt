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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
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
    if (!visible) return

    var showCoordinateEditor by remember(visible) { mutableStateOf(false) }
    var showPoiSearchDialog by remember(visible) { mutableStateOf(false) }
    var coordinateStep by remember(visible) { mutableStateOf(CoordinateStep.ONE_THOUSANDTH) }
    val shouldUsePreflightPopup =
        preflightMessage == "Routing packs missing for selected map" ||
            preflightMessage == "Routing data missing"
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
        title = "GPX Tools",
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
                text = "Activate exactly one GPX first to use this tool.",
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
                title = "Offline POI",
                value = poiSearchSummary(poiSearchState),
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
                        "Create loop"
                    } else {
                        "Pick start on map"
                    },
                )
            }
        }

        Text(
            text = options.activeSummary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.84f),
        )

        Text(
            text = routeToolsHintText(options),
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
            title = "GPX tools settings",
            value = "Creation defaults",
            icon = Icons.Filled.Folder,
            iconTint = Color(0xFFFFD54F),
            onClick = onOpenGpxToolsSettings,
        )
    }

    if (!preflightMessage.isNullOrBlank() && shouldUsePreflightPopup && showPreflightPopup) {
        WearActionDialog(
            visible = true,
            title = "Routing Missing",
            message = preflightMessage,
            confirmText = "OK",
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
        title = "Route type",
        selectedValue = selected,
        options = routeStylePickerOptionsFor(selected),
        onSelect = onSelected,
    ) { openPicker ->
        RouteSettingRow(
            title = "Route type",
            value = selected.title,
            onClick = openPicker,
        )
    }
    Text(
        text = selected.summary,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        color = Color.White.copy(alpha = 0.72f),
    )
}
