@file:Suppress("MatchingDeclarationName") // The reusable callback contract belongs beside its scaffold.

package com.glancemap.glancemapcompanionapp.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.layout.CompanionWidthClass
import com.glancemap.glancemapcompanionapp.layout.LocalCompanionLayoutContext

/** Hosts the map and every tool panel without letting a panel own map renderer state. */
internal data class MapToolScaffoldActions(
    val onToolSelected: (MapTool) -> Unit,
    val onToggleLauncher: () -> Unit,
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "FunctionNaming", // Public Compose entry points follow the project's screen naming convention.
) // One fixed layout pass keeps the Android map host in one composition slot.
internal fun MapToolScaffold(
    state: MapToolPanelState,
    launcherExpanded: Boolean,
    actions: MapToolScaffoldActions,
    mapContent: @Composable () -> Unit,
    panelContent: @Composable (MapTool, MapToolContentMode) -> Unit,
) {
    val isWide = LocalCompanionLayoutContext.current.widthClass != CompanionWidthClass.COMPACT
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            // Keep this slot unconditional: Android map views retain their composition identity as panels resize.
            mapToolMapSurface(
                launcherExpanded = launcherExpanded,
                activeTool = state.activeTool,
                actions = actions,
                modifier = Modifier,
                mapContent = mapContent,
            )
            Box {
                if (state.activeTool != null && state.mode != MapToolPanelMode.CLOSED) {
                    mapToolPanelSurface(
                        state = state,
                        isExpanded = state.mode == MapToolPanelMode.EXPANDED,
                        actions = actions,
                        modifier = Modifier.fillMaxSize(),
                        content = panelContent,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val map = measurables[0]
        val panel = measurables[1]
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val panelOpen = state.activeTool != null && state.mode != MapToolPanelMode.CLOSED
        val mapWidth =
            if (panelOpen && state.mode == MapToolPanelMode.SPLIT && isWide) {
                (width * MAP_SPLIT_FRACTION).toInt()
            } else {
                width
            }
        val mapHeight =
            if (panelOpen && state.mode == MapToolPanelMode.SPLIT && !isWide) {
                (height * MAP_SPLIT_FRACTION).toInt()
            } else {
                height
            }
        val mapPlaceable = map.measure(Constraints.fixed(mapWidth, mapHeight))
        val panelPlaceable =
            panel.measure(
                Constraints.fixed(
                    if (panelOpen && state.mode == MapToolPanelMode.SPLIT && isWide) width - mapWidth else width,
                    if (panelOpen && state.mode == MapToolPanelMode.SPLIT && !isWide) height - mapHeight else height,
                ),
            )

        layout(width, height) {
            mapPlaceable.placeRelative(0, 0)
            if (panelOpen) {
                panelPlaceable.placeRelative(
                    if (state.mode == MapToolPanelMode.SPLIT && isWide) mapWidth else 0,
                    if (state.mode == MapToolPanelMode.SPLIT && !isWide) mapHeight else 0,
                )
            }
        }
    }
}

private const val MAP_SPLIT_FRACTION = 0.6f

@Composable
private fun mapToolMapSurface(
    launcherExpanded: Boolean,
    activeTool: MapTool?,
    actions: MapToolScaffoldActions,
    modifier: Modifier,
    mapContent: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        mapContent()
        mapToolLauncher(
            expanded = launcherExpanded,
            activeTool = activeTool,
            onToolSelected = actions.onToolSelected,
            onToggle = actions.onToggleLauncher,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
        )
    }
}

@Composable
private fun mapToolPanelSurface(
    state: MapToolPanelState,
    isExpanded: Boolean,
    actions: MapToolScaffoldActions,
    modifier: Modifier,
    content: @Composable (MapTool, MapToolContentMode) -> Unit,
) {
    val tool = state.activeTool ?: return
    Surface(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(tool.titleResource(state.contentMode)),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = if (isExpanded) actions.onCollapse else actions.onExpand) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                        contentDescription =
                            stringResource(
                                if (isExpanded) {
                                    R.string.map_tool_action_collapse_content_description
                                } else {
                                    R.string.map_tool_action_expand_content_description
                                },
                            ),
                    )
                }
                IconButton(onClick = actions.onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_action_close),
                    )
                }
            }
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content(tool, state.contentMode) }
        }
    }
}

@Composable
private fun mapToolLauncher(
    expanded: Boolean,
    activeTool: MapTool?,
    onToolSelected: (MapTool) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (expanded) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 88.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                secondaryMapTools.reversed().forEach { tool ->
                    ExtendedFloatingActionButton(
                        onClick = { onToolSelected(tool) },
                        icon = {
                            Icon(
                                imageVector = tool.icon(),
                                contentDescription = stringResource(tool.titleResource()),
                            )
                        },
                        text = { Text(stringResource(tool.titleResource())) },
                        containerColor =
                            if (activeTool == tool) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                    )
                }
            }
        }
        NavigationBar(modifier = Modifier.align(Alignment.BottomCenter)) {
            primaryMapTools.forEach { tool ->
                NavigationBarItem(
                    selected = activeTool == tool,
                    onClick = { onToolSelected(tool) },
                    icon = { Icon(imageVector = tool.icon(), contentDescription = null) },
                    label = { Text(stringResource(tool.titleResource())) },
                    alwaysShowLabel = true,
                )
            }
            NavigationBarItem(
                selected = expanded || activeTool == MapTool.SETTINGS,
                onClick = onToggle,
                icon = { Icon(imageVector = Icons.Filled.Build, contentDescription = null) },
                label = { Text(stringResource(R.string.map_tool_launcher_label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

private fun MapTool.icon(): ImageVector =
    when (this) {
        MapTool.POI -> Icons.Filled.Place
        MapTool.GPX -> Icons.Filled.Route
        MapTool.MAPS -> Icons.Filled.Map
        MapTool.SETTINGS -> Icons.Filled.Settings
    }

@StringRes
internal fun MapTool.titleResource(): Int =
    when (this) {
        MapTool.POI -> R.string.map_tool_poi_title
        MapTool.GPX -> R.string.map_tool_gpx_title
        MapTool.MAPS -> R.string.map_tool_maps_title
        MapTool.SETTINGS -> R.string.map_tool_settings_title
    }

@StringRes
internal fun MapTool.titleResource(contentMode: MapToolContentMode): Int =
    if (contentMode == MapToolContentMode.FEATURE_SETTINGS) {
        when (this) {
            MapTool.POI -> R.string.map_tool_poi_settings_title
            MapTool.GPX -> R.string.map_tool_gpx_settings_title
            MapTool.MAPS -> R.string.map_tool_maps_settings_title
            MapTool.SETTINGS -> titleResource()
        }
    } else {
        titleResource()
    }
