@file:Suppress("TooManyFunctions") // GPX appearance controls stay with the map-tool settings panel.

package com.glancemap.glancemapcompanionapp.map

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.roundToInt

private const val KMPH_TO_MPS = 1f / 3.6f
private const val MPS_TO_KMPH = 3.6f
private const val MPS_TO_MPH = 2.2369363f
private const val MPH_TO_MPS = 1f / MPS_TO_MPH

internal fun MapToolFeatureSettingsSection.titleResource(tool: MapTool): Int =
    when (this) {
        MapToolFeatureSettingsSection.MAP_DATA -> R.string.map_tools_maps_settings_data_title
        MapToolFeatureSettingsSection.MAP_THEME -> R.string.map_tools_maps_settings_theme_heading
        MapToolFeatureSettingsSection.MAP_DISPLAY -> R.string.map_tools_maps_settings_display_title
        MapToolFeatureSettingsSection.MAP_TERRAIN -> R.string.map_tools_maps_settings_terrain_title
        MapToolFeatureSettingsSection.MAP_COMPASS -> R.string.map_tools_maps_compass_heading
        MapToolFeatureSettingsSection.MAP_ZOOM -> R.string.map_tools_maps_settings_zoom_heading
        MapToolFeatureSettingsSection.GPX_SOURCES -> R.string.map_tools_gpx_settings_sources_heading
        MapToolFeatureSettingsSection.GPX_APPEARANCE -> R.string.map_tools_gpx_settings_appearance_heading
        MapToolFeatureSettingsSection.GPX_ANALYSIS -> R.string.map_tools_gpx_settings_analysis_heading
        MapToolFeatureSettingsSection.POI_SOURCES -> R.string.map_tools_poi_settings_sources_heading
        MapToolFeatureSettingsSection.POI_APPEARANCE -> R.string.map_tools_poi_settings_appearance_heading
        MapToolFeatureSettingsSection.GENERAL_DATA -> R.string.map_tools_settings_data_title
        MapToolFeatureSettingsSection.GENERAL_ACTIVITY -> R.string.map_tools_settings_activity_profile
        MapToolFeatureSettingsSection.GENERAL_SENSORS -> R.string.map_tools_settings_sensors_title
        MapToolFeatureSettingsSection.ROOT -> tool.titleResource(MapToolContentMode.FEATURE_SETTINGS)
    }

/** Settings remain panel content so returning to a live tool is a normal Back transition. */
@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolFeatureSettingsContent(
    tool: MapTool,
    section: MapToolFeatureSettingsSection,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    when (tool) {
        MapTool.POI -> mapToolsPoiSettingsPanel(section, state.poi, actions)
        MapTool.GPX ->
            mapToolsGpxSettingsPanel(
                section = section,
                state = state.gpx,
                isMetric = state.general.settings.isMetric,
                actions = actions,
            )
        MapTool.MAPS -> mapToolsMapsSettingsPanel(section, state.maps, actions.maps)
        MapTool.LAYER -> Unit
        MapTool.SETTINGS ->
            mapToolsSettingsPanel(
                section = section,
                state = state.general,
                onSettingsChanged = actions.onGeneralSettingsChanged,
                onOpenBundleDownload = actions.onOpenBundleDownload,
                onStorageChangeRequested = actions.onStorageChangeRequested,
                onOpenSettingsSection = actions.onFeatureSettingsSection,
            )
    }
}

@Composable
@Suppress("LongMethod") // Settings panels keep the related controls in one navigation surface.
private fun mapToolsMapsSettingsPanel(
    section: MapToolFeatureSettingsSection,
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    when (section) {
        MapToolFeatureSettingsSection.ROOT ->
            mapToolPanelColumn {
                Text(stringResource(R.string.map_tools_maps_settings_sections_heading))
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_maps_settings_data_title),
                    summary = stringResource(R.string.map_tools_maps_settings_data_summary),
                    onClick = {
                        actions.onOpenSettingsSection(MapToolFeatureSettingsSection.MAP_DATA)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_maps_settings_theme_heading),
                    summary = stringResource(R.string.map_tools_maps_settings_theme_summary),
                    onClick = {
                        actions.onOpenSettingsSection(MapToolFeatureSettingsSection.MAP_THEME)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_maps_settings_display_title),
                    summary = stringResource(R.string.map_tools_maps_settings_display_summary),
                    onClick = {
                        actions.onOpenSettingsSection(MapToolFeatureSettingsSection.MAP_DISPLAY)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_maps_settings_terrain_title),
                    summary = stringResource(R.string.map_tools_maps_settings_terrain_summary),
                    onClick = {
                        actions.onOpenSettingsSection(MapToolFeatureSettingsSection.MAP_TERRAIN)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_maps_compass_heading),
                    summary = stringResource(R.string.map_tools_maps_compass_summary),
                    onClick = {
                        actions.onOpenSettingsSection(MapToolFeatureSettingsSection.MAP_COMPASS)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_maps_settings_zoom_heading),
                    summary = stringResource(R.string.map_tools_maps_settings_zoom_summary),
                    onClick = {
                        actions.onOpenSettingsSection(MapToolFeatureSettingsSection.MAP_ZOOM)
                    },
                )
            }

        MapToolFeatureSettingsSection.MAP_DATA -> mapToolsMapsDataSettings(state, actions)
        MapToolFeatureSettingsSection.MAP_THEME -> mapToolsMapsThemeSettings(state, actions)
        MapToolFeatureSettingsSection.MAP_DISPLAY ->
            mapToolPanelColumn {
                mapToolsMapDisplaySettings(state.settings, actions.onSettingsChanged)
            }
        MapToolFeatureSettingsSection.MAP_TERRAIN -> mapToolsMapsTerrainSettings(state, actions)
        MapToolFeatureSettingsSection.MAP_COMPASS ->
            mapToolPanelColumn { mapToolsCompassSettings(state, actions) }
        MapToolFeatureSettingsSection.MAP_ZOOM ->
            mapToolPanelColumn {
                mapToolsMapZoomSettings(state.settings, actions.onSettingsChanged)
            }
        else -> Unit
    }
}

