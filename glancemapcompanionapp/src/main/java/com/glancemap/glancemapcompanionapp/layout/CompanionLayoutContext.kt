package com.glancemap.glancemapcompanionapp.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class CompanionWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

internal enum class CompanionHeightCapacity {
    CONSTRAINED,
    REGULAR,
}

internal enum class CompanionContentWidth {
    READABLE,
    WIDE,
    FULL_BLEED,
}

internal sealed interface CompanionWindowTopology {
    data object SingleRegion : CompanionWindowTopology

    data class VerticalSeparator(
        val leftWidth: Dp,
        val separatorWidth: Dp,
        val rightWidth: Dp,
    ) : CompanionWindowTopology

    data class HorizontalSeparator(
        val topHeight: Dp,
        val separatorHeight: Dp,
        val bottomHeight: Dp,
    ) : CompanionWindowTopology
}

internal data class PaneRequirements(
    val primaryMinWidth: Dp = 560.dp,
    val supportingMinWidth: Dp = 320.dp,
    val gutter: Dp = 24.dp,
)

internal enum class CompanionPaneRegion {
    CONTINUOUS,
    LEFT,
    RIGHT,
}

internal sealed interface CompanionPanePlacement {
    data object SingleSurface : CompanionPanePlacement

    data class SideBySide(
        val primaryRegion: CompanionPaneRegion,
        val supportingRegion: CompanionPaneRegion,
        val primaryWidth: Dp,
        val supportingWidth: Dp,
        val gap: Dp,
    ) : CompanionPanePlacement
}

internal data class CompanionLayoutContext(
    val availableWidth: Dp,
    val availableHeight: Dp,
    val widthClass: CompanionWidthClass,
    val heightCapacity: CompanionHeightCapacity,
    val fontScale: Float,
    val windowTopology: CompanionWindowTopology,
) {
    fun contentMaxWidth(kind: CompanionContentWidth): Dp? = contentMaxWidthFor(kind)

    fun panePlacement(requirements: PaneRequirements = PaneRequirements()): CompanionPanePlacement =
        companionPanePlacement(
            availableWidth = availableWidth,
            topology = windowTopology,
            requirements = requirements,
        )

    companion object {
        val Default =
            CompanionLayoutContext(
                availableWidth = 0.dp,
                availableHeight = 0.dp,
                widthClass = CompanionWidthClass.COMPACT,
                heightCapacity = CompanionHeightCapacity.CONSTRAINED,
                fontScale = 1f,
                windowTopology = CompanionWindowTopology.SingleRegion,
            )
    }
}

internal fun companionWidthClass(availableWidth: Dp): CompanionWidthClass =
    when {
        availableWidth < 600.dp -> CompanionWidthClass.COMPACT
        availableWidth < 840.dp -> CompanionWidthClass.MEDIUM
        else -> CompanionWidthClass.EXPANDED
    }

internal fun companionHeightCapacity(availableHeight: Dp): CompanionHeightCapacity =
    if (availableHeight < 480.dp) {
        CompanionHeightCapacity.CONSTRAINED
    } else {
        CompanionHeightCapacity.REGULAR
    }

internal fun contentMaxWidthFor(kind: CompanionContentWidth): Dp? =
    when (kind) {
        CompanionContentWidth.READABLE -> 720.dp
        CompanionContentWidth.WIDE -> 960.dp
        CompanionContentWidth.FULL_BLEED -> null
    }

internal fun companionPanePlacement(
    availableWidth: Dp,
    topology: CompanionWindowTopology,
    requirements: PaneRequirements = PaneRequirements(),
): CompanionPanePlacement =
    when (topology) {
        CompanionWindowTopology.SingleRegion -> continuousPanePlacement(availableWidth, requirements)
        is CompanionWindowTopology.VerticalSeparator -> verticalPanePlacement(topology, requirements)
        is CompanionWindowTopology.HorizontalSeparator -> CompanionPanePlacement.SingleSurface
    }

private fun continuousPanePlacement(
    availableWidth: Dp,
    requirements: PaneRequirements,
): CompanionPanePlacement {
    val requiredWidth =
        requirements.primaryMinWidth + requirements.gutter + requirements.supportingMinWidth
    if (availableWidth < requiredWidth) return CompanionPanePlacement.SingleSurface

    return CompanionPanePlacement.SideBySide(
        primaryRegion = CompanionPaneRegion.CONTINUOUS,
        supportingRegion = CompanionPaneRegion.CONTINUOUS,
        primaryWidth = availableWidth - requirements.gutter - requirements.supportingMinWidth,
        supportingWidth = requirements.supportingMinWidth,
        gap = requirements.gutter,
    )
}

private fun verticalPanePlacement(
    topology: CompanionWindowTopology.VerticalSeparator,
    requirements: PaneRequirements,
): CompanionPanePlacement {
    val leftCanBePrimary = topology.leftWidth >= requirements.primaryMinWidth
    val rightCanBePrimary = topology.rightWidth >= requirements.primaryMinWidth
    val leftCanSupport = topology.leftWidth >= requirements.supportingMinWidth
    val rightCanSupport = topology.rightWidth >= requirements.supportingMinWidth

    return when {
        leftCanBePrimary &&
            rightCanSupport &&
            (!rightCanBePrimary || !leftCanSupport || topology.leftWidth >= topology.rightWidth) ->
            CompanionPanePlacement.SideBySide(
                primaryRegion = CompanionPaneRegion.LEFT,
                supportingRegion = CompanionPaneRegion.RIGHT,
                primaryWidth = topology.leftWidth,
                supportingWidth = topology.rightWidth,
                gap = topology.separatorWidth,
            )

        rightCanBePrimary && leftCanSupport ->
            CompanionPanePlacement.SideBySide(
                primaryRegion = CompanionPaneRegion.RIGHT,
                supportingRegion = CompanionPaneRegion.LEFT,
                primaryWidth = topology.rightWidth,
                supportingWidth = topology.leftWidth,
                gap = topology.separatorWidth,
            )

        else -> CompanionPanePlacement.SingleSurface
    }
}
