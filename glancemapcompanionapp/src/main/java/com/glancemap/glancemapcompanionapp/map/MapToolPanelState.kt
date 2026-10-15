package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapContentVisibility

/** The shared phone-map tools; each uses the same panel scaffold. */
internal enum class MapTool {
    POI,
    REC,
    GPX,
    GPX_TOOLS,
    MAPS,
    LAYER,
    LIBRARY,
    HIKE,
    SETTINGS,
}

/** Content tools with dedicated settings and existing management pages. */
internal val libraryMapTools = listOf(MapTool.POI, MapTool.GPX, MapTool.MAPS)

internal enum class MapToolPanelMode {
    CLOSED,
    SPLIT,
    EXPANDED,
}

internal enum class MapToolContentMode {
    MAIN,
    FEATURE_SETTINGS,
}

internal enum class MapToolFeatureSettingsSection {
    ROOT,
    MAP_DATA,
    MAP_THEME,
    MAP_DISPLAY,
    MAP_TERRAIN,
    MAP_COMPASS,
    MAP_ZOOM,
    GPX_SOURCES,
    GPX_APPEARANCE,
    GPX_ANALYSIS,
    POI_SOURCES,
    POI_APPEARANCE,
    GENERAL_DATA,
    GENERAL_ACTIVITY,
    GENERAL_SENSORS,
}

/** Pure panel navigation state, deliberately separate from map renderer and content state. */
internal data class MapToolPanelState(
    val activeTool: MapTool? = null,
    val mode: MapToolPanelMode = MapToolPanelMode.CLOSED,
    val contentMode: MapToolContentMode = MapToolContentMode.MAIN,
    val featureSettingsSection: MapToolFeatureSettingsSection = MapToolFeatureSettingsSection.ROOT,
    val splitFraction: Float = DEFAULT_MAP_TOOL_PANEL_FRACTION,
    val customSplitFraction: Float? = null,
) {
    val hasFeatureSettingsBack: Boolean
        get() = contentMode == MapToolContentMode.FEATURE_SETTINGS

    fun select(tool: MapTool): MapToolPanelState =
        when {
            activeTool == tool && mode != MapToolPanelMode.CLOSED -> close()
            tool == MapTool.HIKE || tool == MapTool.LIBRARY ->
                copy(
                    activeTool = tool,
                    mode = MapToolPanelMode.SPLIT,
                    contentMode = MapToolContentMode.MAIN,
                )
            mode == MapToolPanelMode.CLOSED -> copy(activeTool = tool, mode = MapToolPanelMode.SPLIT)
            else -> copy(activeTool = tool, contentMode = MapToolContentMode.MAIN)
        }

    fun showFeatureSettings(): MapToolPanelState =
        takeIf { activeTool in featureSettingsTools }
            ?.copy(
                contentMode = MapToolContentMode.FEATURE_SETTINGS,
                featureSettingsSection = MapToolFeatureSettingsSection.ROOT,
            )
            ?: this

    fun showFeatureSettingsSection(section: MapToolFeatureSettingsSection): MapToolPanelState =
        takeIf {
            activeTool in featureSettingsTools &&
                activeTool?.let { tool -> sectionBelongsToTool(section, tool) } == true
        }?.copy(
            contentMode = MapToolContentMode.FEATURE_SETTINGS,
            featureSettingsSection = section,
        )
            ?: this

    fun showMainContent(): MapToolPanelState =
        copy(
            contentMode = MapToolContentMode.MAIN,
            featureSettingsSection = MapToolFeatureSettingsSection.ROOT,
        )

    fun expand(): MapToolPanelState =
        takeIf { mode == MapToolPanelMode.SPLIT }
            ?.copy(mode = MapToolPanelMode.EXPANDED)
            ?: this

    fun collapse(): MapToolPanelState =
        takeIf { mode == MapToolPanelMode.EXPANDED }
            ?.copy(mode = MapToolPanelMode.SPLIT)
            ?: this

    fun back(): MapToolPanelState =
        when {
            contentMode == MapToolContentMode.FEATURE_SETTINGS &&
                featureSettingsSection != MapToolFeatureSettingsSection.ROOT ->
                copy(featureSettingsSection = MapToolFeatureSettingsSection.ROOT)
            contentMode == MapToolContentMode.FEATURE_SETTINGS -> showMainContent()
            else ->
                when (mode) {
                    MapToolPanelMode.EXPANDED -> collapse()
                    MapToolPanelMode.SPLIT -> close()
                    MapToolPanelMode.CLOSED -> this
                }
        }

    fun close(): MapToolPanelState =
        MapToolPanelState(
            splitFraction = splitFraction,
            customSplitFraction = customSplitFraction,
        )

    private fun sectionBelongsToTool(
        section: MapToolFeatureSettingsSection,
        tool: MapTool,
    ): Boolean =
        when (tool) {
            MapTool.MAPS -> section in mapFeatureSettingsSections
            MapTool.GPX -> section in gpxFeatureSettingsSections
            MapTool.POI -> section in poiFeatureSettingsSections
            MapTool.LAYER,
            MapTool.LIBRARY,
            MapTool.HIKE,
            MapTool.REC,
            MapTool.GPX_TOOLS,
            -> false
            MapTool.SETTINGS -> section in generalFeatureSettingsSections
        }

    private companion object {
        val featureSettingsTools = setOf(MapTool.POI, MapTool.GPX, MapTool.MAPS, MapTool.SETTINGS)
        val mapFeatureSettingsSections =
            setOf(
                MapToolFeatureSettingsSection.ROOT,
                MapToolFeatureSettingsSection.MAP_DATA,
                MapToolFeatureSettingsSection.MAP_THEME,
                MapToolFeatureSettingsSection.MAP_DISPLAY,
                MapToolFeatureSettingsSection.MAP_TERRAIN,
                MapToolFeatureSettingsSection.MAP_COMPASS,
                MapToolFeatureSettingsSection.MAP_ZOOM,
            )
        val gpxFeatureSettingsSections =
            setOf(
                MapToolFeatureSettingsSection.ROOT,
                MapToolFeatureSettingsSection.GPX_SOURCES,
                MapToolFeatureSettingsSection.GPX_APPEARANCE,
                MapToolFeatureSettingsSection.GPX_ANALYSIS,
            )
        val poiFeatureSettingsSections =
            setOf(
                MapToolFeatureSettingsSection.ROOT,
                MapToolFeatureSettingsSection.POI_SOURCES,
                MapToolFeatureSettingsSection.POI_APPEARANCE,
            )
        val generalFeatureSettingsSections =
            setOf(
                MapToolFeatureSettingsSection.ROOT,
                MapToolFeatureSettingsSection.GENERAL_DATA,
                MapToolFeatureSettingsSection.GENERAL_ACTIVITY,
                MapToolFeatureSettingsSection.GENERAL_SENSORS,
            )
    }
}