@Composable
private fun mapToolsMapsDataSettings(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    mapToolPanelColumn {
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
        mapToolsRoutingFolderSettings(state, actions)
    }
}

@Composable
private fun mapToolsMapsThemeSettings(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    mapToolPanelColumn {
        Text(
            stringResource(
                R.string.map_tools_maps_theme_value,
                PhoneOfflineThemeCatalog.themeFor(state.themeConfig.themeId).label,
                PhoneOfflineThemeCatalog
                    .themeFor(state.themeConfig.themeId)
                    .styles
                    .firstOrNull { style -> style.id == state.themeConfig.styleId }
                    ?.label
                    ?: "Default",
            ),
        )
        OutlinedButton(onClick = actions.onOpenTheme, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_theme_selector_title))
        }
    }
}

@Composable
private fun mapToolsMapsTerrainSettings(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_maps_elevation_heading))
        Text(
            stringResource(
                if (state.hasElevationData) {
                    R.string.map_tools_maps_elevation_available
                } else {
                    R.string.map_tools_maps_elevation_missing
                },
            ),
        )
        OutlinedButton(onClick = actions.onImportElevation, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_maps_import_elevation))
        }
        mapToolsElevationFolderSettings(state, actions)
        HorizontalDivider()
        mapToolsMapTerrainSettings(state.settings, actions.onSettingsChanged)
    }
}

@Composable
private fun mapToolsElevationFolderSettings(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    Text(stringResource(R.string.map_elevation_folder_heading))
    Text(stringResource(R.string.map_elevation_folder_managed_summary))
    if (state.hasSelectedElevationFolder) {
        Text(
            state.elevationFolderSync.folderName
                ?: stringResource(R.string.map_elevation_folder_selected),
        )
        Text(
            stringResource(
                R.string.map_elevation_folder_status,
                state.elevationFolderSync.validCount,
                state.elevationFolderSync.importedCount,
                state.elevationFolderSync.reusedCount,
                state.elevationFolderSync.invalidCount,
            ),
        )
        state.elevationFolderSync.error?.let { error ->
            Text(
                stringResource(
                    when (error) {
                        PhoneElevationFolderError.PERMISSION_LOST ->
                            R.string.map_elevation_folder_permission_lost
                        PhoneElevationFolderError.SCAN_FAILED -> R.string.map_elevation_folder_scan_failed
                        PhoneElevationFolderError.COPY_FAILED -> R.string.map_elevation_folder_copy_failed
                    },
                ),
            )
        }
        OutlinedButton(onClick = actions.onRescanElevationFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_elevation_folder_rescan))
        }
        OutlinedButton(onClick = actions.onSelectElevationFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_elevation_folder_change))
        }
        OutlinedButton(onClick = actions.onClearElevationFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_elevation_folder_clear))
        }
    } else {
        Text(stringResource(R.string.map_elevation_folder_none))
        OutlinedButton(onClick = actions.onSelectElevationFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_elevation_folder_select))
        }
    }
}

@Composable
private fun mapToolsRoutingFolderSettings(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    Text(stringResource(R.string.map_routing_folder_heading))
    Text(stringResource(R.string.map_routing_folder_managed_summary))
    if (state.hasSelectedRoutingFolder) {
        Text(
            state.routingFolderSync.folderName
                ?: stringResource(R.string.map_routing_folder_selected),
        )
        Text(
            stringResource(
                R.string.map_routing_folder_status,
                state.routingFolderSync.validCount,
                state.routingFolderSync.importedCount,
                state.routingFolderSync.reusedCount,
                state.routingFolderSync.invalidCount,
            ),
        )
        state.routingFolderSync.error?.let { error ->
            Text(
                stringResource(
                    when (error) {
                        PhoneRoutingFolderError.PERMISSION_LOST ->
                            R.string.map_routing_folder_permission_lost
                        PhoneRoutingFolderError.SCAN_FAILED -> R.string.map_routing_folder_scan_failed
                        PhoneRoutingFolderError.COPY_FAILED -> R.string.map_routing_folder_copy_failed
                    },
                ),
            )
        }
        OutlinedButton(onClick = actions.onRescanRoutingFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_routing_folder_rescan))
        }
        OutlinedButton(onClick = actions.onSelectRoutingFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_routing_folder_change))
        }
        OutlinedButton(onClick = actions.onClearRoutingFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_routing_folder_clear))
        }
    } else {
        Text(stringResource(R.string.map_routing_folder_none))
        OutlinedButton(onClick = actions.onSelectRoutingFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_routing_folder_select))
        }
    }
}

