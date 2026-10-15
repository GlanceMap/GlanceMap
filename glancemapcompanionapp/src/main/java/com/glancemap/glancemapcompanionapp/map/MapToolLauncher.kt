package com.glancemap.glancemapcompanionapp.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun mapToolLauncher(
    presentation: MapToolScaffoldState,
    actions: MapToolScaffoldActions,
    modifier: Modifier = Modifier,
) {
    val expanded = presentation.launcherExpanded
    val selectTool: (MapTool) -> Unit = { tool ->
        if (expanded) actions.onToggleLauncher()
        actions.onToolSelected(tool)
    }
    val openScreen: (() -> Unit) -> Unit = { open ->
        if (expanded) actions.onToggleLauncher()
        open()
    }
    Box(modifier) {
        if (expanded) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 72.dp)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                mapShortcut(R.string.map_tool_shortcut_add_poi, Icons.Filled.Place) { openScreen(actions.onAddPoi) }
                mapShortcut(R.string.map_tool_shortcut_rec, Icons.Filled.FiberManualRecord) {
                    openScreen(actions.onOpenRecording)
                }
                mapShortcut(R.string.map_tool_shortcut_gpx, Icons.Filled.Route) { openScreen(actions.onOpenGpxTools) }
            }
        }
        NavigationBar(Modifier.align(Alignment.BottomCenter).height(64.dp).testTag("map-tool-toolbar")) {
            NavigationBarItem(
                selected = !expanded && presentation.panel.activeTool == MapTool.LIBRARY,
                onClick = { selectTool(MapTool.LIBRARY) },
                icon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                label = { Text(stringResource(R.string.library_title)) },
            )
            NavigationBarItem(
                selected = !expanded && presentation.panel.activeTool == MapTool.LAYER,
                onClick = { selectTool(MapTool.LAYER) },
                icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                label = { Text(stringResource(R.string.map_navigation_layers)) },
            )
            NavigationBarItem(
                selected = !expanded && presentation.panel.activeTool == MapTool.HIKE,
                onClick = { selectTool(MapTool.HIKE) },
                icon = { Icon(Icons.Filled.Today, contentDescription = null) },
                label = { Text(stringResource(R.string.map_navigation_hike)) },
            )
            NavigationBarItem(
                selected = expanded || presentation.panel.activeTool == MapTool.GPX_TOOLS,
                onClick = actions.onToggleLauncher,
                icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                label = { Text(stringResource(R.string.map_tool_launcher_label)) },
            )
        }
    }
}

@Composable
private fun mapShortcut(
    @StringRes label: Int,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ExtendedFloatingActionButton(
        modifier = Modifier.testTag("map-shortcut-$label"),
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(stringResource(label)) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
