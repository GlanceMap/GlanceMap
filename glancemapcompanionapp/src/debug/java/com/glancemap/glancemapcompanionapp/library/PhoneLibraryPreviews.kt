package com.glancemap.glancemapcompanionapp.library

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.glancemap.glancemapcompanionapp.map.*
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibrarySummary
import com.glancemap.glancemapcompanionapp.ui.theme.GlanceMapTheme
import org.mapsforge.core.model.BoundingBox
import java.io.File

@Preview(name = "Phone", widthDp = 360, heightDp = 800)
@Preview(name = "Dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Small, large text", widthDp = 320, heightDp = 640, fontScale = 1.3f)
private annotation class LibraryPreviews

@LibraryPreviews
@Composable
internal fun libraryRoutesPreview() {
    libraryPreview(PhoneLibraryListState(previewRouteItems(), PhoneLibraryFilter.GPX))
}

@LibraryPreviews
@Composable
internal fun libraryRoutesWithFoldersPreview() {
    libraryPreview(
        PhoneLibraryListState(
            previewRouteItems(),
            PhoneLibraryFilter.GPX,
            organization = previewOrganization(),
        ),
    )
}

@LibraryPreviews
@Composable
internal fun libraryPoisPreview() {
    libraryPreview(
        PhoneLibraryListState(
            previewPoiSources().map(PhoneLibraryItem::Pois),
            PhoneLibraryFilter.POIS,
        ),
    )
}

@LibraryPreviews
@Composable
internal fun libraryMapsOnlinePreview() {
    libraryPreview(PhoneLibraryListState(previewMapItems(), PhoneLibraryFilter.MAPS))
}

@LibraryPreviews
@Composable
internal fun libraryMapsOfflinePreview() {
    libraryPreview(
        PhoneLibraryListState(previewMapItems(), PhoneLibraryFilter.MAPS),
        previewMapState(PhoneMapSource.Offline(previewMaps.first())),
    )
}

@LibraryPreviews
@Composable
internal fun libraryOfflineDataPreview() {
    libraryPreview(PhoneLibraryListState(listOf(previewBundleItem()), PhoneLibraryFilter.BUNDLES))
}

@LibraryPreviews
@Composable
internal fun libraryEmptyPreview() {
    libraryPreview(PhoneLibraryListState(emptyList(), PhoneLibraryFilter.GPX))
}

@Composable
private fun libraryPreview(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState = previewMapState(PhoneMapSource.Online),
) {
    GlanceMapTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                phoneLibraryHeader(state.filter) {}
                Box(Modifier.weight(1f)) {
                    libraryPanelContent(state, mapState, previewMapActions(), previewLibraryActions())
                }
            }
        }
    }
}

private val previewMaps =
    listOf(
        PhoneOfflineMap(File("Alps.map"), contentRevision = "preview"),
        PhoneOfflineMap(File("Pyrenees.map"), contentRevision = "preview"),
        PhoneOfflineMap(File("my-map.map"), contentRevision = "preview"),
    )

private fun previewMapItems(): List<PhoneLibraryItem> {
    val bounds = BoundingBox(45.0, 6.0, 46.0, 7.0)
    return listOf(
        PhoneLibraryItem.OfflineMap(
            previewMaps[0],
            2_100_000_000L,
            PhoneLibraryMapCoverage(bounds, 4, 4, 2, 2),
        ),
        PhoneLibraryItem.OfflineMap(
            previewMaps[1],
            1_400_000_000L,
            PhoneLibraryMapCoverage(bounds, 6, 2, 2, 0),
        ),
        PhoneLibraryItem.OfflineMap(previewMaps[2], 800_000_000L, PhoneLibraryMapCoverage(bounds = null)),
    )
}

private fun previewRoute(
    id: String,
    name: String,
    distanceMeters: Double,
    gainMeters: Double,
    isRecording: Boolean = false,
) = RouteLibraryRoute(
    id = id,
    displayName = name,
    storedFileName = "$id.gpx",
    importedAtMillis = 1L,
    summary = RouteLibrarySummary(distanceMeters, gainMeters, gainMeters, 3600.0, 0, 1000.0, 100.0),
    isRecording = isRecording,
)

private fun previewRouteItems(): List<PhoneLibraryItem> =
    listOf(
        PhoneLibraryItem.Gpx(previewRoute("tmb", "Tour du Mont Blanc day 3", 12_400.0, 850.0)),
        PhoneLibraryItem.Gpx(previewRoute("ridge", "Aiguilles Rouges ridge traverse via Lac Blanc", 18_750.0, 1_320.0)),
        PhoneLibraryItem.Gpx(previewRoute("morning", "Morning hike", 6_200.0, 310.0, isRecording = true)),
        PhoneLibraryItem.FolderGpx(previewTrack("folder:lakes", "Lakes loop", enabled = false)),
        PhoneLibraryItem.ReceivedGpx(File("watch-track.gpx"), 48_000L, 1L),
    )