@Composable
internal fun mapToolsStorageSetting(
    location: PhoneOfflineStorageLocation,
    externalStorageAvailable: Boolean,
    needsCanonicalMigration: Boolean,
    migration: PhoneOfflineStorageMigrationState,
    onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit,
) {
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_settings_storage_selector),
        selectedLabel = location.label,
        options =
            PhoneOfflineStorageLocation.entries.mapNotNull { location ->
                if (location == PhoneOfflineStorageLocation.EXTERNAL && !externalStorageAvailable) {
                    null
                } else {
                    location to location.label
                }
            },
        onSelect = onStorageChangeRequested,
    )
    if (!externalStorageAvailable) {
        Text(stringResource(R.string.map_tools_settings_storage_external_unavailable))
    }
    if (needsCanonicalMigration) {
        Text(stringResource(R.string.map_tools_settings_storage_legacy))
        OutlinedButton(
            onClick = { onStorageChangeRequested(location) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.map_tools_settings_storage_move_to_glancemap))
        }
    }
    mapToolsStorageMigrationStatus(migration)
}

@Composable
private fun mapToolsStorageMigrationStatus(migration: PhoneOfflineStorageMigrationState) {
    if (migration.phase == PhoneOfflineStorageMigrationPhase.IDLE) return
    val context = LocalContext.current
    Text(stringResource(migration.phase.statusResource()))
    if (migration.phase == PhoneOfflineStorageMigrationPhase.COPYING) {
        Text(stringResource(R.string.map_tools_settings_storage_merging))
    }
    if (migration.totalFiles > 0 && migration.phase.showsProgress()) {
        Text(
            stringResource(
                R.string.map_tools_settings_storage_progress,
                migration.copiedFiles,
                migration.totalFiles,
                migration.percent ?: 0,
            ),
        )
    }
    if (migration.phase == PhoneOfflineStorageMigrationPhase.COPYING &&
        migration.requiredSpaceBytes > 0L &&
        migration.availableSpaceBytes > 0L
    ) {
        Text(
            stringResource(
                R.string.map_tools_settings_storage_space_progress,
                Formatter.formatFileSize(context, migration.requiredSpaceBytes),
                Formatter.formatFileSize(context, migration.availableSpaceBytes),
            ),
        )
    }
    migration.message?.takeIf(String::isNotBlank)?.let { message -> Text(message) }
    if (migration.phase == PhoneOfflineStorageMigrationPhase.COMPLETE) {
        Text(
            stringResource(
                R.string.map_tools_settings_storage_summary,
                migration.reusedFiles,
                migration.copiedFiles,
                migration.replacedFiles,
            ),
        )
    }
}

private fun PhoneOfflineStorageMigrationPhase.statusResource(): Int =
    when (this) {
        PhoneOfflineStorageMigrationPhase.COPYING -> R.string.map_tools_settings_storage_moving
        PhoneOfflineStorageMigrationPhase.VERIFYING -> R.string.map_tools_settings_storage_verifying
        PhoneOfflineStorageMigrationPhase.SWITCHING -> R.string.map_tools_settings_storage_switching
        PhoneOfflineStorageMigrationPhase.CLEANUP -> R.string.map_tools_settings_storage_cleanup
        PhoneOfflineStorageMigrationPhase.COMPLETE -> R.string.map_tools_settings_storage_complete
        PhoneOfflineStorageMigrationPhase.FAILED -> R.string.map_tools_settings_storage_failed
        PhoneOfflineStorageMigrationPhase.IDLE -> R.string.map_tools_settings_storage_ready
    }

private fun PhoneOfflineStorageMigrationPhase.showsProgress(): Boolean =
    this !in
        setOf(
            PhoneOfflineStorageMigrationPhase.COMPLETE,
            PhoneOfflineStorageMigrationPhase.FAILED,
        )

