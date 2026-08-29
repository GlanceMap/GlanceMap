package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapContentVisibility

/** The shared phone-map tools; each uses the same panel scaffold. */
internal enum class MapTool {
    POI,
    GPX,
    MAPS,
    SETTINGS,
}

internal enum class MapToolPanelMode {
    CLOSED,
    SPLIT,
    EXPANDED,
}

/** Pure panel navigation state, deliberately separate from map renderer and content state. */
internal data class MapToolPanelState(
    val activeTool: MapTool? = null,
    val mode: MapToolPanelMode = MapToolPanelMode.CLOSED,
) {
    fun select(tool: MapTool): MapToolPanelState =
        when (mode) {
            MapToolPanelMode.CLOSED -> MapToolPanelState(activeTool = tool, mode = MapToolPanelMode.SPLIT)
            MapToolPanelMode.SPLIT ->
                if (activeTool == tool) {
                    MapToolPanelState()
                } else {
                    copy(activeTool = tool)
                }
            MapToolPanelMode.EXPANDED ->
                if (activeTool == tool) {
                    collapse()
                } else {
                    copy(activeTool = tool)
                }
        }

    fun expand(): MapToolPanelState =
        takeIf { mode == MapToolPanelMode.SPLIT }
            ?.copy(mode = MapToolPanelMode.EXPANDED)
            ?: this

    fun collapse(): MapToolPanelState =
        takeIf { mode == MapToolPanelMode.EXPANDED }
            ?.copy(mode = MapToolPanelMode.SPLIT)
            ?: this

    fun back(): MapToolPanelState =
        when (mode) {
            MapToolPanelMode.EXPANDED -> collapse()
            MapToolPanelMode.SPLIT -> MapToolPanelState()
            MapToolPanelMode.CLOSED -> this
        }
}

/** Map selection and semantic content visibility remain untouched when a tool panel changes. */
internal data class PhoneMapUiState(
    val source: PhoneMapSource = PhoneMapSource.Online,
    val contentVisibility: MapContentVisibility = MapContentVisibility(),
    val toolPanel: MapToolPanelState = MapToolPanelState(),
) {
    fun selectTool(tool: MapTool): PhoneMapUiState = copy(toolPanel = toolPanel.select(tool))

    fun expandTool(): PhoneMapUiState = copy(toolPanel = toolPanel.expand())

    fun collapseTool(): PhoneMapUiState = copy(toolPanel = toolPanel.collapse())

    fun onToolBack(): PhoneMapUiState = copy(toolPanel = toolPanel.back())
}
