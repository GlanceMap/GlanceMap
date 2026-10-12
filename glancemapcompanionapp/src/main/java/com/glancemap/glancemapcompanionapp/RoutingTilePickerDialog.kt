@file:Suppress("FunctionNaming", "LongParameterList")

package com.glancemap.glancemapcompanionapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routing.BRouterTileMath

private const val DEFAULT_ROUTING_PICKER_BBOX = "5.00,43.00,10.00,48.00"

@Composable
internal fun RoutingTilePickerDialog(
    initialBbox: String,
    watchInstalledCoverageAreas: List<WatchInstalledCoverageArea>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val textResources = LocalResources.current
    val context = LocalContext.current
    val nativeMapPickerAvailable = remember(context) { context.hasNativeMapPickerGraphicsSupport() }
    if (!nativeMapPickerAvailable) {
        NativeMapPickerUnavailableDialog(
            title = textResources.getString(R.string.routing_picker_routing_picker_unavailable),
            onDismiss = onDismiss,
        )
        return
    }

    val initialPickerBbox = remember(initialBbox) { resolveInitialRoutingPickerBbox(initialBbox) }
    val hasUsableInitialBbox = remember(initialBbox) { hasUsableInitialRoutingBbox(initialBbox) }
    var centerOnLocation by remember(initialBbox) { mutableStateOf(!hasUsableInitialBbox) }
    val locationAllowed = rememberMapPickerLocationAllowed(centerOnLocation)
    val useLocationDefault = centerOnLocation && locationAllowed
    var selectedBbox by remember(initialPickerBbox) { mutableStateOf("") }
    var selectedTiles by remember(initialPickerBbox) { mutableStateOf(emptyList<String>()) }
    var mapReady by remember { mutableStateOf(false) }

    LaunchedEffect(initialPickerBbox, useLocationDefault) {
        mapReady = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true,
            ),
    ) {
        RoutingTilePickerSurface(
            useLocationDefault = useLocationDefault,
            state = RoutingTilePickerState(selectedBbox, selectedTiles, centerOnLocation, mapReady),
            actions =
                RoutingTilePickerActions(
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    onReady = { mapReady = true },
                    onCenterOnLocationChanged = { checked -> centerOnLocation = checked },
                    onSelectionChanged = { bbox, tiles ->
                        selectedBbox = bbox
                        selectedTiles = tiles
                    },
                ),
            initialBbox = initialPickerBbox,
            watchInstalledCoverageAreas = watchInstalledCoverageAreas,
        )
    }
}

private data class RoutingTilePickerState(
    val selectedBbox: String,
    val selectedTiles: List<String>,
    val centerOnLocation: Boolean,
    val mapReady: Boolean,
)

private data class RoutingTilePickerActions(
    val onDismiss: () -> Unit,
    val onConfirm: (String) -> Unit,
    val onReady: () -> Unit,
    val onCenterOnLocationChanged: (Boolean) -> Unit,
    val onSelectionChanged: (bbox: String, tiles: List<String>) -> Unit,
)

@Composable
private fun RoutingTilePickerSurface(
    useLocationDefault: Boolean,
    state: RoutingTilePickerState,
    actions: RoutingTilePickerActions,
    initialBbox: String,
    watchInstalledCoverageAreas: List<WatchInstalledCoverageArea>,
) {
    val textResources = LocalResources.current
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        modifier =
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f)
                .heightIn(max = 720.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Text(
                textResources.getString(R.string.routing_area_method_tile_picker),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            MapPickerControlRow(
                checked = state.centerOnLocation,
                onCheckedChange = actions.onCenterOnLocationChanged,
                label = textResources.getString(R.string.map_picker_center_on_my_location),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(2.dp))
            MapPickerHelpText(
                text = textResources.getString(R.string.routing_picker_pan_the_map_then_tap_tiles_to),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            RoutingTilePickerMap(
                initialBbox = initialBbox,
                useLocationDefault = useLocationDefault,
                watchInstalledCoverageAreas = watchInstalledCoverageAreas,
                state = state,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.height(10.dp))
            RoutingTilePickerFooter(state, actions)
        }
    }
}