@Composable
@Suppress("LongMethod") // Compass controls intentionally mirror the watch settings in one panel.
private fun mapToolsCompassSettings(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    val compass = state.compassSettings
    val compassState = state.compassState
    Text(stringResource(R.string.map_tools_maps_compass_heading))
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_compass_provider),
        selectedLabel = compass.providerMode.label,
        options = PhoneCompassProviderMode.entries.map { mode -> mode to mode.label },
        onSelect = { mode -> actions.onCompassSettingsChanged(compass.copy(providerMode = mode)) },
    )
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_compass_settings_mode),
        selectedLabel = compass.settingsMode.label,
        options = PhoneCompassSettingsMode.entries.map { mode -> mode to mode.label },
        onSelect = { mode -> actions.onCompassSettingsChanged(compass.copy(settingsMode = mode)) },
    )
    if (compass.settingsMode == PhoneCompassSettingsMode.ADVANCED) {
        mapToolsMapSettingPicker(
            label = stringResource(R.string.map_tools_maps_compass_heading_source),
            selectedLabel = compass.headingSourceMode.label,
            options = PhoneCompassHeadingSourceMode.entries.map { mode -> mode to mode.label },
            onSelect = { mode -> actions.onCompassSettingsChanged(compass.copy(headingSourceMode = mode)) },
        )
    }
    Text(
        stringResource(
            R.string.map_tools_maps_compass_accuracy,
            phoneCompassAccuracyLabel(compassState.accuracy),
        ),
    )
    Text(
        stringResource(
            R.string.map_tools_maps_compass_source,
            compassState.pipeline.label,
        ),
    )
    if (compass.calibrationAlertsEnabled && compassState.calibrationRecommended) {
        Text(stringResource(R.string.map_tools_maps_compass_calibration_needed))
    }
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_maps_compass_calibration_alerts),
        checked = compass.calibrationAlertsEnabled,
        onCheckedChange = { enabled ->
            actions.onCompassSettingsChanged(compass.copy(calibrationAlertsEnabled = enabled))
        },
    )
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_maps_compass_accuracy_display),
        checked = compass.accuracyDisplayEnabled,
        onCheckedChange = { enabled ->
            actions.onCompassSettingsChanged(compass.copy(accuracyDisplayEnabled = enabled))
        },
    )
    OutlinedButton(onClick = actions.onCalibrateCompass, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.map_tools_maps_compass_recalibrate))
    }
}

@Composable
private fun mapToolsMapDisplaySettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    mapToolsMapLocationSettings(settings, onSettingsChanged)
    mapToolsMapDisplayOptions(settings, onSettingsChanged)
}

@Composable
private fun mapToolsMapLocationSettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_marker_position),
        selectedLabel =
            stringResource(
                if (settings.markerAnchor == PhoneMapMarkerAnchor.LOWER) {
                    R.string.map_tools_maps_marker_position_lower
                } else {
                    R.string.map_tools_maps_marker_position_center
                },
            ),
        options =
            PhoneMapMarkerAnchor.entries.map { anchor ->
                anchor to
                    stringResource(
                        if (anchor == PhoneMapMarkerAnchor.LOWER) {
                            R.string.map_tools_maps_marker_position_lower
                        } else {
                            R.string.map_tools_maps_marker_position_center
                        },
                    )
            },
        onSelect = { anchor -> onSettingsChanged(settings.copy(markerAnchor = anchor)) },
    )
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_maps_auto_recenter),
        checked = settings.autoRecenterEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(autoRecenterEnabled = enabled)) },
    )
    if (settings.autoRecenterEnabled) {
        mapToolsMapScaleDelaySetting(
            settings = settings,
            onSettingsChanged = onSettingsChanged,
        )
    }
}

@Composable
private fun mapToolsMapDisplayOptions(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_north_indicator),
        selectedLabel = stringResource(settings.northIndicatorMode.labelResource()),
        options =
            PhoneMapNorthIndicatorMode.entries.map { mode ->
                mode to stringResource(mode.labelResource())
            },
        onSelect = { mode -> onSettingsChanged(settings.copy(northIndicatorMode = mode)) },
    )
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_marker_style),
        selectedLabel = stringResource(settings.markerStyle.labelResource()),
        options =
            PhoneMapMarkerStyle.entries.map { style ->
                style to stringResource(style.labelResource())
            },
        onSelect = { style -> onSettingsChanged(settings.copy(markerStyle = style)) },
    )
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_zoom_buttons),
        selectedLabel = stringResource(settings.zoomButtonsMode.labelResource()),
        options =
            PhoneMapZoomButtonsMode.entries.map { mode ->
                mode to stringResource(mode.labelResource())
            },
        onSelect = { mode -> onSettingsChanged(settings.copy(zoomButtonsMode = mode)) },
    )
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_maps_accuracy_circle),
        checked = settings.gpsAccuracyCircleEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(gpsAccuracyCircleEnabled = enabled)) },
    )
}

@Composable
private fun mapToolsMapTerrainSettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_maps_terrain_heading))
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_dem_source),
        selectedLabel = settings.demSource.shortLabel,
        options =
            PhoneOfflineDemSource.entries.map { source ->
                source to stringResource(R.string.map_tools_maps_dem_source_detail, source.label, source.detailLabel)
            },
        onSelect = { source -> onSettingsChanged(settings.copy(demSource = source)) },
    )
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_maps_north_reference),
        selectedLabel = stringResource(settings.northReferenceMode.labelResource()),
        options =
            PhoneMapNorthReferenceMode.entries.map { mode ->
                mode to stringResource(mode.labelResource())
            },
        onSelect = { mode -> onSettingsChanged(settings.copy(northReferenceMode = mode)) },
    )
}

