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
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    val hasElevationData: Boolean = false,
    val themeConfig: PhoneOfflineThemeConfig,
    val settings: PhoneMapSettings = PhoneMapSettings(),
    val compassSettings: PhoneCompassSettings = PhoneCompassSettings(),
    val compassState: PhoneCompassState = PhoneCompassState(),
)

internal data class MapToolsGpxState(
    val items: List<PhoneMapGpxItem>,
    val isLoading: Boolean,
    val globalVisible: Boolean,
    val settings: PhoneMapGpxSettings,
    val routeLibrarySourceCount: Int,
    val hasSelectedFolder: Boolean,
    val selectedFolderName: String?,
    val folderError: PhoneGpxFolderError?,
)

internal data class MapToolsPoiState(
    val sources: List<PhoneMapPoiSource>,
    val globalVisible: Boolean,
    val settings: PhoneMapPoiSettings = PhoneMapPoiSettings(),
)

internal data class MapToolsGeneralState(
    val mapMode: PhoneMapMode,
    val settings: PhoneGeneralSettings = PhoneGeneralSettings(),
    val sensorCapabilities: PhoneSensorCapabilities,
    val storageLocation: PhoneOfflineStorageLocation = PhoneOfflineStorageLocation.INTERNAL,
    val externalStorageAvailable: Boolean = false,
    val storageNeedsCanonicalMigration: Boolean = false,
    val storageMigration: PhoneOfflineStorageMigrationState = PhoneOfflineStorageMigrationState(),
)

internal data class MapToolsPanelState(
    val maps: MapToolsMapsState,
    val gpx: MapToolsGpxState,
    val poi: MapToolsPoiState,
    val general: MapToolsGeneralState,
)

internal data class MapToolsMapsActions(
    val onSelectOnline: () -> Unit,
    val onSelectOffline: (PhoneOfflineMap) -> Unit,
    val onImportMap: () -> Unit,
    val onImportElevation: () -> Unit,
    val onSelectFolder: () -> Unit,
    val onRescanFolder: () -> Unit,
    val onClearFolder: () -> Unit,
    val onOpenTheme: () -> Unit,
    val onSettingsChanged: (PhoneMapSettings) -> Unit,
    val onCompassSettingsChanged: (PhoneCompassSettings) -> Unit,
    val onCalibrateCompass: () -> Unit,
)

internal data class MapToolsPanelActions(
    val maps: MapToolsMapsActions,
    val onGpxVisibilityChanged: (Boolean) -> Unit,
    val onGpxItemToggled: (String) -> Unit,
    val onOpenRouteTools: () -> Unit,
    val onGpxSettingsChanged: (PhoneMapGpxSettings) -> Unit,
    val onSelectGpxFolder: () -> Unit,
    val onRescanGpxFolder: () -> Unit,
    val onClearGpxFolder: () -> Unit,
    val onPoiVisibilityChanged: (Boolean) -> Unit,
    val onPoiSettingsChanged: (PhoneMapPoiSettings) -> Unit,
    val onFeatureSettings: (MapTool) -> Unit,
    val onCycleMapMode: () -> Unit,
    val onGeneralSettingsChanged: (PhoneGeneralSettings) -> Unit,
    val onOpenBundleDownload: () -> Unit,
    val onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit,
)

@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolPanelContent(
    tool: MapTool,
    contentMode: MapToolContentMode,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    if (contentMode == MapToolContentMode.FEATURE_SETTINGS) {
        MapToolFeatureSettingsContent(tool = tool, state = state, actions = actions)
    } else {
        when (tool) {
            MapTool.MAPS ->
                mapToolsMapsPanel(
                    state = state.maps,
                    actions = actions.maps,
                    onSettings = { actions.onFeatureSettings(MapTool.MAPS) },
                )
            MapTool.GPX ->
                mapToolsGpxPanel(
                    state = state.gpx,
                    onGlobalVisibilityChanged = actions.onGpxVisibilityChanged,
                    onItemToggled = actions.onGpxItemToggled,
                    onOpenRouteTools = actions.onOpenRouteTools,
                    onSettings = { actions.onFeatureSettings(MapTool.GPX) },
                )
            MapTool.POI ->
                mapToolsPoiPanel(
                    state = state.poi,
                    onGlobalVisibilityChanged = actions.onPoiVisibilityChanged,
                    onSettings = { actions.onFeatureSettings(MapTool.POI) },
                )
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
}

@Composable
private fun mapToolsMapsPanel(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
    onSettings: () -> Unit,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_maps_active_heading))
        mapSourceRow(
            label = stringResource(R.string.map_source_select_online),
            selected = state.source is PhoneMapSource.Online,
            onClick = actions.onSelectOnline,
        )
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
        if (state.source is PhoneMapSource.Offline) {
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
        }
        mapToolsMapsQuickDisplaySettings(state.settings, actions.onSettingsChanged)
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_maps_settings_action))
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
    onOpenRouteTools: () -> Unit,
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
        OutlinedButton(onClick = onOpenRouteTools, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_title))
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_gpx_settings_action))
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
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_poi_settings_action))
        }
    }
}