@Composable
private fun RoutingTilePickerMap(
    initialBbox: String,
    useLocationDefault: Boolean,
    watchInstalledCoverageAreas: List<WatchInstalledCoverageArea>,
    state: RoutingTilePickerState,
    actions: RoutingTilePickerActions,
    modifier: Modifier = Modifier,
) {
    val initialBounds =
        remember(initialBbox) {
            MapPickerBounds.parseOrNull(initialBbox) ?: defaultRoutingPickerBounds()
        }
    var pickerView by remember(useLocationDefault, initialBbox) { mutableStateOf<MapLibreRoutingTilePickerView?>(null) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        key(useLocationDefault, initialBbox) {
            AndroidView(
                factory = { context ->
                    MapLibreRoutingTilePickerView(
                        context = context,
                        initialBounds = initialBounds,
                        useLocationDefault = useLocationDefault,
                        watchInstalledCoverageAreas = watchInstalledCoverageAreas,
                        onSelectionChanged = actions.onSelectionChanged,
                        onReady = actions.onReady,
                    ).also { view -> pickerView = view }
                },
                update = { view ->
                    view.setWatchInstalledCoverageAreas(watchInstalledCoverageAreas)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!state.mapReady) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        if (state.mapReady) {
            RoutingTilePickerMapOverlays(pickerView)
        }
    }
}

@Composable
private fun BoxScope.RoutingTilePickerMapOverlays(
    pickerView: MapLibreRoutingTilePickerView?,
) {
    val textResources = LocalResources.current
    MapPickerCoverageLegend(
        text = textResources.getString(R.string.routing_picker_darker_square_routing_already_on_watch),
        color = Color(0x665A80FF),
        modifier =
            Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp),
    )
    MapPickerZoomControls(
        onZoomIn = { pickerView?.zoomIn() },
        onZoomOut = { pickerView?.zoomOut() },
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp),
    )
}

@Composable
private fun RoutingTilePickerFooter(
    state: RoutingTilePickerState,
    actions: RoutingTilePickerActions,
) {
    val textResources = LocalResources.current
    Text(
        textResources.getString(R.string.routing_picker_selected_routing_packs_value, state.selectedTiles.size),
        style = MaterialTheme.typography.labelSmall,
    )
    Text(
        state.selectedTiles.joinToString(", ").ifBlank {
            textResources.getString(R.string.routing_picker_tap_tiles_on_the_map)
        },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = actions.onDismiss) { Text(textResources.getString(R.string.common_action_cancel)) }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = { actions.onConfirm(state.selectedBbox) },
            enabled = state.selectedTiles.isNotEmpty(),
        ) {
            Text(textResources.getString(R.string.routing_picker_use_tiles))
        }
    }
}

private fun resolveInitialRoutingPickerBbox(initialBbox: String): String =
    runCatching {
        val parsed = BRouterTileMath.parseBbox(initialBbox)
        val tileCount = BRouterTileMath.tileFileNamesForBbox(parsed).size
        if (tileCount in 1..4) {
            parsed.asQueryString()
        } else {
            DEFAULT_ROUTING_PICKER_BBOX
        }
    }.getOrElse { DEFAULT_ROUTING_PICKER_BBOX }

private fun hasUsableInitialRoutingBbox(initialBbox: String): Boolean =
    runCatching {
        val parsed = BRouterTileMath.parseBbox(initialBbox)
        BRouterTileMath.tileFileNamesForBbox(parsed).size in 1..4
    }.getOrDefault(false)

private fun defaultRoutingPickerBounds(): MapPickerBounds {
    val fallback = MapPickerBounds.defaultAround(45.5, 7.5)
    return MapPickerBounds.parseOrNull(DEFAULT_ROUTING_PICKER_BBOX) ?: fallback
}