@Composable
private fun mapToolsMapZoomSettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_maps_settings_zoom_heading))
    mapToolsMapScaleSetting(
        label = stringResource(R.string.map_tools_maps_default_scale),
        value = settings.zoomDefaultScaleMeters,
        onValueChange = { value -> onSettingsChanged(settings.copy(zoomDefaultScaleMeters = value)) },
    )
    mapToolsMapScaleSetting(
        label = stringResource(R.string.map_tools_maps_farthest_out),
        value = settings.zoomMinScaleMeters,
        onValueChange = { value -> onSettingsChanged(settings.copy(zoomMinScaleMeters = value)) },
    )
    mapToolsMapScaleSetting(
        label = stringResource(R.string.map_tools_maps_closest_in),
        value = settings.zoomMaxScaleMeters,
        onValueChange = { value -> onSettingsChanged(settings.copy(zoomMaxScaleMeters = value)) },
    )
}

@Composable
private fun mapToolsMapScaleDelaySetting(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    val minDelay = MIN_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS.toFloat()
    val maxDelay = MAX_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS.toFloat()
    val delayRange = minDelay.rangeTo(maxDelay)
    Text(
        stringResource(
            R.string.map_tools_maps_auto_recenter_delay,
            settings.autoRecenterDelaySeconds,
        ),
    )
    Slider(
        value = settings.autoRecenterDelaySeconds.toFloat(),
        onValueChange = { value ->
            onSettingsChanged(
                settings.copy(autoRecenterDelaySeconds = value.roundToInt()),
            )
        },
        valueRange = delayRange,
        steps = MAX_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS - MIN_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun mapToolsSettingsSection(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
internal fun <T> mapToolsMapSettingPicker(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $selectedLabel")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun mapToolsMapsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun mapToolsMapsQuickDisplaySettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
    showOfflineOnlyOptions: Boolean = true,
) {
    HorizontalDivider()
    Text(stringResource(R.string.map_tools_maps_quick_display_heading))
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_maps_live_elevation),
        checked = settings.liveElevationEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(liveElevationEnabled = enabled)) },
    )
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_maps_live_distance),
        checked = settings.liveDistanceEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(liveDistanceEnabled = enabled)) },
    )
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_maps_distance_measurement),
        checked = settings.distanceMeasurementEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(distanceMeasurementEnabled = enabled)) },
    )
    if (showOfflineOnlyOptions) {
        mapToolsMapsToggle(
            label = stringResource(R.string.map_tools_maps_hill_shading),
            checked = settings.hillShadingEnabled,
            onCheckedChange = { enabled -> onSettingsChanged(settings.copy(hillShadingEnabled = enabled)) },
        )
        mapToolsMapsToggle(
            label = stringResource(R.string.map_tools_maps_relief_overlay),
            checked = settings.reliefOverlayEnabled,
            onCheckedChange = { enabled -> onSettingsChanged(settings.copy(reliefOverlayEnabled = enabled)) },
        )
        mapToolsMapsToggle(
            label = stringResource(R.string.map_tools_maps_night_mode),
            checked = settings.nightModeEnabled,
            onCheckedChange = { enabled -> onSettingsChanged(settings.copy(nightModeEnabled = enabled)) },
        )
    }
}

@Composable
private fun mapToolsMapScaleSetting(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val selectedIndex = PHONE_MAP_SCALE_STEPS_METERS.indexOf(value).coerceAtLeast(0)
    Text("$label: ${formatPhoneMapScaleDistance(value)}")
    Slider(
        value = selectedIndex.toFloat(),
        onValueChange = { next ->
            onValueChange(
                PHONE_MAP_SCALE_STEPS_METERS[
                    next.roundToInt().coerceIn(0, PHONE_MAP_SCALE_STEPS_METERS.lastIndex),
                ],
            )
        },
        valueRange = 0f..PHONE_MAP_SCALE_STEPS_METERS.lastIndex.toFloat(),
        steps = (PHONE_MAP_SCALE_STEPS_METERS.size - 2).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun mapToolsGpxSettingsPanel(
    section: MapToolFeatureSettingsSection,
    state: MapToolsGpxState,
    isMetric: Boolean,
    actions: MapToolsPanelActions,
) {
    when (section) {
        MapToolFeatureSettingsSection.ROOT ->
            mapToolPanelColumn {
                Text(stringResource(R.string.map_tools_gpx_settings_sections_heading))
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_gpx_settings_sources_heading),
                    summary = stringResource(R.string.map_tools_gpx_settings_sources_summary),
                    onClick = {
                        actions.onFeatureSettingsSection(MapToolFeatureSettingsSection.GPX_SOURCES)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_gpx_settings_appearance_heading),
                    summary = stringResource(R.string.map_tools_gpx_settings_appearance_summary),
                    onClick = {
                        actions.onFeatureSettingsSection(MapToolFeatureSettingsSection.GPX_APPEARANCE)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_gpx_settings_analysis_heading),
                    summary = stringResource(R.string.map_tools_gpx_settings_analysis_summary),
                    onClick = {
                        actions.onFeatureSettingsSection(MapToolFeatureSettingsSection.GPX_ANALYSIS)
                    },
                )
            }

        MapToolFeatureSettingsSection.GPX_SOURCES -> mapToolsGpxSourceSettings(state, actions)
        MapToolFeatureSettingsSection.GPX_APPEARANCE ->
            mapToolPanelColumn {
                mapToolsGpxAppearanceSettings(
                    settings = state.settings,
                    onSettingsChanged = actions.onGpxSettingsChanged,
                )
            }
        MapToolFeatureSettingsSection.GPX_ANALYSIS ->
            mapToolPanelColumn {
                mapToolsGpxAnalysisSettings(
                    settings = state.settings,
                    isMetric = isMetric,
                    onSettingsChanged = actions.onGpxSettingsChanged,
                )
            }
        else -> Unit
    }
}

