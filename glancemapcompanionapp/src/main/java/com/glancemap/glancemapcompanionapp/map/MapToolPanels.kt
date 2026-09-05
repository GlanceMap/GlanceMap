@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
) // The map tool panels keep each renderer/data source's controls together.

package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import java.io.File
import java.util.Locale

internal data class MapToolsMapsState(
    val source: PhoneMapSource,
    val onlineSources: List<PhoneOnlineMapSource>,
    val selectedOnlineSource: PhoneOnlineMapSource,
    val offlineMaps: List<PhoneOfflineMap>,
    val offlineMapAvailability: Map<String, PhoneOfflineMapAvailability> = emptyMap(),
    val preferredOfflineMapName: String? = null,
    val hasSelectedFolder: Boolean,
    val hasElevationData: Boolean = false,
    val hasSelectedElevationFolder: Boolean = false,
    val elevationFolderSync: PhoneElevationFolderSyncResult = PhoneElevationFolderSyncResult(),
    val hasSelectedRoutingFolder: Boolean = false,
    val routingFolderSync: PhoneRoutingFolderSyncResult = PhoneRoutingFolderSyncResult(),
    val themeConfig: PhoneOfflineThemeConfig,
    val settings: PhoneMapSettings = PhoneMapSettings(),
    val compassSettings: PhoneCompassSettings = PhoneCompassSettings(),
    val compassState: PhoneCompassState = PhoneCompassState(),
)

internal data class PhoneOfflineMapAvailability(
    val hasElevationData: Boolean = false,
    val hasRoutingData: Boolean = false,
    val bundleAreaId: String? = null,
)

internal fun phoneOfflineMapAvailability(
    map: PhoneOfflineMap,
    fallbackHasElevationData: Boolean,
    bundles: List<PhoneInstalledBundle>,
    healthByAreaId: Map<String, PhoneOfflineBundleHealth>,
): PhoneOfflineMapAvailability {
    val bundle =
        bundles.firstOrNull { it.mapFileName == map.displayName }
            ?: return PhoneOfflineMapAvailability(
                hasElevationData = fallbackHasElevationData,
            )
    val health = healthByAreaId[bundle.areaId]
    val available = health?.availableFileNames?.toSet()
    val hasElevationData =
        bundle.demTileIds.isNotEmpty() &&
            bundle.demTileIds.all { tileId ->
                available?.contains(tileId.uppercase(Locale.ROOT))
                    ?: bundle.downloadedDemTileIds.any { downloaded ->
                        downloaded.equals(tileId, ignoreCase = true)
                    }
            }
    val hasRoutingData =
        bundle.routingFileNames.isNotEmpty() &&
            bundle.routingFileNames.all { fileName ->
                val safeName = File(fileName).name
                available?.contains(safeName)
                    ?: bundle.downloadedRoutingFileNames.any { downloaded -> File(downloaded).name == safeName }
            }
    return PhoneOfflineMapAvailability(
        hasElevationData = hasElevationData,
        hasRoutingData = hasRoutingData,
        bundleAreaId = bundle.areaId,
    )
}

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
    val settings: PhoneGeneralSettings = PhoneGeneralSettings(),
    val sensorCapabilities: PhoneSensorCapabilities,
    val storageLocation: PhoneOfflineStorageLocation = PhoneOfflineStorageLocation.INTERNAL,
    val externalStorageAvailable: Boolean = false,
    val storageNeedsCanonicalMigration: Boolean = false,
    val storageMigration: PhoneOfflineStorageMigrationState = PhoneOfflineStorageMigrationState(),
)

internal data class MapToolsLayerState(
    val base: PhoneMapSource,
    val baseOnlineSource: PhoneOnlineMapSource,
    val offlineMaps: List<PhoneOfflineMap>,
    val onlineSources: List<PhoneOnlineMapSource>,
    val comparison: PhoneMapComparisonState,
    val hasElevationData: Boolean,
    val mapSettings: PhoneMapSettings,
)

internal data class MapToolsPanelState(
    val maps: MapToolsMapsState,
    val gpx: MapToolsGpxState,
    val poi: MapToolsPoiState,
    val layer: MapToolsLayerState,
    val general: MapToolsGeneralState,
)