/** The optional upper map source used to compare online and offline coverage. */
internal sealed interface PhoneMapComparisonLayer {
    data class Online(
        val source: PhoneOnlineMapSource,
    ) : PhoneMapComparisonLayer

    data class Offline(
        val map: PhoneOfflineMap,
    ) : PhoneMapComparisonLayer
}

/** Keeps comparison presentation separate from the selected base map and its saved preference. */
internal data class PhoneMapComparisonState(
    val layer: PhoneMapComparisonLayer? = null,
    val transparencyPercent: Float = DEFAULT_PHONE_MAP_COMPARISON_TRANSPARENCY_PERCENT,
    val selectionGeneration: Long = 0L,
    val validationRequestGeneration: Long = 0L,
) {
    @Suppress("MaxLineLength") // Keep this compact immutable state helper readable at its call sites.
    fun withTransparency(percent: Float): PhoneMapComparisonState = copy(transparencyPercent = percent.coerceIn(0f, 100f))

    fun isAvailableFor(
        base: PhoneMapSource,
        offlineMaps: List<PhoneOfflineMap>,
    ): Boolean =
        when (val selectedLayer = layer) {
            null -> true
            is PhoneMapComparisonLayer.Online ->
                base is PhoneMapSource.Online || base is PhoneMapSource.Offline
            is PhoneMapComparisonLayer.Offline ->
                base == PhoneMapSource.Online && selectedLayer.map in offlineMaps
        }
}

internal const val DEFAULT_PHONE_MAP_COMPARISON_TRANSPARENCY_PERCENT = 50f

internal data class PhoneMapComparisonSelectionCompletion(
    val state: PhoneMapUiState,
    val validationError: PhoneOfflineMapError? = null,
)

