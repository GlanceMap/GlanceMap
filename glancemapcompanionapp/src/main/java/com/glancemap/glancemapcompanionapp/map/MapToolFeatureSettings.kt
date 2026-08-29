package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R

/** Settings remain panel content so returning to a live tool is a normal Back transition. */
@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolFeatureSettingsContent(
    tool: MapTool,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    when (tool) {
        MapTool.POI -> mapToolsPoiSettingsPanel(state.poi)
        MapTool.GPX -> mapToolsGpxSettingsPanel(state.gpx, actions)
        MapTool.MAPS -> mapToolsMapsSettingsPanel(state.maps, actions.maps)
        MapTool.SETTINGS -> mapToolsSettingsPanel(state.general, actions.onCycleMapMode)
    }
}

@Composable
private fun mapToolsMapsSettingsPanel(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_maps_settings_storage_heading))
        Button(onClick = actions.onImportMap, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_source_import_local_map))
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
        HorizontalDivider()
        Text(stringResource(R.string.map_tools_maps_settings_theme_heading))
        Text(
            stringResource(
                R.string.map_tools_maps_theme_value,
                stringResource(PhoneOfflineThemeCatalog.themeFor(state.themeConfig.themeId).labelRes),
                stringResource(
                    PhoneOfflineThemeCatalog
                        .themeFor(state.themeConfig.themeId)
                        .styles
                        .first { style -> style.id == state.themeConfig.styleId }
                        .labelRes,
                ),
            ),
        )
        OutlinedButton(onClick = actions.onOpenTheme, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_theme_selector_title))
        }
        HorizontalDivider()
        Text(stringResource(R.string.map_tools_maps_settings_download_heading))
        OutlinedButton(onClick = actions.onDownloadBundle, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_source_download_offline_bundle))
        }
    }
}

@Composable
private fun mapToolsGpxSettingsPanel(
    state: MapToolsGpxState,
    actions: MapToolsPanelActions,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_gpx_settings_sources_heading))
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_tools_gpx_settings_route_library)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (state.routeLibrarySourceCount == 0) {
                            R.string.map_tools_gpx_empty
                        } else {
                            R.string.map_tools_gpx_settings_route_library_available
                        },
                    ),
                )
            },
        )
        HorizontalDivider()
        Text(stringResource(R.string.map_tools_gpx_settings_folder_heading))
        if (state.hasSelectedFolder) {
            Text(
                text =
                    state.selectedFolderName?.let { name ->
                        stringResource(R.string.map_tools_gpx_settings_folder_selected_name, name)
                    } ?: stringResource(R.string.map_tools_gpx_settings_folder_selected),
            )
            state.folderError?.let { error ->
                Text(stringResource(error.messageResource()))
            }
            OutlinedButton(onClick = actions.onRescanGpxFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_tools_gpx_settings_rescan_folder))
            }
            OutlinedButton(onClick = actions.onSelectGpxFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_tools_gpx_settings_change_folder))
            }
            OutlinedButton(onClick = actions.onClearGpxFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_tools_gpx_settings_clear_folder))
            }
        } else {
            OutlinedButton(onClick = actions.onSelectGpxFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_tools_gpx_settings_select_folder))
            }
        }
    }
}

private fun PhoneGpxFolderError.messageResource(): Int =
    when (this) {
        PhoneGpxFolderError.PERMISSION_LOST -> R.string.map_tools_gpx_settings_folder_permission_lost
        PhoneGpxFolderError.SCAN_FAILED -> R.string.map_tools_gpx_settings_folder_scan_failed
    }

@Composable
private fun mapToolsPoiSettingsPanel(
    state: MapToolsPoiState,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_poi_settings_sources_heading))
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
        HorizontalDivider()
        Text(stringResource(R.string.map_tools_poi_settings_limit))
    }
}