internal data class MapToolsMapsActions(
    val onSelectOnline: (PhoneOnlineMapSource) -> Unit,
    val onSwitchToOnline: () -> Unit,
    val onSelectOffline: (PhoneOfflineMap) -> Unit,
    val onRenameOfflineMap: (PhoneOfflineMap) -> Unit,
    val onDeleteOfflineMap: (PhoneOfflineMap) -> Unit,
    val onDownloadElevationForBundleArea: (String) -> Unit,
    val onImportMap: () -> Unit,
    val onImportElevation: () -> Unit,
    val onSelectElevationFolder: () -> Unit,
    val onRescanElevationFolder: () -> Unit,
    val onClearElevationFolder: () -> Unit,
    val onSelectRoutingFolder: () -> Unit,
    val onRescanRoutingFolder: () -> Unit,
    val onClearRoutingFolder: () -> Unit,
    val onSelectFolder: () -> Unit,
    val onRescanFolder: () -> Unit,
    val onClearFolder: () -> Unit,
    val onOpenTheme: () -> Unit,
    val onOpenSettingsSection: (MapToolFeatureSettingsSection) -> Unit,
    val onSettingsChanged: (PhoneMapSettings) -> Unit,
    val onCompassSettingsChanged: (PhoneCompassSettings) -> Unit,
    val onCalibrateCompass: () -> Unit,
)

internal data class MapToolsPanelActions(
    val maps: MapToolsMapsActions,
    val onGpxVisibilityChanged: (Boolean) -> Unit,
    val onGpxItemToggled: (String) -> Unit,
    val onOpenGpxAnalysis: (PhoneMapGpxItem) -> Unit,
    val onRenameGpxItem: (PhoneMapGpxItem) -> Unit,
    val onDeleteGpxItem: (PhoneMapGpxItem) -> Unit,
    val onOpenRouteTools: () -> Unit,
    val onGpxSettingsChanged: (PhoneMapGpxSettings) -> Unit,
    val onSelectGpxFolder: () -> Unit,
    val onRescanGpxFolder: () -> Unit,
    val onClearGpxFolder: () -> Unit,
    val onPoiVisibilityChanged: (Boolean) -> Unit,
    val onPoiSourceVisibilityChanged: (String, Boolean) -> Unit,
    val onRenamePoiSource: (PhoneMapPoiSource) -> Unit,
    val onDeletePoiSource: (PhoneMapPoiSource) -> Unit,
    val onPoiSettingsChanged: (PhoneMapPoiSettings) -> Unit,
    val onComparisonLayerChanged: (PhoneMapComparisonLayer?) -> Unit,
    val onComparisonTransparencyChanged: (Float) -> Unit,
    val onLayerMapSettingsChanged: (PhoneMapSettings) -> Unit,
    val onFeatureSettings: (MapTool) -> Unit,
    val onFeatureSettingsSection: (MapToolFeatureSettingsSection) -> Unit,
    val onGeneralSettingsChanged: (PhoneGeneralSettings) -> Unit,
    val onOpenBundleDownload: () -> Unit,
    val onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit,
)

@Composable
@Suppress("FunctionNaming") // Public Compose entry points follow the project's screen naming convention.
internal fun MapToolPanelContent(
    tool: MapTool,
    contentMode: MapToolContentMode,
    featureSettingsSection: MapToolFeatureSettingsSection,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    if (contentMode == MapToolContentMode.FEATURE_SETTINGS) {
        MapToolFeatureSettingsContent(
            tool = tool,
            section = featureSettingsSection,
            state = state,
            actions = actions,
        )
    } else {
        when (tool) {
            MapTool.MAPS ->
                mapToolsMapsPanel(
                    state = state.maps,
                    actions = actions.maps,
                )
            MapTool.GPX ->
                mapToolsGpxPanel(
                    state = state.gpx,
                    onGlobalVisibilityChanged = actions.onGpxVisibilityChanged,
                    onItemToggled = actions.onGpxItemToggled,
                    onOpenAnalysis = actions.onOpenGpxAnalysis,
                    onRenameItem = actions.onRenameGpxItem,
                    onDeleteItem = actions.onDeleteGpxItem,
                    onOpenRouteTools = actions.onOpenRouteTools,
                )
            MapTool.POI ->
                mapToolsPoiPanel(
                    state = state.poi,
                    onGlobalVisibilityChanged = actions.onPoiVisibilityChanged,
                    onSourceVisibilityChanged = actions.onPoiSourceVisibilityChanged,
                    onRenameSource = actions.onRenamePoiSource,
                    onDeleteSource = actions.onDeletePoiSource,
                )
            MapTool.LAYER ->
                mapToolsLayerPanel(
                    state = state.layer,
                    onLayerChanged = actions.onComparisonLayerChanged,
                    onTransparencyChanged = actions.onComparisonTransparencyChanged,
                    onMapSettingsChanged = actions.onLayerMapSettingsChanged,
                )
            MapTool.SETTINGS ->
                mapToolsSettingsPanel(
                    section = MapToolFeatureSettingsSection.ROOT,
                    state = state.general,
                    onSettingsChanged = actions.onGeneralSettingsChanged,
                    onOpenBundleDownload = actions.onOpenBundleDownload,
                    onStorageChangeRequested = actions.onStorageChangeRequested,
                    onOpenSettingsSection = actions.onFeatureSettingsSection,
                )
        }
    }
}

