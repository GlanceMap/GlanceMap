@file:Suppress("MatchingDeclarationName") // The reusable callback contract belongs beside its scaffold.

package com.glancemap.glancemapcompanionapp.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.layout.CompanionWidthClass
import com.glancemap.glancemapcompanionapp.layout.LocalCompanionLayoutContext

/** Hosts the map and every tool panel without letting a panel own map renderer state. */
internal data class MapToolScaffoldActions(
    val onToolSelected: (MapTool) -> Unit,
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolScaffold(
    state: MapToolPanelState,
    actions: MapToolScaffoldActions,
    mapContent: @Composable () -> Unit,
    panelContent: @Composable (MapTool) -> Unit,
) {
    val isWide = LocalCompanionLayoutContext.current.widthClass != CompanionWidthClass.COMPACT
    Column(modifier = Modifier.fillMaxSize()) {
        when (state.mode) {
            MapToolPanelMode.CLOSED -> Box(modifier = Modifier.weight(1f)) { mapContent() }
            MapToolPanelMode.SPLIT -> {
                val tool = state.activeTool ?: return@Column
                if (isWide) {
                    Row(modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.weight(1.2f).fillMaxHeight()) { mapContent() }
                        mapToolPanelSurface(
                            tool = tool,
                            isExpanded = false,
                            actions = actions,
                            modifier = Modifier.weight(0.8f).fillMaxHeight(),
                            content = panelContent,
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.weight(1.1f).fillMaxWidth()) { mapContent() }
                        mapToolPanelSurface(
                            tool = tool,
                            isExpanded = false,
                            actions = actions,
                            modifier = Modifier.weight(0.9f).fillMaxWidth(),
                            content = panelContent,
                        )
                    }
                }
            }
            MapToolPanelMode.EXPANDED -> {
                val tool = state.activeTool ?: return@Column
                Box(modifier = Modifier.weight(1f)) {
                    mapContent()
                    mapToolPanelSurface(
                        tool = tool,
                        isExpanded = true,
                        actions = actions,
                        modifier = Modifier.fillMaxSize(),
                        content = panelContent,
                    )
                }
            }
        }
        mapToolBar(activeTool = state.activeTool, onToolSelected = actions.onToolSelected)
    }
}

@Composable
private fun mapToolPanelSurface(
    tool: MapTool,
    isExpanded: Boolean,
    actions: MapToolScaffoldActions,
    modifier: Modifier,
    content: @Composable (MapTool) -> Unit,
) {
    Surface(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(tool.titleResource()), modifier = Modifier.weight(1f))
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content(tool) }
        }
    }
}

@Composable
private fun mapToolBar(
    activeTool: MapTool?,
    onToolSelected: (MapTool) -> Unit,
) {
    NavigationBar {
        MapTool.entries.forEach { tool ->
            NavigationBarItem(
                selected = activeTool == tool,
                onClick = { onToolSelected(tool) },
                icon = {
                    Icon(
                        imageVector = tool.icon(),
                        contentDescription = stringResource(tool.titleResource()),
                    )
                },
                label = { Text(stringResource(tool.titleResource())) },
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
