@file:Suppress("TooManyFunctions") // GPX appearance controls stay with the map-tool settings panel.

package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.roundToInt

/** Settings remain panel content so returning to a live tool is a normal Back transition. */
@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolFeatureSettingsContent(
    tool: MapTool,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    when (tool) {
        MapTool.POI -> mapToolsPoiSettingsPanel(state.poi, actions.onPoiSettingsChanged)
        MapTool.GPX -> mapToolsGpxSettingsPanel(state.gpx, actions)
        MapTool.MAPS -> mapToolsMapsSettingsPanel(state.maps, actions.maps)
        MapTool.SETTINGS ->
            mapToolsSettingsPanel(
                state = state.general,
                onCycleMapMode = actions.onCycleMapMode,
                onSettingsChanged = actions.onGeneralSettingsChanged,
                onOpenBundleDownload = actions.onOpenBundleDownload,
                onStorageChangeRequested = actions.onStorageChangeRequested,
            )
    }
}

@Composable
@Suppress("LongMethod") // Settings panels keep the related controls in one navigation surface.
private fun mapToolsMapsSettingsPanel(
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
        Text(stringResource(R.string.map_tools_maps_settings_theme_heading))
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
        HorizontalDivider()
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
        HorizontalDivider()
        mapToolsMapBehaviorSettings(
            settings = state.settings,
            onSettingsChanged = actions.onSettingsChanged,
        )
        HorizontalDivider()
        mapToolsCompassSettings(state, actions)
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // The fixed migration status mapping is clearer in one settings control.
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
    if (migration.phase != PhoneOfflineStorageMigrationPhase.IDLE) {
        Text(
            stringResource(
                when (migration.phase) {
                    PhoneOfflineStorageMigrationPhase.COPYING -> R.string.map_tools_settings_storage_moving
                    PhoneOfflineStorageMigrationPhase.VERIFYING -> R.string.map_tools_settings_storage_verifying
                    PhoneOfflineStorageMigrationPhase.SWITCHING -> R.string.map_tools_settings_storage_switching
                    PhoneOfflineStorageMigrationPhase.CLEANUP -> R.string.map_tools_settings_storage_cleanup
                    PhoneOfflineStorageMigrationPhase.COMPLETE -> R.string.map_tools_settings_storage_complete
                    PhoneOfflineStorageMigrationPhase.FAILED -> R.string.map_tools_settings_storage_failed
                    PhoneOfflineStorageMigrationPhase.IDLE -> R.string.map_tools_settings_storage_ready
                },
            ),
        )
        if (migration.totalFiles > 0) {
            Text("${migration.copiedFiles}/${migration.totalFiles} files")
        }
        migration.message?.takeIf(String::isNotBlank)?.let { message -> Text(message) }
    }
}

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
private fun mapToolsMapBehaviorSettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_maps_settings_behavior_heading))
    mapToolsMapDisplaySettings(settings, onSettingsChanged)
    HorizontalDivider()
    mapToolsMapZoomSettings(settings, onSettingsChanged)
}

@Composable
private fun mapToolsMapDisplaySettings(
    settings: PhoneMapSettings,
    onSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    mapToolsMapLocationSettings(settings, onSettingsChanged)
    mapToolsMapDisplayOptions(settings, onSettingsChanged)
    mapToolsMapTerrainSettings(settings, onSettingsChanged)
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
    HorizontalDivider()
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
private fun <T> mapToolsMapSettingPicker(
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
        HorizontalDivider()
        mapToolsGpxAppearanceSettings(
            settings = state.settings,
            onSettingsChanged = actions.onGpxSettingsChanged,
        )
    }
}

@Composable
private fun mapToolsGpxAppearanceSettings(
    settings: PhoneMapGpxSettings,
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    var colorModeMenuExpanded by remember { mutableStateOf(false) }

    Text(stringResource(R.string.map_tools_gpx_settings_appearance_heading))
    mapToolsGpxAnalysisSettings(settings, onSettingsChanged)
    HorizontalDivider()
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
    onSettingsChanged: (PhoneMapGpxSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_gpx_settings_analysis_heading))
    mapToolsMapsToggle(
        label = stringResource(R.string.map_tools_gpx_route_analyzer),
        checked = settings.inspectionEnabled,
        onCheckedChange = { enabled -> onSettingsChanged(settings.copy(inspectionEnabled = enabled)) },
    )
    Text(
        stringResource(
            R.string.map_tools_gpx_flat_speed,
            settings.flatSpeedMetersPerSecond,
        ),
    )
    Slider(
        value = settings.flatSpeedMetersPerSecond,
        onValueChange = { value ->
            onSettingsChanged(settings.copy(flatSpeedMetersPerSecond = value))
        },
        valueRange = MIN_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND..MAX_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND,
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
    state: MapToolsPoiState,
    onSettingsChanged: (PhoneMapPoiSettings) -> Unit,
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
                onSettingsChanged(state.settings.copy(linkGpxWaypointPoiFolders = enabled))
            },
        )
        HorizontalDivider()
        mapToolsPoiAppearanceSettings(state.settings, onSettingsChanged)
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