@Composable
private fun mapToolsGpxSourceSettings(
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

@Composable
private fun mapToolsGpxAppearanceSettings(
    settings: PhoneMapGpxSettings,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    var colorModeMenuExpanded by remember { mutableStateOf(false) }

    Text(stringResource(R.string.map_tools_gpx_settings_appearance_heading))
    gpxColorModeSetting(
        settings = settings,
        expanded = colorModeMenuExpanded,
        onExpand = { colorModeMenuExpanded = true },
        onDismiss = { colorModeMenuExpanded = false },
        onSettingsChanged = onSettingsChanged,
    )
    if (settings.colorMode == PhoneMapGpxColorMode.SOLID) {
        gpxTrackColorSetting(settings, onSettingsChanged)
    }
    gpxTrackDirectionSetting(settings, onSettingsChanged)
    gpxTrackWidthSetting(settings, onSettingsChanged)
    gpxTrackOpacitySetting(settings, onSettingsChanged)
}

@Composable
@Suppress("LongMethod") // GPX analysis controls must stay together so dependencies are visible.
private fun mapToolsGpxAnalysisSettings(
    settings: PhoneMapGpxSettings,
    isMetric: Boolean,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_gpx_settings_analysis_heading))
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_gpx_route_analyzer),
        checked = settings.inspectionEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(inspectionEnabled = enabled)) },
    )
    val minDisplaySpeed =
        if (isMetric) {
            MIN_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND * MPS_TO_KMPH
        } else {
            MIN_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND * MPS_TO_MPH
        }
    val maxDisplaySpeed =
        if (isMetric) {
            MAX_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND * MPS_TO_KMPH
        } else {
            MAX_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND * MPS_TO_MPH
        }
    val displaySpeed =
        if (isMetric) {
            settings.flatSpeedMetersPerSecond * MPS_TO_KMPH
        } else {
            settings.flatSpeedMetersPerSecond * MPS_TO_MPH
        }.coerceIn(minDisplaySpeed, maxDisplaySpeed)
    val displayUnit = if (isMetric) "km/h" else "mph"
    Text(stringResource(R.string.map_tools_gpx_flat_speed, displaySpeed, displayUnit))
    Slider(
        value = displaySpeed,
        onValueChange = { rawValue ->
            val displayValue =
                (rawValue / 0.1f).roundToInt() *
                    0.1f
                        .coerceIn(minDisplaySpeed, maxDisplaySpeed)
            val speedMps = if (isMetric) displayValue * KMPH_TO_MPS else displayValue * MPH_TO_MPS
            onSettingsChanged(settings.copy(flatSpeedMetersPerSecond = speedMps))
        },
        valueRange = minDisplaySpeed..maxDisplaySpeed,
        modifier = Modifier.fillMaxWidth(),
    )
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_gpx_advanced_eta),
        checked = settings.advancedEtaEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(advancedEtaEnabled = enabled)) },
    )
    if (settings.advancedEtaEnabled) {
        Text(
            stringResource(
                R.string.map_tools_gpx_uphill_rate,
                settings.uphillVerticalMetersPerHour.roundToInt(),
            ),
        )
        Slider(
            value = settings.uphillVerticalMetersPerHour,
            onValueChange = { value ->
                onSettingsChanged(settings.copy(uphillVerticalMetersPerHour = value))
            },
            valueRange = MIN_PHONE_GPX_VERTICAL_METERS_PER_HOUR..MAX_PHONE_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                R.string.map_tools_gpx_downhill_rate,
                settings.downhillVerticalMetersPerHour.roundToInt(),
            ),
        )
        Slider(
            value = settings.downhillVerticalMetersPerHour,
            onValueChange = { value ->
                onSettingsChanged(settings.copy(downhillVerticalMetersPerHour = value))
            },
            valueRange = MIN_PHONE_GPX_VERTICAL_METERS_PER_HOUR..MAX_PHONE_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_gpx_stamina_adjustment),
        checked = settings.staminaAdjustmentEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(staminaAdjustmentEnabled = enabled)) },
    )
}

