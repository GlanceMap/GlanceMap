package com.glancemap.glancemapcompanionapp.map

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.mapSaver
import com.glancemap.trailcore.map.MapContentVisibility
import java.io.File

/** Save only presentation values and source identities; renderer handles and requests are transient. */
internal val phoneMapUiStateSaver =
    mapSaver<PhoneMapUiState>(
        save = { state -> savePhoneMapUiState(state) },
        restore = { values -> restorePhoneMapUiState(values) },
    )

internal val phoneMapCameraStateSaver =
    mapSaver<PhoneMapCameraState>(
        save = { state ->
            mapOf(
                "latitude" to state.camera.latitude,
                "longitude" to state.camera.longitude,
                "zoom" to state.camera.zoom,
                "bearing" to state.camera.bearingDegrees,
                "resolution" to state.camera.groundResolutionMetersPerPixel,
                "publisher" to state.publisher.name,
            )
        },
        restore = { values ->
            runCatching {
                PhoneMapCameraState(
                    camera =
                        PhoneMapCameraSnapshot(
                            latitude = values["latitude"] as Double,
                            longitude = values["longitude"] as Double,
                            zoom = values["zoom"] as Double,
                            bearingDegrees = values["bearing"] as Float,
                            groundResolutionMetersPerPixel = values["resolution"] as Double?,
                        ),
                    publisher = PhoneMapCameraRenderer.valueOf(values["publisher"] as String),
                )
            }.getOrNull()
        },
    )

internal val phoneMapDistanceMeasurementSaver =
    listSaver<PhoneMapDistanceMeasurement?, Double>(
        save = { measurement ->
            measurement
                ?.let {
                    listOf(it.first.latitude, it.first.longitude, it.second.latitude, it.second.longitude)
                }.orEmpty()
        },
        restore = { coordinates ->
            coordinates.takeIf { it.size == 4 }?.let {
                PhoneMapDistanceMeasurement(
                    first = PhoneMapCoordinate(it[0], it[1]),
                    second = PhoneMapCoordinate(it[2], it[3]),
                )
            }
        },
    )

private fun savePhoneMapUiState(state: PhoneMapUiState): Map<String, Any?> {
    val offlineBase = (state.source as? PhoneMapSource.Offline)?.map
    val offlineComparison = (state.comparison.layer as? PhoneMapComparisonLayer.Offline)?.map
    return mapOf(
        "basePath" to offlineBase?.selectionIdentity,
        "baseRevision" to offlineBase?.contentRevision,
        "comparisonPath" to offlineComparison?.selectionIdentity,
        "comparisonRevision" to offlineComparison?.contentRevision,
        "comparisonOnline" to (state.comparison.layer as? PhoneMapComparisonLayer.Online)?.source?.name,
        "transparency" to state.comparison.transparencyPercent,
        "gpxVisible" to state.contentVisibility.gpxTracks,
        "routesVisible" to state.contentVisibility.routes,
        "poisVisible" to state.contentVisibility.pois,
        "tool" to state.toolPanel.activeTool?.name,
        "panelMode" to state.toolPanel.mode.name,
        "panelFraction" to state.toolPanel.splitFraction,
        "panelCustomFraction" to state.toolPanel.customSplitFraction,
        "contentMode" to state.toolPanel.contentMode.name,
        "settingsSection" to state.toolPanel.featureSettingsSection.name,
        "launcherExpanded" to state.toolLauncherExpanded,
        "orientation" to state.mapMode.orientation.name,
        "follow" to state.mapMode.follow.name,
        "manualBearing" to state.mapMode.manualBearingDegrees,
    )
}

private fun restorePhoneMapUiState(values: Map<String, Any?>): PhoneMapUiState? =
    runCatching {
        val onlineComparison =
            PhoneOnlineMapSource.entries
                .firstOrNull { it.name == values["comparisonOnline"] }
                ?.takeIf(PhoneMapRendererCatalog::isOnlineSourceAvailable)
        PhoneMapUiState(
            source = values.offlineMap("base")?.let(PhoneMapSource::Offline) ?: PhoneMapSource.Online,
            comparison =
                PhoneMapComparisonState(
                    layer =
                        onlineComparison?.let(PhoneMapComparisonLayer::Online)
                            ?: values.offlineMap("comparison")?.let(PhoneMapComparisonLayer::Offline),
                    transparencyPercent = values["transparency"] as Float,
                ),
            contentVisibility =
                MapContentVisibility(
                    gpxTracks = values["gpxVisible"] as Boolean,
                    routes = values["routesVisible"] as Boolean,
                    pois = values["poisVisible"] as Boolean,
                ),
            toolPanel =
                MapToolPanelState(
                    activeTool = (values["tool"] as String?)?.let(MapTool::valueOf),
                    mode = MapToolPanelMode.valueOf(values["panelMode"] as String),
                    splitFraction = values["panelFraction"] as? Float ?: DEFAULT_MAP_TOOL_PANEL_FRACTION,
                    customSplitFraction = values["panelCustomFraction"] as? Float,
                    contentMode = MapToolContentMode.valueOf(values["contentMode"] as String),
                    featureSettingsSection = MapToolFeatureSettingsSection.valueOf(values["settingsSection"] as String),
                ).normalizedSizing(),
            toolLauncherExpanded = values["launcherExpanded"] as Boolean,
            mapMode =
                PhoneMapMode(
                    orientation = PhoneMapOrientation.valueOf(values["orientation"] as String),
                    follow = PhoneMapFollowMode.valueOf(values["follow"] as String),
                    manualBearingDegrees = values["manualBearing"] as Float?,
                ),
        )
    }.getOrNull()

private fun Map<String, Any?>.offlineMap(prefix: String): PhoneOfflineMap? =
    (this["${prefix}Path"] as String?)?.let { path ->
        PhoneOfflineMap(File(path), contentRevision = this["${prefix}Revision"] as String)
    }

internal val phoneMapLegendViewsSaver =
    listSaver<List<PhoneMapLegendView>, String>(
        save = { views -> views.flatMap { listOf(it.label, it.sourceLabel, it.themeId.orEmpty()) } },
        restore = { values ->
            values.chunked(3).mapNotNull { view ->
                view.takeIf { it.size == 3 }?.let {
                    PhoneMapLegendView(it[0], it[1], it[2].ifEmpty { null })
                }
            }
        },
    )
