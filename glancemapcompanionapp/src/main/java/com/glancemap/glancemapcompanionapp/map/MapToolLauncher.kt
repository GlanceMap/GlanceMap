package com.glancemap.glancemapcompanionapp.map

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SpatialTracking
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

private enum class MapLauncherGroup { LIBRARY, WATCH, TOOLS }

@Composable
internal fun mapToolLauncher(
    presentation: MapToolScaffoldState,
    actions: MapToolScaffoldActions,
    navigation: MapNavigationActions,
    modifier: Modifier = Modifier,
) {
    val expanded = presentation.launcherExpanded
    val activeTool = presentation.panel.activeTool
    var group by rememberSaveable { mutableStateOf(MapLauncherGroup.TOOLS) }
    val toggleGroup: (MapLauncherGroup) -> Unit = { target ->
        if (!expanded || group == target) actions.onToggleLauncher()
        group = target
    }
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
                when (group) {
                    MapLauncherGroup.LIBRARY -> libraryShortcuts(selectTool, navigation, openScreen)
                    MapLauncherGroup.WATCH -> watchShortcuts(navigation, presentation.liveHikeSyncEnabled, openScreen)
                    MapLauncherGroup.TOOLS -> {
                        mapShortcut(R.string.map_tool_shortcut_add_poi, Icons.Filled.Place) {
                            openScreen(actions.onAddPoi)
                        }
                        mapShortcut(R.string.map_tool_shortcut_gpx, Icons.Filled.Route) {
                            openScreen(actions.onOpenGpxTools)
                        }
                    }
                }
            }
        }
        mapBottomBar(expanded, group, activeTool, toggleGroup, selectTool)
    }
}

@Composable
private fun BoxScope.mapBottomBar(
    expanded: Boolean,
    group: MapLauncherGroup,
    activeTool: MapTool?,
    onGroup: (MapLauncherGroup) -> Unit,
    onTool: (MapTool) -> Unit,
) {
    NavigationBar(Modifier.align(Alignment.BottomCenter).height(64.dp).testTag("map-tool-toolbar")) {
        NavigationBarItem(
            selected = (expanded && group == MapLauncherGroup.LIBRARY) || (!expanded && activeTool in libraryMapTools),
            onClick = { onGroup(MapLauncherGroup.LIBRARY) },
            icon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
            label = { Text(stringResource(R.string.library_title)) },
        )
        NavigationBarItem(
            selected = !expanded && activeTool == MapTool.LAYER,
            onClick = { onTool(MapTool.LAYER) },
            icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
            label = { Text(stringResource(R.string.map_navigation_layers)) },
        )
        NavigationBarItem(
            selected = expanded && group == MapLauncherGroup.WATCH,
            onClick = { onGroup(MapLauncherGroup.WATCH) },
            icon = { Icon(Icons.Filled.Watch, contentDescription = null) },
            label = { Text(stringResource(R.string.map_navigation_watch)) },
        )
        NavigationBarItem(
            selected = expanded && group == MapLauncherGroup.TOOLS,
            onClick = { onGroup(MapLauncherGroup.TOOLS) },
            icon = { Icon(Icons.Filled.Build, contentDescription = null) },
            label = { Text(stringResource(R.string.map_tool_launcher_label)) },
        )
    }
}

@Composable
private fun libraryShortcuts(
    onTool: (MapTool) -> Unit,
    navigation: MapNavigationActions,
    openScreen: (() -> Unit) -> Unit,
) {
    libraryMapTools.forEach { tool ->
        mapShortcut(tool.titleResource(), tool.icon()) { onTool(tool) }
    }
    mapShortcut(R.string.library_title, Icons.Filled.FolderOpen) { openScreen(navigation.onOpenLibrary) }
}

@Composable
private fun watchShortcuts(
    navigation: MapNavigationActions,
    syncEnabled: Boolean,
    openScreen: (() -> Unit) -> Unit,
) {
    val syncLabel = stringResource(R.string.menu_live_hike_sync)
    mapShortcut(R.string.transfer_title_send_to_watch, Icons.AutoMirrored.Filled.SendToMobile) {
        openScreen(navigation.onSendToWatch)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clickable { openScreen(navigation.onLiveHike) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.SpatialTracking, contentDescription = null)
                Text(stringResource(R.string.menu_live_hike))
            }
            Switch(
                checked = syncEnabled,
                onCheckedChange = navigation.onLiveHikeSyncChanged,
                modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = syncLabel },
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
