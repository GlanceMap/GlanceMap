package com.glancemap.glancemapcompanionapp.map

internal const val DEFAULT_MAP_TOOL_PANEL_FRACTION = 0.35f
private const val MIN_MAP_TOOL_PANEL_FRACTION = 0.05f
private const val MAX_MAP_TOOL_PANEL_FRACTION = 1f

internal enum class MapToolPanelSizeTarget {
    FULL,
    DEFAULT,
    CUSTOM,
}

internal val MapToolPanelState.nextSizeTarget: MapToolPanelSizeTarget
    get() =
        when {
            mode == MapToolPanelMode.EXPANDED -> MapToolPanelSizeTarget.DEFAULT
            splitFraction == DEFAULT_MAP_TOOL_PANEL_FRACTION &&
                customSplitFraction != null &&
                customSplitFraction != DEFAULT_MAP_TOOL_PANEL_FRACTION ->
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
        // Upgrade the old preset without replacing an explicitly dragged 32% custom size.
        splitFraction =
            if (splitFraction == 0.32f && customSplitFraction != 0.32f) {
                DEFAULT_MAP_TOOL_PANEL_FRACTION
            } else {
                normalizedMapToolPanelFraction(splitFraction)
            },
        customSplitFraction = customSplitFraction?.takeIf(Float::isFinite)?.let(::normalizedMapToolPanelFraction),
    )

private fun normalizedMapToolPanelFraction(fraction: Float): Float =
    if (fraction.isFinite()) {
        fraction.coerceIn(MIN_MAP_TOOL_PANEL_FRACTION, MAX_MAP_TOOL_PANEL_FRACTION)
    } else {
        DEFAULT_MAP_TOOL_PANEL_FRACTION
    }

/** Keep the header reachable, including when the window cannot also fit the requested map minimum. */
internal fun mapToolPanelFractionRange(
    axisSizePx: Int,
    minPanelSizePx: Int,
    minMapSizePx: Int,
): ClosedFloatingPointRange<Float> {
    if (axisSizePx <= 0) return DEFAULT_MAP_TOOL_PANEL_FRACTION..DEFAULT_MAP_TOOL_PANEL_FRACTION
    val half = axisSizePx / 2f
    val maximum = 1f - minOf(minMapSizePx.coerceAtLeast(0).toFloat(), half) / axisSizePx
    val minimum = minOf(minPanelSizePx.coerceAtLeast(0).toFloat() / axisSizePx, maximum)
    return minimum.coerceAtLeast(MIN_MAP_TOOL_PANEL_FRACTION)..maximum.coerceAtMost(MAX_MAP_TOOL_PANEL_FRACTION)
}
