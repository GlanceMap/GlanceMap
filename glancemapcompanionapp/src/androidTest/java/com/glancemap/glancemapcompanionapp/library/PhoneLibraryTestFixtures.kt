package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import androidx.compose.runtime.MutableState
import com.glancemap.glancemapcompanionapp.map.*
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibrarySummary

internal fun libraryTestMapState(context: Context): MapToolsPanelState =
    MapToolsPanelState(
        maps =
            MapToolsMapsState(
                source = PhoneMapSource.Online,
                onlineSources = PhoneMapRendererCatalog.availableOnlineSources(),
                selectedOnlineSource = PhoneOnlineMapSource.OPEN_STREET_MAP,
                offlineMaps = emptyList(),
                hasSelectedFolder = false,
                themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
            ),
        gpx =
            MapToolsGpxState(
                items = listOf(libraryTestTrack()),
                isLoading = false,
                globalVisible = true,
                settings = PhoneMapGpxSettings(),
                routeLibrarySourceCount = 1,
                hasSelectedFolder = false,
                selectedFolderName = null,
                folderError = null,
            ),
        poi = MapToolsPoiState(listOf(PhoneMapPoiSource("spring.poi", true, false)), globalVisible = true),
        layer =
            MapToolsLayerState(
                base = PhoneMapSource.Online,
                baseOnlineSource = PhoneOnlineMapSource.OPEN_STREET_MAP,
                offlineMaps = emptyList(),
                onlineSources = emptyList(),
                comparison = PhoneMapComparisonState(),
                hasElevationData = false,
                mapSettings = PhoneMapSettings(),
            ),
        general = MapToolsGeneralState(sensorCapabilities = resolvePhoneSensorCapabilities(context)),
    )

internal fun libraryTestMapActions(state: MutableState<MapToolsPanelState>): MapToolsPanelActions =
    MapToolsPanelActions(
        maps = libraryTestMapSelectionActions(state),
        onGpxVisibilityChanged = {},
        onGpxItemToggled = { id ->
            state.value =
                state.value.copy(
                    gpx =
                        state.value.gpx.copy(
                            items =
                                state.value.gpx.items
                                    .toggleEnabled(id),
                        ),
                )
        },
        onOpenGpxAnalysis = {},
        onRenameGpxItem = {},
        onDeleteGpxItem = {},
        onOpenRouteTools = {},
        onGpxSettingsChanged = {},
        onSelectGpxFolder = {},
        onRescanGpxFolder = {},
        onClearGpxFolder = {},
        onPoiVisibilityChanged = {},
        onPoiSourceVisibilityChanged = { id, enabled ->
            val sources =
                state.value.poi.sources
                    .map { if (it.fileName == id) it.copy(isEnabled = enabled) else it }
            state.value = state.value.copy(poi = state.value.poi.copy(sources = sources))
        },
        onRenamePoiSource = {},
        onDeletePoiSource = {},
        onPoiSettingsChanged = {},
        onComparisonLayerChanged = {},
        onComparisonTransparencyChanged = {},
        onLayerMapSettingsChanged = {},
        onFeatureSettings = {},
        onFeatureSettingsSection = {},
        onGeneralSettingsChanged = {},
        onOpenBundleDownload = {},
        onStorageChangeRequested = {},
        onStorageMigrationCancelled = {},
    )

private fun libraryTestMapSelectionActions(state: MutableState<MapToolsPanelState>) =
    MapToolsMapsActions(
        onSelectOnline = {
            state.value =
                state.value.copy(
                    maps =
                        state.value.maps.copy(
                            source = PhoneMapSource.Online,
                            selectedOnlineSource = it,
                        ),
                )
        },
        onSwitchToOnline = {},
        onSelectOffline = {
            state.value = state.value.copy(maps = state.value.maps.copy(source = PhoneMapSource.Offline(it)))
        },
        onRenameOfflineMap = {},
        onDeleteOfflineMap = {},
        onDownloadElevationForBundleArea = {},
        onImportMap = {},
        onImportElevation = {},
        onSelectElevationFolder = {},
        onRescanElevationFolder = {},
        onClearElevationFolder = {},
        onSelectRoutingFolder = {},
        onRescanRoutingFolder = {},
        onClearRoutingFolder = {},
        onSelectFolder = {},
        onRescanFolder = {},
        onClearFolder = {},
        onOpenTheme = {},
        onOpenSettingsSection = {},
        onSettingsChanged = {},
        onCompassSettingsChanged = {},
        onCalibrateCompass = {},
    )

internal fun libraryTestActions(
    onSettings: (MapTool) -> Unit = {},
    onFilter: (PhoneLibraryFilter) -> Unit = {},
) = PhoneLibraryActions(
    onRefresh = {},
    onImportGpx = { _, _, _ -> },
    onUseRoute = {},
    onOpenMapTools = {},
    onOpenSettings = onSettings,
    onMapAction = {},
    onSendGpx = {},
    onFilterChanged = onFilter,
)

internal fun libraryTestRoute() =
    RouteLibraryRoute(
        "route",
        "Forest loop",
        "route.gpx",
        1L,
        RouteLibrarySummary(1000.0, 100.0, 80.0, 1000.0, 0, 1000.0, 100.0),
    )

internal fun libraryTestTrack() = PhoneMapGpxItem("route", "Forest loop", PhoneMapGpxTrack("route", emptyList()), false)