/** Map selection and semantic content visibility remain untouched when a tool panel changes. */
@Suppress("TooManyFunctions") // State transitions stay beside the immutable map-tool state they update.
internal data class PhoneMapUiState(
    val source: PhoneMapSource = PhoneMapSource.Online,
    val comparison: PhoneMapComparisonState = PhoneMapComparisonState(),
    val contentVisibility: MapContentVisibility = MapContentVisibility(),
    val toolPanel: MapToolPanelState = MapToolPanelState(),
    val toolLauncherExpanded: Boolean = false,
    val mapMode: PhoneMapMode = PhoneMapMode(),
    val cameraCommand: PhoneMapCameraCommand? = null,
    val nextCameraCommandId: Long = 1L,
) {
    fun selectTool(tool: MapTool): PhoneMapUiState = copy(toolPanel = toolPanel.select(tool))

    fun openToolPage(
        tool: MapTool,
        featureSettings: Boolean,
    ): PhoneMapUiState {
        val selected = closeTool().selectTool(tool).copy(toolLauncherExpanded = false)
        val content = if (featureSettings) selected.showFeatureSettings() else selected
        return if (featureSettings || tool == MapTool.SETTINGS) content.expandTool() else content
    }

    fun toggleToolLauncher(): PhoneMapUiState = copy(toolLauncherExpanded = !toolLauncherExpanded)

    fun expandTool(): PhoneMapUiState = copy(toolPanel = toolPanel.expand())

    fun collapseTool(): PhoneMapUiState = copy(toolPanel = toolPanel.collapse())

    fun closeTool(): PhoneMapUiState = copy(toolPanel = toolPanel.close())

    fun showFeatureSettings(): PhoneMapUiState = copy(toolPanel = toolPanel.showFeatureSettings())

    fun nextComparisonSelectionRequest(): PhoneMapUiState =
        copy(
            comparison =
                comparison.copy(
                    validationRequestGeneration = comparison.validationRequestGeneration + 1L,
                ),
        )

    fun changeBaseSource(source: PhoneMapSource): PhoneMapUiState =
        if (this.source == source) {
            this
        } else {
            copy(source = source).nextComparisonSelectionRequest()
        }

    fun completeOfflineComparisonSelection(
        requestGeneration: Long,
        layer: PhoneMapComparisonLayer.Offline,
        validationError: PhoneOfflineMapError?,
        offlineMaps: List<PhoneOfflineMap>,
    ): PhoneMapComparisonSelectionCompletion {
        if (
            comparison.validationRequestGeneration != requestGeneration ||
            !comparison.copy(layer = layer).isAvailableFor(source, offlineMaps)
        ) {
            return PhoneMapComparisonSelectionCompletion(this)
        }
        return if (validationError == null) {
            PhoneMapComparisonSelectionCompletion(selectComparisonLayer(layer))
        } else {
            PhoneMapComparisonSelectionCompletion(this, validationError)
        }
    }

    fun selectComparisonLayer(layer: PhoneMapComparisonLayer?): PhoneMapUiState =
        copy(
            comparison =
                comparison.copy(
                    layer = layer,
                    selectionGeneration = comparison.selectionGeneration + 1L,
                ),
        )

    fun clearFailedOfflineComparison(
        layer: PhoneMapComparisonLayer.Offline,
        selectionGeneration: Long,
    ): PhoneMapUiState =
        if (comparison.layer == layer && comparison.selectionGeneration == selectionGeneration) {
            selectComparisonLayer(null)
        } else {
            this
        }

    @Suppress("MaxLineLength") // Keep this immutable state transition beside the other tool actions.
    fun setComparisonTransparency(percent: Float): PhoneMapUiState = copy(comparison = comparison.withTransparency(percent))

    fun clearUnavailableComparison(offlineMaps: List<PhoneOfflineMap>): PhoneMapUiState =
        takeIf { !comparison.isAvailableFor(source, offlineMaps) }
            ?.selectComparisonLayer(null) ?: this

    fun requestZoom(delta: Int): PhoneMapUiState =
        copy(
            cameraCommand =
                PhoneMapCameraCommand(
                    id = nextCameraCommandId,
                    zoomDelta = delta,
                ),
            nextCameraCommandId = nextCameraCommandId + 1L,
        )

    fun requestSearchTarget(
        target: PhoneMapCoordinate,
        currentBearingDegrees: Float? = null,
    ): PhoneMapUiState =
        copy(
            mapMode = mapMode.detachFromLocation(currentBearingDegrees),
            cameraCommand = PhoneMapCameraCommand(id = nextCameraCommandId, target = target),
            nextCameraCommandId = nextCameraCommandId + 1L,
        )

    fun consumeCommand(id: Long): PhoneMapUiState = if (cameraCommand?.id == id) copy(cameraCommand = null) else this

    fun toggleMapOrientation(): PhoneMapUiState = copy(mapMode = mapMode.toggleOrientation())

    fun onMapBack(): PhoneMapUiState {
        val panelAfterBack = toolPanel.back()
        return when {
            toolLauncherExpanded -> copy(toolLauncherExpanded = false)
            panelAfterBack != toolPanel -> copy(toolPanel = panelAfterBack)
            else -> this
        }
    }
}
