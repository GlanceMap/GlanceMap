@file:Suppress("MatchingDeclarationName") // The reusable callback contract belongs beside its scaffold.

package com.glancemap.glancemapcompanionapp.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.layout.CompanionWidthClass
import com.glancemap.glancemapcompanionapp.layout.LocalCompanionLayoutContext
import kotlin.math.roundToInt

private val mapToolLauncherHeight = 64.dp

internal data class MapToolScaffoldState(
    val panel: MapToolPanelState,
    val launcherExpanded: Boolean = false,
    val liveHikeSyncEnabled: Boolean = false,
    val fullScreenPage: Boolean = false,
)

/** Hosts the map and every tool panel without letting a panel own map renderer state. */
internal data class MapToolScaffoldActions(
    val onToolSelected: (MapTool) -> Unit,
    val onToggleLauncher: () -> Unit,
    val onAddPoi: () -> Unit,
    val onOpenGpxTools: () -> Unit,
    val onCycleSize: () -> Unit,
    val onResize: (Float) -> Unit,
    val onResizeFinished: () -> Unit,
    val onBack: () -> Unit,
    val onClose: () -> Unit,
    val onFeatureSettings: () -> Unit,
)

@Composable
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "FunctionNaming", // Public Compose entry points follow the project's screen naming convention.
) // One fixed layout pass keeps the Android map host in one composition slot.
internal fun MapToolScaffold(
    presentation: MapToolScaffoldState,
    actions: MapToolScaffoldActions,
    mapContent: @Composable () -> Unit,
    panelContent: @Composable (MapTool, MapToolContentMode, MapToolFeatureSettingsSection) -> Unit,
    navigation: MapNavigationActions = MapNavigationActions(),
) {
    val state = presentation.panel
    val isWide = LocalCompanionLayoutContext.current.widthClass != CompanionWidthClass.COMPACT
    var availableSize by remember { mutableStateOf(IntSize.Zero) }
    val axisSize = if (isWide) availableSize.width else availableSize.height
    val launcherHeight = with(LocalDensity.current) { mapToolLauncherHeight.roundToPx() }
    val minPanelSize =
        with(LocalDensity.current) {
            (if (isWide) 200.dp else mapToolLauncherHeight + 48.dp).roundToPx()
        }
    val minMapSize = with(LocalDensity.current) { (if (isWide) 240.dp else 0.dp).roundToPx() }
    val fractionRange = mapToolPanelFractionRange(axisSize, minPanelSize, minMapSize)
    val resizeModifier = mapToolPanelResizeModifier(state, isWide, axisSize, fractionRange, actions)
    Layout(
        modifier = Modifier.fillMaxSize().onSizeChanged { availableSize = it },
        content = {
            // Keep this slot unconditional: Android map views retain their composition identity as panels resize.
            mapToolMapSurface(
                presentation = presentation,
                actions = actions,
                navigation = navigation,
                modifier = Modifier,
                mapContent = mapContent,
            )
            Box {
                if (state.activeTool != null && state.mode != MapToolPanelMode.CLOSED) {
                    mapToolPanelSurface(
                        state = state,
                        resizeModifier = resizeModifier,
                        actions = actions,
                        fullScreenPage = presentation.fullScreenPage,
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
        val range = mapToolPanelFractionRange(if (isWide) width else height, minPanelSize, minMapSize)
        val panelFraction = state.normalizedSizing().splitFraction.coerceIn(range)
        // Portrait fractions include the toolbar, which is overlaid inside the map host.
        val portraitPanelHeight =
            ((height * panelFraction).roundToInt() - launcherHeight.coerceAtMost(height)).coerceAtLeast(0)
        val mapWidth =
            if (panelOpen && state.mode == MapToolPanelMode.SPLIT && isWide) {
                width - (width * panelFraction).roundToInt()
            } else {
                width
            }
        val mapHeight =
            if (panelOpen && state.mode == MapToolPanelMode.SPLIT && !isWide) {
                height - portraitPanelHeight
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

@Composable
private fun mapToolMapSurface(
    presentation: MapToolScaffoldState,
    actions: MapToolScaffoldActions,
    navigation: MapNavigationActions,
    modifier: Modifier,
    mapContent: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        mapContent()
        if (!presentation.fullScreenPage) {
            mapToolLauncher(
                presentation = presentation,
                actions = actions,
                navigation = navigation,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun mapToolPanelSurface(
    state: MapToolPanelState,
    resizeModifier: Modifier,
    actions: MapToolScaffoldActions,
    fullScreenPage: Boolean,
    content: @Composable (MapTool, MapToolContentMode, MapToolFeatureSettingsSection) -> Unit,
) {
    val tool = state.activeTool ?: return
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            mapToolPanelHeader(
                state = state,
                resizeModifier = resizeModifier,
                actions = actions,
                fullScreenPage = fullScreenPage,
            )
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content(tool, state.contentMode, state.featureSettingsSection)
            }
        }
    }
}

@Composable
private fun mapToolPanelHeader(
    state: MapToolPanelState,
    resizeModifier: Modifier,
    actions: MapToolScaffoldActions,
    fullScreenPage: Boolean,
) {
    val isExpanded = state.mode == MapToolPanelMode.EXPANDED
    val tool = state.activeTool ?: return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fullScreenPage || state.hasFeatureSettingsBack) {
            IconButton(onClick = actions.onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_action_back),
                )
            }
        }
        mapToolHeaderTitle(
            state = state,
            tool = tool,
            onFeatureSettings = actions.onFeatureSettings,
        )
        val nextSizeDescription =
            when (state.nextSizeTarget) {
                MapToolPanelSizeTarget.FULL -> stringResource(R.string.map_tool_action_expand_content_description)
                MapToolPanelSizeTarget.DEFAULT -> stringResource(R.string.map_tool_action_default_size, 32)
                MapToolPanelSizeTarget.CUSTOM ->
                    stringResource(
                        R.string.map_tool_action_custom_size,
                        (requireNotNull(state.customSplitFraction) * 100).roundToInt(),
                    )
            }
        if (!fullScreenPage) {
            IconButton(onClick = actions.onCycleSize, modifier = resizeModifier) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                    contentDescription = nextSizeDescription + ". " + stringResource(R.string.map_tool_resize_hint),
                )
            }
        }
        IconButton(onClick = actions.onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_action_close),
            )
        }
    }
}