private fun previewTrack(
    id: String,
    name: String,
    enabled: Boolean,
) = PhoneMapGpxItem(id, name, PhoneMapGpxTrack(id, emptyList()), enabled)

private fun previewPoiSources() =
    listOf(
        PhoneMapPoiSource("Alps refuges.poi", isReadable = true, isEnabled = true, poiCount = 412),
        PhoneMapPoiSource("Springs.poi", isReadable = true, isEnabled = false, poiCount = 87),
        PhoneMapPoiSource("damaged.poi", isReadable = false),
    )

private fun previewBundleItem() =
    PhoneLibraryItem.Bundle(
        bundle =
            PhoneInstalledBundle(
                areaId = "alps",
                areaLabel = "Alps",
                mapFileName = "Alps.map",
                poiFileName = "Alps.poi",
                installedAtMillis = 1L,
            ),
        health = PhoneOfflineBundleHealth(PhoneOfflineBundleStatus.COMPLETE),
        map = previewMaps.first(),
    )

private fun previewOrganization() =
    PhoneLibraryOrganization(
        folders =
            listOf(
                PhoneLibraryFolder("summer", "Summer 2026"),
                PhoneLibraryFolder("ski", "Ski touring"),
            ),
    )

private fun previewMapState(source: PhoneMapSource): MapToolsPanelState {
    val onlineSources = PhoneMapRendererCatalog.availableOnlineSources()
    return MapToolsPanelState(
        maps =
            MapToolsMapsState(
                source = source,
                onlineSources = onlineSources,
                selectedOnlineSource = PhoneOnlineMapSource.OPEN_STREET_MAP,
                offlineMaps = previewMaps,
                hasSelectedFolder = false,
                themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
            ),
        gpx =
            MapToolsGpxState(
                items =
                    listOf(
                        previewTrack("tmb", "Tour du Mont Blanc day 3", enabled = true),
                        previewTrack("ridge", "Aiguilles Rouges ridge traverse via Lac Blanc", enabled = false),
                        previewTrack("morning", "Morning hike", enabled = true),
                        previewTrack("folder:lakes", "Lakes loop", enabled = false),
                    ),
                isLoading = false,
                globalVisible = true,
                settings = PhoneMapGpxSettings(),
                routeLibrarySourceCount = 3,
                hasSelectedFolder = false,
                selectedFolderName = null,
                folderError = null,
            ),
        poi = MapToolsPoiState(previewPoiSources(), globalVisible = true),
        layer =
            MapToolsLayerState(
                base = source,
                baseOnlineSource = PhoneOnlineMapSource.OPEN_STREET_MAP,
                offlineMaps = previewMaps,
                onlineSources = onlineSources,
                comparison = PhoneMapComparisonState(),
                hasElevationData = false,
                mapSettings = PhoneMapSettings(),
            ),
        general = MapToolsGeneralState(sensorCapabilities = previewSensorCapabilities()),
    )
}

// Built directly because the preview renderer has no real sensor service to query.
private fun previewSensorCapabilities() =
    PhoneSensorCapabilities(
        gpsAvailable = true,
        compassFeatureAvailable = true,
        headingSensorAvailable = true,
        rotationVectorAvailable = true,
        accelerometerAvailable = true,
        magnetometerAvailable = true,
        gyroscopeAvailable = true,
        barometerAvailable = true,
        stepDetectorAvailable = true,
        stepCounterAvailable = true,
    )

private fun previewLibraryActions() =
    PhoneLibraryActions(
        onRefresh = {},
        onImportGpx = { _, _, _ -> },
        onUseRoute = {},
        onOpenMapTools = {},
        onOpenSettings = {},
        onMapAction = {},
        onSendGpx = {},
        onFilterChanged = {},
    )

private fun previewMapActions() =
    MapToolsPanelActions(
        maps = previewMapSelectionActions(),
        onGpxVisibilityChanged = {},
        onGpxItemToggled = {},
        onOpenGpxAnalysis = {},
        onRenameGpxItem = {},
        onDeleteGpxItem = {},
        onOpenRouteTools = {},
        onGpxSettingsChanged = {},
        onSelectGpxFolder = {},
        onRescanGpxFolder = {},
        onClearGpxFolder = {},
        onPoiVisibilityChanged = {},
        onPoiSourceVisibilityChanged = { _, _ -> },
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

private fun previewMapSelectionActions() =
    MapToolsMapsActions(
        onSelectOnline = {},
        onSwitchToOnline = {},
        onSelectOffline = {},
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