@Composable
private fun mapToolsLayerPanel(
    state: MapToolsLayerState,
    onLayerChanged: (PhoneMapComparisonLayer?) -> Unit,
    onTransparencyChanged: (Float) -> Unit,
    onMapSettingsChanged: (PhoneMapSettings) -> Unit,
) {
    val layerOptions =
        comparisonLayerOptions(
            base = state.base,
            baseOnlineSource = state.baseOnlineSource,
            offlineMaps = state.offlineMaps,
            onlineSources = state.onlineSources,
        )
    var reliefOpacityPercent by
        remember(state.mapSettings.reliefOverlayOpacityPercent) {
            mutableStateOf(state.mapSettings.reliefOverlayOpacityPercent.toFloat())
        }
    mapToolPanelColumn {
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_layer_base_source)) },
            supportingContent = {
                Text(comparisonLayerLabel(state.base.asComparisonLayer(state.baseOnlineSource)))
            },
        )
        Text(stringResource(R.string.map_layer_select_source))
        if (layerOptions.isEmpty()) {
            Text(stringResource(R.string.map_layer_no_offline_maps))
        } else {
            layerOptions.forEach { layer ->
                ListItem(
                    headlineContent = { Text(comparisonLayerLabel(layer)) },
                    leadingContent = {
                        RadioButton(
                            selected = state.comparison.layer == layer,
                            onClick = { onLayerChanged(layer) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onLayerChanged(layer) },
                )
            }
        }
        if (!PhoneMapRendererCatalog.isOnlineSourceAvailable(PhoneOnlineMapSource.SATELLITE)) {
            Text(stringResource(R.string.map_layer_satellite_unavailable))
        }
        if (!PhoneMapRendererCatalog.isOnlineSourceAvailable(PhoneOnlineMapSource.TRACESTRACK_TOPO)) {
            Text(stringResource(R.string.map_layer_tracestrack_unavailable))
        }
        state.comparison.layer?.let {
            Text(
                stringResource(
                    R.string.map_layer_transparency,
                    state.comparison.transparencyPercent.toInt(),
                ),
            )
            Slider(
                value = state.comparison.transparencyPercent,
                onValueChange = onTransparencyChanged,
                valueRange = 0f..100f,
                steps = 99,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onLayerChanged(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_layer_clear))
            }
        }
        val reliefAvailable = state.base is PhoneMapSource.Offline && state.hasElevationData
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_layer_slope_title)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (reliefAvailable) {
                            R.string.map_layer_slope_available
                        } else {
                            R.string.map_layer_slope_unavailable
                        },
                    ),
                )
            },
            trailingContent = {
                Switch(
                    checked = reliefAvailable && state.mapSettings.reliefOverlayEnabled,
                    onCheckedChange = { enabled ->
                        onMapSettingsChanged(state.mapSettings.copy(reliefOverlayEnabled = enabled))
                    },
                    enabled = reliefAvailable,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (reliefAvailable && state.mapSettings.reliefOverlayEnabled) {
            Text(
                stringResource(
                    R.string.map_layer_slope_transparency,
                    100 - reliefOpacityPercent.toInt(),
                ),
            )
            Slider(
                value = reliefOpacityPercent,
                onValueChange = { opacity -> reliefOpacityPercent = opacity },
                onValueChangeFinished = {
                    onMapSettingsChanged(
                        state.mapSettings.copy(
                            reliefOverlayOpacityPercent = reliefOpacityPercent.toInt(),
                        ),
                    )
                },
                valueRange = 0f..100f,
                steps = 99,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun comparisonLayerOptions(
    base: PhoneMapSource,
    baseOnlineSource: PhoneOnlineMapSource,
    offlineMaps: List<PhoneOfflineMap>,
    onlineSources: List<PhoneOnlineMapSource>,
): List<PhoneMapComparisonLayer> =
    when (base) {
        PhoneMapSource.Online ->
            onlineSources
                .filterNot { source -> source == baseOnlineSource }
                .map(PhoneMapComparisonLayer::Online) +
                offlineMaps.map(PhoneMapComparisonLayer::Offline)
        is PhoneMapSource.Offline -> onlineSources.map(PhoneMapComparisonLayer::Online)
    }

@Composable
private fun comparisonLayerLabel(layer: PhoneMapComparisonLayer): String =
    when (layer) {
        is PhoneMapComparisonLayer.Online ->
            PhoneMapRendererCatalog.onlineSourceLabel(layer.source)
        is PhoneMapComparisonLayer.Offline -> layer.map.displayName
    }

private fun PhoneMapSource.asComparisonLayer(
    onlineSource: PhoneOnlineMapSource,
): PhoneMapComparisonLayer =
    when (this) {
        PhoneMapSource.Online -> PhoneMapComparisonLayer.Online(onlineSource)
        is PhoneMapSource.Offline -> PhoneMapComparisonLayer.Offline(map)
    }

@Composable
private fun mapToolsMapsPanel(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    mapToolPanelColumn {
        val isOffline = state.source is PhoneMapSource.Offline
        val offlineMapToActivate =
            state.preferredOfflineMapName
                ?.let { name -> state.offlineMaps.firstOrNull { map -> map.displayName == name } }
                ?: state.offlineMaps.firstOrNull()
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_source_selector_title)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (isOffline) R.string.map_source_offline_label else R.string.map_source_online,
                    ),
                )
            },
            trailingContent = {
                Switch(
                    checked = isOffline,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            offlineMapToActivate?.let(actions.onSelectOffline)
                        } else {
                            actions.onSwitchToOnline()
                        }
                    },
                    enabled = state.offlineMaps.isNotEmpty() || isOffline,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.source is PhoneMapSource.Offline) {
            if (state.offlineMaps.isEmpty()) {
                Text(stringResource(R.string.map_source_no_offline_maps))
            } else {
                val selectedOfflineMap = state.source.map
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    state.offlineMaps.forEach { offlineMap ->
                        val availability =
                            state.offlineMapAvailability[offlineMap.displayName]
                                ?: PhoneOfflineMapAvailability()
                        mapToolsOfflineMapRow(
                            map = offlineMap,
                            availability = availability,
                            selected = selectedOfflineMap == offlineMap,
                            onClick = { actions.onSelectOffline(offlineMap) },
                            onRename = { actions.onRenameOfflineMap(offlineMap) },
                            onDelete = { actions.onDeleteOfflineMap(offlineMap) },
                            onDownloadElevation = actions.onDownloadElevationForBundleArea,
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                state.onlineSources.forEach { onlineSource ->
                    val available = PhoneMapRendererCatalog.isOnlineSourceAvailable(onlineSource)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = available) { actions.onSelectOnline(onlineSource) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = onlineSource == state.selectedOnlineSource,
                            onClick = { actions.onSelectOnline(onlineSource) },
                            enabled = available,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = PhoneMapRendererCatalog.onlineSourceLabel(onlineSource))
                            if (!available) {
                                val messageResId =
                                    when (onlineSource) {
                                        PhoneOnlineMapSource.SATELLITE -> R.string.map_online_source_satellite_unavailable
                                        PhoneOnlineMapSource.TRACESTRACK_TOPO -> R.string.map_online_source_tracestrack_unavailable
                                        else -> R.string.map_online_source_unavailable
                                    }
                                Text(stringResource(messageResId))
                            }
                        }
                    }
                }
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
        mapToolsMapsQuickDisplaySettings(
            settings = state.settings,
            onSettingsChanged = actions.onSettingsChanged,
            showOfflineOnlyOptions = isOffline,
        )
    }
}

