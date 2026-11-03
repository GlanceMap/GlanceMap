package com.glancemap.glancemapcompanionapp.map

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun mapToolLauncher(
    presentation: MapToolScaffoldState,
    actions: MapToolScaffoldActions,
    modifier: Modifier = Modifier,
) {
    val expanded = presentation.launcherExpanded
    val startRecording = rememberPhoneRecordingStartAction()
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
                        .padding(end = 12.dp, bottom = mapToolLauncherHeight + 8.dp)
                        .heightIn(max = 360.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                mapShortcut(R.string.map_tool_shortcut_poi, Icons.Filled.Place) { openScreen(actions.onAddPoi) }
                mapShortcut(R.string.map_tool_shortcut_rec, Icons.Filled.FiberManualRecord) {
                    openScreen(startRecording)
                }
                mapShortcut(R.string.map_tool_shortcut_gpx, Icons.Filled.Route) { openScreen(actions.onOpenGpxTools) }
            }
        }
        NavigationBar(
            Modifier.align(Alignment.BottomCenter).height(mapToolLauncherHeight).testTag("map-tool-toolbar"),
        ) {
            NavigationBarItem(
                selected = !expanded && presentation.panel.activeTool == MapTool.LIBRARY,
                onClick = { selectTool(MapTool.LIBRARY) },
                icon = {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.offset(y = 2.dp))
                },
                label = { Text(stringResource(R.string.library_title)) },
            )
            mapLayersNavigationItem(
                selected = !expanded && presentation.panel.activeTool == MapTool.LAYER,
                layersActive = presentation.layersActive,
            ) { selectTool(MapTool.LAYER) }
            NavigationBarItem(
                selected = !expanded && presentation.panel.activeTool == MapTool.HIKE,
                onClick = { selectTool(MapTool.HIKE) },
                icon = { Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.offset(y = 2.dp)) },
                label = { Text(stringResource(R.string.map_navigation_hike)) },
            )
            NavigationBarItem(
                selected = expanded || presentation.panel.activeTool == MapTool.GPX_TOOLS,
                onClick = actions.onToggleLauncher,
                icon = { Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.offset(y = 2.dp)) },
                label = { Text(stringResource(R.string.map_tool_launcher_label)) },
            )
        }
    }
}

@Composable
private fun RowScope.mapLayersNavigationItem(
    selected: Boolean,
    layersActive: Boolean,
    onClick: () -> Unit,
) {
    val activeLayersDescription = stringResource(R.string.map_layers_active)
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        modifier =
            Modifier.semantics {
                if (layersActive) stateDescription = activeLayersDescription
            },
        icon = {
            Icon(
                Icons.Filled.Layers,
                contentDescription = null,
                modifier =
                    Modifier
                        .offset(y = 2.dp)
                        .background(
                            color =
                                if (layersActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            shape = CircleShape,
                        ).padding(horizontal = 6.dp),
                tint =
                    if (layersActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        LocalContentColor.current
                    },
            )
        },
        label = {
            Text(
                stringResource(R.string.map_navigation_layers),
                color =
                    if (layersActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
            )
        },
    )
}

@Composable
private fun mapShortcut(
    @StringRes label: Int,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("map-shortcut-$label"),
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            // Compact chip look; the clickable Surface still keeps the 48dp minimum touch target.
            modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