@Composable
private fun gpxColorModeSetting(
    settings: PhoneMapGpxSettings,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    Box {
        OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(settings.colorMode.labelResource()))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            PhoneMapGpxColorMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.labelResource())) },
                    onClick = {
                        onDismiss()
                        onSettingsChanged(settings.copy(colorMode = mode))
                    },
                )
            }
        }
    }
}

@Composable
private fun gpxTrackColorSetting(
    settings: PhoneMapGpxSettings,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_gpx_track_color))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GPX_COLOR_PALETTE.forEach { color ->
            val selected = color.toArgb() == settings.trackColorArgb
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) Color.Black else Color.Gray,
                            shape = CircleShape,
                        ).clickable {
                            onSettingsChanged(settings.copy(trackColorArgb = color.toArgb()))
                        },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (color == Color(0xFFFFA500)) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun gpxTrackDirectionSetting(
    settings: PhoneMapGpxSettings,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.map_tools_gpx_track_direction), modifier = Modifier.weight(1f))
        Switch(
            checked = settings.directionArrowsEnabled,
            onCheckedChange = { enabled ->
                onSettingsChanged(settings.copy(directionArrowsEnabled = enabled))
            },
        )
    }
}

@Composable
private fun gpxTrackWidthSetting(
    settings: PhoneMapGpxSettings,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    Text(
        stringResource(
            R.string.map_tools_gpx_track_width_value,
            settings.trackWidth.roundToInt(),
        ),
    )
    Slider(
        value = settings.trackWidth,
        onValueChange = { value ->
            onSettingsChanged(
                settings.copy(trackWidth = value.roundToInt().toFloat()),
            )
        },
        valueRange = MIN_PHONE_GPX_TRACK_WIDTH..MAX_PHONE_GPX_TRACK_WIDTH,
        steps = 13,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun gpxTrackOpacitySetting(
    settings: PhoneMapGpxSettings,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_gpx_track_opacity_value, settings.trackOpacityPercent))
    Slider(
        value = settings.trackOpacityPercent.toFloat(),
        onValueChange = { value ->
            onSettingsChanged(
                settings.copy(trackOpacityPercent = value.roundToInt()),
            )
        },
        valueRange =
            MIN_PHONE_GPX_TRACK_OPACITY_PERCENT.toFloat()..MAX_PHONE_GPX_TRACK_OPACITY_PERCENT.toFloat(),
        steps = MAX_PHONE_GPX_TRACK_OPACITY_PERCENT - MIN_PHONE_GPX_TRACK_OPACITY_PERCENT - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun PhoneMapGpxColorMode.labelResource(): Int =
    when (this) {
        PhoneMapGpxColorMode.SOLID -> R.string.map_tools_gpx_color_mode_solid
        PhoneMapGpxColorMode.ELEVATION -> R.string.map_tools_gpx_color_mode_elevation
    }

private fun PhoneMapNorthIndicatorMode.labelResource(): Int =
    when (this) {
        PhoneMapNorthIndicatorMode.ALWAYS -> R.string.map_tools_maps_north_indicator_always
        PhoneMapNorthIndicatorMode.COMPASS_ONLY -> R.string.map_tools_maps_north_indicator_compass_only
        PhoneMapNorthIndicatorMode.NORTH_UP_ONLY -> R.string.map_tools_maps_north_indicator_north_up_only
        PhoneMapNorthIndicatorMode.NEVER -> R.string.map_tools_maps_north_indicator_never
    }

private fun PhoneMapNorthReferenceMode.labelResource(): Int =
    when (this) {
        PhoneMapNorthReferenceMode.TRUE -> R.string.map_tools_maps_north_reference_true
        PhoneMapNorthReferenceMode.MAGNETIC -> R.string.map_tools_maps_north_reference_magnetic
    }

private fun PhoneMapMarkerStyle.labelResource(): Int =
    when (this) {
        PhoneMapMarkerStyle.DOT -> R.string.map_tools_maps_marker_style_dot
        PhoneMapMarkerStyle.TRIANGLE -> R.string.map_tools_maps_marker_style_triangle
    }

private fun PhoneMapZoomButtonsMode.labelResource(): Int =
    when (this) {
        PhoneMapZoomButtonsMode.BOTH -> R.string.map_tools_maps_zoom_buttons_both
        PhoneMapZoomButtonsMode.HIDE_BOTH -> R.string.map_tools_maps_zoom_buttons_hide_both
        PhoneMapZoomButtonsMode.HIDE_PLUS -> R.string.map_tools_maps_zoom_buttons_hide_plus
    }

private val GPX_COLOR_PALETTE =
    listOf(
        Color.Magenta,
        Color.Blue,
        Color(0xFFFFA500),
        Color.Red,
    )

private fun PhoneGpxFolderError.messageResource(): Int =
    when (this) {
        PhoneGpxFolderError.PERMISSION_LOST -> R.string.map_tools_gpx_settings_folder_permission_lost
        PhoneGpxFolderError.SCAN_FAILED -> R.string.map_tools_gpx_settings_folder_scan_failed
    }

@Composable
private fun mapToolsPoiSettingsPanel(
    section: MapToolFeatureSettingsSection,
    state: MapToolsPoiState,
    actions: MapToolsPanelActions,
) {
    when (section) {
        MapToolFeatureSettingsSection.ROOT ->
            mapToolPanelColumn {
                Text(stringResource(R.string.map_tools_poi_settings_sections_heading))
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_poi_settings_sources_heading),
                    summary = stringResource(R.string.map_tools_poi_settings_sources_summary),
                    onClick = {
                        actions.onFeatureSettingsSection(MapToolFeatureSettingsSection.POI_SOURCES)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_poi_settings_appearance_heading),
                    summary = stringResource(R.string.map_tools_poi_settings_appearance_summary),
                    onClick = {
                        actions.onFeatureSettingsSection(MapToolFeatureSettingsSection.POI_APPEARANCE)
                    },
                )
            }

        MapToolFeatureSettingsSection.POI_SOURCES -> mapToolsPoiSourceSettings(state, actions)
        MapToolFeatureSettingsSection.POI_APPEARANCE ->
            mapToolPanelColumn {
                mapToolsPoiAppearanceSettings(
                    settings = state.settings,
                    onSettingsChanged = actions.onPoiSettingsChanged,
                )
            }
        else -> Unit
    }
}

