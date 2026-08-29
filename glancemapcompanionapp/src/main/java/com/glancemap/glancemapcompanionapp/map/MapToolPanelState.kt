package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapContentVisibility

/** The shared phone-map tools; each uses the same panel scaffold. */
internal enum class MapTool {
    POI,
    GPX,
    MAPS,
    SETTINGS,
}

/** Primary content stays directly reachable; the smaller secondary launcher can grow later. */
internal val primaryMapTools = listOf(MapTool.MAPS, MapTool.GPX, MapTool.POI)
internal val secondaryMapTools = listOf(MapTool.SETTINGS)

internal enum class MapToolPanelMode {
    CLOSED,
    SPLIT,
    EXPANDED,
}

internal enum class MapToolContentMode {
    MAIN,
    FEATURE_SETTINGS,
}

/** Pure panel navigation state, deliberately separate from map renderer and content state. */
internal data class MapToolPanelState(
    val activeTool: MapTool? = null,
    val mode: MapToolPanelMode = MapToolPanelMode.CLOSED,
    val contentMode: MapToolContentMode = MapToolContentMode.MAIN,
) {
    fun select(tool: MapTool): MapToolPanelState =
        when (mode) {
            MapToolPanelMode.CLOSED ->
                MapToolPanelState(activeTool = tool, mode = MapToolPanelMode.SPLIT)
            MapToolPanelMode.SPLIT,
            MapToolPanelMode.EXPANDED,
            -> copy(activeTool = tool, contentMode = MapToolContentMode.MAIN)
        }

    fun showFeatureSettings(): MapToolPanelState =
        takeIf { activeTool in featureSettingsTools }
            ?.copy(contentMode = MapToolContentMode.FEATURE_SETTINGS)
            ?: this

    fun showMainContent(): MapToolPanelState = copy(contentMode = MapToolContentMode.MAIN)

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
            contentMode == MapToolContentMode.FEATURE_SETTINGS -> showMainContent()
            else ->
                when (mode) {
                    MapToolPanelMode.EXPANDED -> collapse()
                    MapToolPanelMode.SPLIT -> MapToolPanelState()
                    MapToolPanelMode.CLOSED -> this
                }
        }

    fun close(): MapToolPanelState = MapToolPanelState()

    private companion object {
        val featureSettingsTools = setOf(MapTool.POI, MapTool.GPX, MapTool.MAPS)
    }
}

/** Map selection and semantic content visibility remain untouched when a tool panel changes. */
internal data class PhoneMapUiState(
    val source: PhoneMapSource = PhoneMapSource.Online,
    val contentVisibility: MapContentVisibility = MapContentVisibility(),
    val toolPanel: MapToolPanelState = MapToolPanelState(),
    val toolLauncherExpanded: Boolean = false,
    val mapMode: PhoneMapMode = PhoneMapMode(),
    val cameraCommand: PhoneMapCameraCommand? = null,
) {
    fun selectTool(tool: MapTool): PhoneMapUiState = copy(toolPanel = toolPanel.select(tool))

    fun toggleToolLauncher(): PhoneMapUiState = copy(toolLauncherExpanded = !toolLauncherExpanded)

    fun expandTool(): PhoneMapUiState = copy(toolPanel = toolPanel.expand())

    fun collapseTool(): PhoneMapUiState = copy(toolPanel = toolPanel.collapse())

    fun closeTool(): PhoneMapUiState = copy(toolPanel = toolPanel.close())

    fun showFeatureSettings(): PhoneMapUiState = copy(toolPanel = toolPanel.showFeatureSettings())

    fun requestZoom(delta: Int): PhoneMapUiState =
        copy(
            cameraCommand =
                PhoneMapCameraCommand(
                    id = (cameraCommand?.id ?: 0L) + 1L,
                    zoomDelta = delta,
                ),
        )

    fun consumeCommand(id: Long): PhoneMapUiState = if (cameraCommand?.id == id) copy(cameraCommand = null) else this

    fun toggleMapOrientation(): PhoneMapUiState = copy(mapMode = mapMode.toggleOrientation())

    fun onMapBack(): PhoneMapUiState {
        val panelAfterBack = toolPanel.back()
        return when {
            panelAfterBack != toolPanel -> copy(toolPanel = panelAfterBack)
            toolLauncherExpanded -> copy(toolLauncherExpanded = false)
            else -> this
        }
    }
}