@Composable
private fun RowScope.mapToolHeaderTitle(
    state: MapToolPanelState,
    tool: MapTool,
    onFeatureSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                stringResource(
                    if (state.contentMode == MapToolContentMode.FEATURE_SETTINGS) {
                        state.featureSettingsSection.titleResource(tool)
                    } else {
                        tool.titleResource(state.contentMode)
                    },
                ),
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!state.hasFeatureSettingsBack && tool in setOf(MapTool.POI, MapTool.GPX, MapTool.MAPS)) {
            IconButton(onClick = onFeatureSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.map_tool_action_settings_content_description),
                )
            }
        }
    }
}

internal fun MapTool.icon(): ImageVector =
    when (this) {
        MapTool.POI -> Icons.Filled.Place
        MapTool.GPX -> Icons.Filled.Route
        MapTool.MAPS -> Icons.Filled.Map
        MapTool.LAYER -> Icons.Filled.Layers
        MapTool.SETTINGS -> Icons.Filled.Settings
    }

@StringRes
internal fun MapTool.titleResource(): Int =
    when (this) {
        MapTool.POI -> R.string.map_tool_poi_title
        MapTool.GPX -> R.string.map_tool_gpx_title
        MapTool.MAPS -> R.string.map_tool_maps_title
        MapTool.LAYER -> R.string.map_tool_layer_title
        MapTool.SETTINGS -> R.string.map_tool_settings_title
    }

@StringRes
internal fun MapTool.titleResource(contentMode: MapToolContentMode): Int =
    if (contentMode == MapToolContentMode.FEATURE_SETTINGS) {
        when (this) {
            MapTool.POI -> R.string.map_tool_poi_settings_title
            MapTool.GPX -> R.string.map_tool_gpx_settings_title
            MapTool.MAPS -> R.string.map_tool_maps_settings_title
            MapTool.LAYER,
            MapTool.SETTINGS,
            -> titleResource()
        }
    } else {
        titleResource()
    }