@Composable
private fun mapToolsPoiSourceSettings(
    state: MapToolsPoiState,
    actions: MapToolsPanelActions,
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
        mapToolsMapsToggle(
            label = stringResource(R.string.map_tools_poi_link_gpx_waypoint_folders),
            checked = state.settings.linkGpxWaypointPoiFolders,
            onCheckedChange = { enabled ->
                actions.onPoiSettingsChanged(state.settings.copy(linkGpxWaypointPoiFolders = enabled))
            },
        )
        HorizontalDivider()
        Text(stringResource(R.string.map_tools_poi_settings_limit))
    }
}

@Composable
private fun mapToolsPoiAppearanceSettings(
    settings: PhoneMapPoiSettings,
    onSettingsChanged: (PhoneMapPoiSettings) -> Unit,
) {
    val minPopupTimeout = MIN_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS.toFloat()
    val maxPopupTimeout = MAX_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS.toFloat()
    Text(stringResource(R.string.map_tools_poi_settings_appearance_heading))
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_poi_icon_size),
        selectedLabel = stringResource(settings.iconSize.labelResource()),
        options =
            PhoneMapPoiIconSize.entries.map { size ->
                size to stringResource(size.labelResource())
            },
        onSelect = { size -> onSettingsChanged(settings.copy(iconSize = size)) },
    )
    mapToolsMapSettingPicker(
        label = stringResource(R.string.map_tools_poi_marker_style),
        selectedLabel = stringResource(settings.markerStyle.labelResource()),
        options =
            PhoneMapPoiMarkerStyle.entries.map { style ->
                style to stringResource(style.labelResource())
            },
        onSelect = { style -> onSettingsChanged(settings.copy(markerStyle = style)) },
    )
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_poi_popup_auto_close),
        checked = settings.popupAutoCloseEnabled,
        onCheckedChange = { enabled ->
            onSettingsChanged(settings.copy(popupAutoCloseEnabled = enabled))
        },
    )
    if (settings.popupAutoCloseEnabled) {
        Text(stringResource(R.string.map_tools_poi_popup_timeout, settings.popupTimeoutSeconds))
        Slider(
            value = settings.popupTimeoutSeconds.toFloat(),
            onValueChange = { value ->
                onSettingsChanged(settings.copy(popupTimeoutSeconds = value.roundToInt()))
            },
            valueRange = minPopupTimeout..maxPopupTimeout,
            steps =
                MAX_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS -
                    MIN_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS -
                    1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun PhoneMapPoiIconSize.labelResource(): Int =
    when (this) {
        PhoneMapPoiIconSize.SMALL -> R.string.map_tools_poi_icon_size_small
        PhoneMapPoiIconSize.MEDIUM -> R.string.map_tools_poi_icon_size_medium
        PhoneMapPoiIconSize.LARGE -> R.string.map_tools_poi_icon_size_large
    }

private fun PhoneMapPoiMarkerStyle.labelResource(): Int =
    when (this) {
        PhoneMapPoiMarkerStyle.BADGE -> R.string.map_tools_poi_marker_style_badge
        PhoneMapPoiMarkerStyle.THEME_ICON -> R.string.map_tools_poi_marker_style_theme_icon
    }