@Composable
private fun mapToolsOfflineMapRow(
    map: PhoneOfflineMap,
    availability: PhoneOfflineMapAvailability,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownloadElevation: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        ) {
            Text(
                text = map.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.map_source_capability,
                    stringResource(
                        if (availability.hasElevationData) {
                            R.string.map_source_capability_available
                        } else {
                            R.string.map_source_capability_missing
                        },
                    ),
                    stringResource(
                        if (availability.hasRoutingData) {
                            R.string.map_source_capability_available
                        } else {
                            R.string.map_source_capability_missing
                        },
                    ),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        availability.bundleAreaId?.let { areaId ->
            IconButton(
                onClick = { onDownloadElevation(areaId) },
                enabled = !availability.hasElevationData,
            ) {
                Icon(
                    imageVector =
                        if (availability.hasElevationData) {
                            Icons.Filled.Landscape
                        } else {
                            Icons.Filled.Download
                        },
                    contentDescription =
                        stringResource(
                            if (availability.hasElevationData) {
                                R.string.map_source_action_elevation_ready
                            } else {
                                R.string.map_source_action_download_elevation
                            },
                        ),
                )
            }
        }
        mapToolEditDeleteActions(
            onRename = onRename,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun mapToolsGpxPanel(
    state: MapToolsGpxState,
    onGlobalVisibilityChanged: (Boolean) -> Unit,
    onItemToggled: (String) -> Unit,
    onOpenAnalysis: (PhoneMapGpxItem) -> Unit,
    onRenameItem: (PhoneMapGpxItem) -> Unit,
    onDeleteItem: (PhoneMapGpxItem) -> Unit,
    onOpenRouteTools: () -> Unit,
) {
    mapToolPanelColumn {
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_tools_gpx_global_visibility)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.map_tools_gpx_visibility_count,
                        state.items.count(PhoneMapGpxItem::enabled),
                        state.items.size,
                    ),
                )
            },
            trailingContent = {
                Switch(
                    checked = state.globalVisible,
                    onCheckedChange = onGlobalVisibilityChanged,
                )
            },
        )
        when {
            state.isLoading -> Text(stringResource(R.string.map_tools_gpx_loading))
            state.items.isEmpty() -> Text(stringResource(R.string.map_tools_gpx_empty))
            else ->
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    state.items.forEach { item ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onItemToggled(item.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.displayName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = item.enabled,
                                    onCheckedChange = { onItemToggled(item.id) },
                                )
                                IconButton(onClick = { onOpenAnalysis(item) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Timeline,
                                        contentDescription =
                                            stringResource(R.string.map_tools_gpx_open_analysis),
                                    )
                                }
                                mapToolEditDeleteActions(
                                    onRename = { onRenameItem(item) },
                                    onDelete = { onDeleteItem(item) },
                                    enabled = item.isEditable,
                                )
                            }
                        }
                    }
                }
        }
        OutlinedButton(onClick = onOpenRouteTools, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_title))
        }
    }
}

