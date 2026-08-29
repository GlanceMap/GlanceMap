package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

internal data class MapToolsMapsState(
    val source: PhoneMapSource,
    val offlineMaps: List<PhoneOfflineMap>,
    val hasSelectedFolder: Boolean,
    val themeConfig: PhoneOfflineThemeConfig,
)

internal data class MapToolsGpxState(
    val items: List<PhoneMapGpxItem>,
    val isLoading: Boolean,
    val globalVisible: Boolean,
)

internal data class MapToolsPoiState(
    val sources: List<PhoneMapPoiSource>,
    val globalVisible: Boolean,
)

internal data class MapToolsPanelState(
    val maps: MapToolsMapsState,
    val gpx: MapToolsGpxState,
    val poi: MapToolsPoiState,
)

internal data class MapToolsMapsActions(
    val onSelectOnline: () -> Unit,
    val onSelectOffline: (PhoneOfflineMap) -> Unit,
    val onImportMap: () -> Unit,
    val onDownloadBundle: () -> Unit,
    val onSelectFolder: () -> Unit,
    val onRescanFolder: () -> Unit,
    val onClearFolder: () -> Unit,
    val onOpenTheme: () -> Unit,
)

internal data class MapToolsPanelActions(
    val maps: MapToolsMapsActions,
    val onGpxVisibilityChanged: (Boolean) -> Unit,
    val onGpxItemToggled: (String) -> Unit,
    val onPoiVisibilityChanged: (Boolean) -> Unit,
    val onFeatureSettings: (MapTool) -> Unit,
)

@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolPanelContent(
    tool: MapTool,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    when (tool) {
        MapTool.MAPS -> mapToolsMapsPanel(state.maps, actions.maps)
        MapTool.GPX ->
            mapToolsGpxPanel(
                state = state.gpx,
                onGlobalVisibilityChanged = actions.onGpxVisibilityChanged,
                onItemToggled = actions.onGpxItemToggled,
                onSettings = { actions.onFeatureSettings(MapTool.GPX) },
            )
        MapTool.POI ->
            mapToolsPoiPanel(
                state = state.poi,
                onGlobalVisibilityChanged = actions.onPoiVisibilityChanged,
                onSettings = { actions.onFeatureSettings(MapTool.POI) },
            )
        MapTool.SETTINGS -> mapToolsSettingsPanel(onOpenTheme = actions.maps.onOpenTheme)
    }
}

@Composable
private fun mapToolsMapsPanel(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_maps_source_heading))
        mapSourceRow(
            label = stringResource(R.string.map_source_select_online),
            selected = state.source is PhoneMapSource.Online,
            onClick = actions.onSelectOnline,
        )
        Text(stringResource(R.string.map_tools_maps_offline_heading))
        if (state.offlineMaps.isEmpty()) {
            Text(stringResource(R.string.map_source_no_offline_maps))
        } else {
            state.offlineMaps.forEach { offlineMap ->
                mapSourceRow(
                    label = stringResource(R.string.map_source_select_offline, offlineMap.displayName),
                    selected = (state.source as? PhoneMapSource.Offline)?.map == offlineMap,
                    onClick = { actions.onSelectOffline(offlineMap) },
                )
            }
        }
        HorizontalDivider()
        Button(onClick = actions.onImportMap, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_source_import_local_map))
        }
        OutlinedButton(onClick = actions.onDownloadBundle, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_source_download_offline_bundle))
        }
        if (state.hasSelectedFolder) {
            Text(stringResource(R.string.map_source_map_folder_selected))
            OutlinedButton(onClick = actions.onRescanFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_source_rescan_map_folder))
            }
            OutlinedButton(onClick = actions.onClearFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_source_clear_map_folder))
            }
        } else {
            OutlinedButton(onClick = actions.onSelectFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_source_select_map_folder))
            }
        }
        Text(
            stringResource(
                R.string.map_tools_maps_theme_value,
                stringResource(PhoneOfflineThemeCatalog.themeFor(state.themeConfig.themeId).labelRes),
            ),
        )
        OutlinedButton(onClick = actions.onOpenTheme, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_feature_settings_action))
        }
    }
}

@Composable
private fun mapSourceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun mapToolsGpxPanel(
    state: MapToolsGpxState,
    onGlobalVisibilityChanged: (Boolean) -> Unit,
    onItemToggled: (String) -> Unit,
    onSettings: () -> Unit,
) {
    mapToolPanelColumn {
        mapToolsVisibilityRow(
            label = stringResource(R.string.map_tools_gpx_global_visibility),
            checked = state.globalVisible,
            onCheckedChange = onGlobalVisibilityChanged,
        )
        Text(stringResource(R.string.map_tools_gpx_routes_heading))
        when {
            state.isLoading -> Text(stringResource(R.string.map_tools_gpx_loading))
            state.items.isEmpty() -> Text(stringResource(R.string.map_tools_gpx_empty))
            else ->
                state.items.forEach { item ->
                    mapToolsVisibilityRow(
                        label = item.displayName,
                        checked = item.enabled,
                        onCheckedChange = { onItemToggled(item.id) },
                    )
                }
        }
        Text(stringResource(R.string.map_tools_gpx_folder_deferred))
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_feature_settings_action))
        }
    }
}

@Composable
private fun mapToolsPoiPanel(
    state: MapToolsPoiState,
    onGlobalVisibilityChanged: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    mapToolPanelColumn {
        mapToolsVisibilityRow(
            label = stringResource(R.string.map_tools_poi_global_visibility),
            checked = state.globalVisible,
            onCheckedChange = onGlobalVisibilityChanged,
        )
        Text(stringResource(R.string.map_tools_poi_sources_heading))
        if (state.sources.isEmpty()) {
            Text(stringResource(R.string.map_tools_poi_empty))
        } else {
            state.sources.forEach { source ->
                ListItem(
                    headlineContent = { Text(source.fileName) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (source.isReadable) {
                                    R.string.map_tools_poi_source_available
                                } else {
                                    R.string.map_tools_poi_source_unreadable
                                },
                            ),
                        )
                    },
                )
            }
        }
        Text(stringResource(R.string.map_tools_poi_source_limit))
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_feature_settings_action))
        }
    }
}

@Composable
private fun mapToolsSettingsPanel(
    onOpenTheme: () -> Unit,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_settings_heading))
        Text(stringResource(R.string.map_tools_settings_intro))
        OutlinedButton(onClick = onOpenTheme, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_theme_selector_title))
        }
        Text(stringResource(R.string.map_tools_settings_future))
    }
}

@Composable
private fun mapToolsVisibilityRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun mapToolPanelColumn(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolFeatureSettingsDialog(
    tool: MapTool,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(tool.titleResource())) },
        text = { Text(stringResource(R.string.map_tools_feature_settings_unavailable)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}
