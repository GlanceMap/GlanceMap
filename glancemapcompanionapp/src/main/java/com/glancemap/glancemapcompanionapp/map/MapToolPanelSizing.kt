package com.glancemap.glancemapcompanionapp.map

import kotlin.math.abs

internal const val DEFAULT_MAP_TOOL_PANEL_FRACTION = 0.32f
private const val MIN_MAP_TOOL_PANEL_FRACTION = 0.05f
private const val MAX_MAP_TOOL_PANEL_FRACTION = 0.95f
private const val MAP_TOOL_PANEL_PRESET_TOLERANCE = 0.005f

internal enum class MapToolPanelSizeTarget {
    FULL,
    DEFAULT,
    CUSTOM,
}

internal val MapToolPanelState.nextSizeTarget: MapToolPanelSizeTarget
    get() =
        when {
            mode == MapToolPanelMode.EXPANDED -> MapToolPanelSizeTarget.DEFAULT
            abs(splitFraction - DEFAULT_MAP_TOOL_PANEL_FRACTION) <= MAP_TOOL_PANEL_PRESET_TOLERANCE &&
                customSplitFraction != null &&
                abs(customSplitFraction - DEFAULT_MAP_TOOL_PANEL_FRACTION) > MAP_TOOL_PANEL_PRESET_TOLERANCE ->
                MapToolPanelSizeTarget.CUSTOM
            else -> MapToolPanelSizeTarget.FULL
        }

internal fun MapToolPanelState.cycleSize(): MapToolPanelState {
    if (mode == MapToolPanelMode.CLOSED) return this
    return when (nextSizeTarget) {
        MapToolPanelSizeTarget.FULL -> expand()
        MapToolPanelSizeTarget.DEFAULT ->
            copy(
                mode = MapToolPanelMode.SPLIT,
                splitFraction = DEFAULT_MAP_TOOL_PANEL_FRACTION,
            )
        MapToolPanelSizeTarget.CUSTOM -> copy(splitFraction = requireNotNull(customSplitFraction))
    }
}

internal fun MapToolPanelState.resize(fraction: Float): MapToolPanelState {
    if (mode == MapToolPanelMode.CLOSED || !fraction.isFinite()) return this
    val normalized = normalizedMapToolPanelFraction(fraction)
    return copy(mode = MapToolPanelMode.SPLIT, splitFraction = normalized, customSplitFraction = normalized)
}

internal fun MapToolPanelState.normalizedSizing(): MapToolPanelState =
    copy(
        splitFraction = normalizedMapToolPanelFraction(splitFraction),
        customSplitFraction = customSplitFraction?.takeIf(Float::isFinite)?.let(::normalizedMapToolPanelFraction),
    )

private fun normalizedMapToolPanelFraction(fraction: Float): Float =
    if (fraction.isFinite()) {
        fraction.coerceIn(MIN_MAP_TOOL_PANEL_FRACTION, MAX_MAP_TOOL_PANEL_FRACTION)
    } else {
        DEFAULT_MAP_TOOL_PANEL_FRACTION
    }

/** Keep the header/list and the map controls usable, adapting both minimums on small windows. */
internal fun mapToolPanelFractionRange(
    axisSizePx: Int,
    minPanelSizePx: Int,
    minMapSizePx: Int,
): ClosedFloatingPointRange<Float> {
    if (axisSizePx <= 0) return DEFAULT_MAP_TOOL_PANEL_FRACTION..DEFAULT_MAP_TOOL_PANEL_FRACTION
    val half = axisSizePx / 2f
    val minimum = minOf(minPanelSizePx.coerceAtLeast(0).toFloat(), half) / axisSizePx
    val maximum = 1f - minOf(minMapSizePx.coerceAtLeast(0).toFloat(), half) / axisSizePx
    return minimum.coerceAtLeast(MIN_MAP_TOOL_PANEL_FRACTION)..maximum.coerceAtMost(MAX_MAP_TOOL_PANEL_FRACTION)
}