@Composable
private fun mapToolsPoiPanel(
    state: MapToolsPoiState,
    onGlobalVisibilityChanged: (Boolean) -> Unit,
    onSourceVisibilityChanged: (String, Boolean) -> Unit,
    onRenameSource: (PhoneMapPoiSource) -> Unit,
    onDeleteSource: (PhoneMapPoiSource) -> Unit,
) {
    mapToolPanelColumn {
        val enabledSources = state.sources.filter { source -> source.isReadable && source.isEnabled }
        ListItem(
            headlineContent = { Text(stringResource(R.string.map_tools_poi_global_visibility)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.map_tools_poi_visibility_count,
                        enabledSources.size,
                        state.sources.size,
                        enabledSources.sumOf { source -> source.poiCount ?: 0 },
                    ),
                )
            },
            trailingContent = {
                Switch(
                    checked = state.globalVisible,
                    onCheckedChange = onGlobalVisibilityChanged,
                )
            },
        )
        Text(stringResource(R.string.map_tools_poi_sources_heading))
        if (state.sources.isEmpty()) {
            Text(stringResource(R.string.map_tools_poi_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                state.sources.forEach { source ->
                    val sourceSummary =
                        when {
                            !source.isReadable -> stringResource(R.string.map_tools_poi_source_unreadable)
                            source.poiCount != null ->
                                stringResource(R.string.map_tools_poi_source_count, source.poiCount)
                            else -> stringResource(R.string.map_tools_poi_source_available)
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = source.isReadable) {
                                    onSourceVisibilityChanged(source.fileName, !source.isEnabled)
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.fileName.substringBeforeLast("."),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sourceSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = source.isEnabled,
                                onCheckedChange = { enabled ->
                                    onSourceVisibilityChanged(source.fileName, enabled)
                                },
                                enabled = source.isReadable,
                            )
                            mapToolEditDeleteActions(
                                onRename = { onRenameSource(source) },
                                onDelete = { onDeleteSource(source) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun mapToolEditDeleteActions(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onRename, enabled = enabled) {
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = stringResource(R.string.map_content_action_rename),
        )
    }
    IconButton(onClick = onDelete, enabled = enabled) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.map_content_action_delete),
        )
    }
}

@Composable
@Suppress("LongMethod") // General settings keep their compact root and sensor detail sections together.
internal fun mapToolsSettingsPanel(
    section: MapToolFeatureSettingsSection,
    state: MapToolsGeneralState,
    onSettingsChanged: (PhoneGeneralSettings) -> Unit,
    onOpenBundleDownload: () -> Unit,
    onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit,
    onOpenSettingsSection: (MapToolFeatureSettingsSection) -> Unit,
) {
    when (section) {
        MapToolFeatureSettingsSection.ROOT ->
            mapToolPanelColumn(verticalSpacing = 4.dp) {
                Text(stringResource(R.string.map_tools_settings_heading))
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_settings_data_title),
                    summary =
                        stringResource(
                            R.string.map_tools_settings_data_summary,
                            state.storageLocation.label,
                        ),
                    onClick = {
                        onOpenSettingsSection(MapToolFeatureSettingsSection.GENERAL_DATA)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_settings_units),
                    summary =
                        stringResource(
                            if (state.settings.isMetric) {
                                R.string.map_tools_settings_units_metric
                            } else {
                                R.string.map_tools_settings_units_imperial
                            },
                        ),
                    onClick = {
                        onSettingsChanged(state.settings.copy(isMetric = !state.settings.isMetric))
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_settings_activity_profile),
                    summary =
                        stringResource(
                            R.string.map_tools_settings_activity_summary,
                            stringResource(
                                if (state.settings.activityProfile == PhoneActivityProfile.BIKE) {
                                    R.string.map_tools_settings_activity_bike
                                } else {
                                    R.string.map_tools_settings_activity_hike
                                },
                            ),
                        ),
                    onClick = {
                        onOpenSettingsSection(MapToolFeatureSettingsSection.GENERAL_ACTIVITY)
                    },
                )
                mapToolsSettingsSection(
                    title = stringResource(R.string.map_tools_settings_sensors_title),
                    summary = stringResource(R.string.map_tools_settings_sensors_summary),
                    onClick = {
                        onOpenSettingsSection(MapToolFeatureSettingsSection.GENERAL_SENSORS)
                    },
                )
            }

        MapToolFeatureSettingsSection.GENERAL_DATA ->
            mapToolsGeneralDataSettings(
                state = state,
                onOpenBundleDownload = onOpenBundleDownload,
                onStorageChangeRequested = onStorageChangeRequested,
            )

        MapToolFeatureSettingsSection.GENERAL_ACTIVITY ->
            mapToolPanelColumn(verticalSpacing = 8.dp) {
                mapToolsActivitySettings(
                    settings = state.settings,
                    onSettingsChanged = onSettingsChanged,
                )
            }

        MapToolFeatureSettingsSection.GENERAL_SENSORS ->
            mapToolsSensorSettings(state.sensorCapabilities)

        else -> Unit
    }
}

@Composable
private fun mapToolsGeneralDataSettings(
    state: MapToolsGeneralState,
    onOpenBundleDownload: () -> Unit,
    onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit,
) {
    mapToolPanelColumn(verticalSpacing = 8.dp) {
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
    }
}

@Composable
private fun mapToolsSensorSettings(capabilities: PhoneSensorCapabilities) {
    mapToolPanelColumn(verticalSpacing = 8.dp) {
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
internal fun mapToolPanelColumn(
    verticalSpacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        content()
        Spacer(modifier = Modifier.height(8.dp))
    }
}