@Composable
@Suppress("LongMethod") // General settings keep their fixed, readable sensor/status sections together.
internal fun mapToolsSettingsPanel(
    state: MapToolsGeneralState,
    onCycleMapMode: () -> Unit,
    onSettingsChanged: (PhoneGeneralSettings) -> Unit,
    onOpenBundleDownload: () -> Unit,
    onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit,
) {
    mapToolPanelColumn {
        Text(stringResource(R.string.map_tools_settings_heading))
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_tools_settings_map_mode)) },
            supportingContent = { Text(stringResource(state.mapMode.labelResource())) },
        )
        OutlinedButton(onClick = onCycleMapMode, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_settings_cycle_map_mode))
        }
        Text(stringResource(R.string.map_tools_settings_storage_heading))
        mapToolsStorageSetting(
            location = state.storageLocation,
            externalStorageAvailable = state.externalStorageAvailable,
            needsCanonicalMigration = state.storageNeedsCanonicalMigration,
            migration = state.storageMigration,
            onStorageChangeRequested = onStorageChangeRequested,
        )
        Text(stringResource(R.string.map_tools_settings_download_heading))
        OutlinedButton(onClick = onOpenBundleDownload, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_tools_settings_download_bundle))
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_tools_settings_units)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (state.settings.isMetric) {
                            R.string.map_tools_settings_units_metric
                        } else {
                            R.string.map_tools_settings_units_imperial
                        },
                    ),
                )
            },
        )
        OutlinedButton(
            onClick = { onSettingsChanged(state.settings.copy(isMetric = !state.settings.isMetric)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.settings.isMetric) {
                        R.string.map_tools_settings_switch_to_imperial
                    } else {
                        R.string.map_tools_settings_switch_to_metric
                    },
                ),
            )
        }
        mapToolsDistanceMeasurementSetting(
            settings = state.settings,
            onSettingsChanged = onSettingsChanged,
        )
        mapToolsActivitySettings(
            settings = state.settings,
            onSettingsChanged = onSettingsChanged,
        )
        val capabilities = state.sensorCapabilities
        Text(stringResource(R.string.map_tools_settings_sensors_heading))
        listOf(
            "GPS" to capabilities.gpsAvailable,
            "Compass" to capabilities.compassAvailable,
            "Heading sensor" to capabilities.headingSensorAvailable,
            "Rotation vector" to capabilities.rotationVectorAvailable,
            "Accelerometer" to capabilities.accelerometerAvailable,
            "Magnetometer" to capabilities.magnetometerAvailable,
            "Gyroscope" to capabilities.gyroscopeAvailable,
            "Barometer" to capabilities.barometerAvailable,
            "Step detector" to capabilities.stepDetectorAvailable,
            "Step counter" to capabilities.stepCounterAvailable,
        ).forEach { (label, available) ->
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (available) {
                                R.string.map_tools_settings_sensor_available
                            } else {
                                R.string.map_tools_settings_sensor_unavailable
                            },
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun mapToolsActivitySettings(
    settings: PhoneGeneralSettings,
    onSettingsChanged: (PhoneGeneralSettings) -> Unit,
) {
    Text(stringResource(R.string.map_tools_settings_activity_profile))
    OutlinedButton(
        onClick = {
            val nextProfile =
                if (settings.activityProfile == PhoneActivityProfile.HIKE) {
                    PhoneActivityProfile.BIKE
                } else {
                    PhoneActivityProfile.HIKE
                }
            onSettingsChanged(settings.copy(activityProfile = nextProfile))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (settings.activityProfile == PhoneActivityProfile.BIKE) {
                    R.string.map_tools_settings_activity_bike
                } else {
                    R.string.map_tools_settings_activity_hike
                },
            ),
        )
    }
    Text(stringResource(R.string.map_tools_settings_body_weight, settings.userWeightKg))
    Slider(
        value = settings.userWeightKg,
        onValueChange = { value -> onSettingsChanged(settings.copy(userWeightKg = value)) },
        valueRange = MIN_PHONE_USER_WEIGHT_KG..MAX_PHONE_USER_WEIGHT_KG,
    )
    Text(stringResource(R.string.map_tools_settings_backpack_weight, settings.backpackWeightKg))
    Slider(
        value = settings.backpackWeightKg,
        onValueChange = { value -> onSettingsChanged(settings.copy(backpackWeightKg = value)) },
        valueRange = MIN_PHONE_BACKPACK_WEIGHT_KG..MAX_PHONE_BACKPACK_WEIGHT_KG,
    )
    if (settings.activityProfile == PhoneActivityProfile.BIKE) {
        Text(stringResource(R.string.map_tools_settings_bike_weight, settings.bikeWeightKg))
        Slider(
            value = settings.bikeWeightKg,
            onValueChange = { value -> onSettingsChanged(settings.copy(bikeWeightKg = value)) },
            valueRange = MIN_PHONE_BIKE_WEIGHT_KG..MAX_PHONE_BIKE_WEIGHT_KG,
        )
    }
}

@Composable
private fun mapToolsDistanceMeasurementSetting(
    settings: PhoneGeneralSettings,
    onSettingsChanged: (PhoneGeneralSettings) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.map_tools_settings_distance_measurement)) },
        supportingContent = {
            Text(
                stringResource(
                    if (settings.distanceMeasurementEnabled) {
                        R.string.map_tools_settings_distance_measurement_on
                    } else {
                        R.string.map_tools_settings_distance_measurement_off
                    },
                ),
            )
        },
        trailingContent = {
            Switch(
                checked = settings.distanceMeasurementEnabled,
                onCheckedChange = { enabled ->
                    onSettingsChanged(settings.copy(distanceMeasurementEnabled = enabled))
                },
            )
        },
    )
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
internal fun mapToolPanelColumn(content: @Composable () -> Unit) {
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
