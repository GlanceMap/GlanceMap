package com.glancemap.glancemapcompanionapp.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionLayoutContextTest {
    @Test
    fun `classifies widths at compact medium and expanded boundaries`() {
        assertEquals(CompanionWidthClass.COMPACT, companionWidthClass(599.dp))
        assertEquals(CompanionWidthClass.MEDIUM, companionWidthClass(600.dp))
        assertEquals(CompanionWidthClass.MEDIUM, companionWidthClass(839.dp))
        assertEquals(CompanionWidthClass.EXPANDED, companionWidthClass(840.dp))
    }

    @Test
    fun `classifies height independently from width`() {
        assertEquals(CompanionHeightCapacity.CONSTRAINED, companionHeightCapacity(479.dp))
        assertEquals(CompanionHeightCapacity.REGULAR, companionHeightCapacity(480.dp))
    }

    @Test
    fun `requires the full minimum width for a continuous supporting pane`() {
        val requirements = PaneRequirements()

        assertEquals(
            CompanionPanePlacement.SingleSurface,
            companionPanePlacement(
                availableWidth = 840.dp,
                topology = CompanionWindowTopology.SingleRegion,
                requirements = requirements,
            ),
        )
        assertEquals(
            CompanionPanePlacement.SideBySide(
                primaryRegion = CompanionPaneRegion.CONTINUOUS,
                supportingRegion = CompanionPaneRegion.CONTINUOUS,
                primaryWidth = 560.dp,
                supportingWidth = 320.dp,
                gap = 24.dp,
            ),
            companionPanePlacement(
                availableWidth = 904.dp,
                topology = CompanionWindowTopology.SingleRegion,
                requirements = requirements,
            ),
        )
        assertEquals(
            CompanionPanePlacement.SingleSurface,
            companionPanePlacement(
                availableWidth = 903.dp,
                topology = CompanionWindowTopology.SingleRegion,
                requirements = requirements,
            ),
        )
    }

    @Test
    fun `assigns vertical separator regions by pane requirements rather than side`() {
        assertEquals(
            CompanionPanePlacement.SideBySide(
                primaryRegion = CompanionPaneRegion.LEFT,
                supportingRegion = CompanionPaneRegion.RIGHT,
                primaryWidth = 560.dp,
                supportingWidth = 320.dp,
                gap = 24.dp,
            ),
            companionPanePlacement(
                availableWidth = 904.dp,
                topology =
                    CompanionWindowTopology.VerticalSeparator(
                        leftWidth = 560.dp,
                        separatorWidth = 24.dp,
                        rightWidth = 320.dp,
                    ),
            ),
        )
        assertEquals(
            CompanionPanePlacement.SideBySide(
                primaryRegion = CompanionPaneRegion.RIGHT,
                supportingRegion = CompanionPaneRegion.LEFT,
                primaryWidth = 560.dp,
                supportingWidth = 320.dp,
                gap = 24.dp,
            ),
            companionPanePlacement(
                availableWidth = 904.dp,
                topology =
                    CompanionWindowTopology.VerticalSeparator(
                        leftWidth = 320.dp,
                        separatorWidth = 24.dp,
                        rightWidth = 560.dp,
                    ),
            ),
        )
        assertEquals(
            CompanionPanePlacement.SingleSurface,
            companionPanePlacement(
                availableWidth = 868.dp,
                topology =
                    CompanionWindowTopology.VerticalSeparator(
                        leftWidth = 540.dp,
                        separatorWidth = 24.dp,
                        rightWidth = 304.dp,
                    ),
            ),
        )
    }

    @Test
    fun `does not create side by side panes across a horizontal separator`() {
        assertEquals(
            CompanionPanePlacement.SingleSurface,
            companionPanePlacement(
                availableWidth = 1200.dp,
                topology =
                    CompanionWindowTopology.HorizontalSeparator(
                        topHeight = 500.dp,
                        separatorHeight = 24.dp,
                        bottomHeight = 500.dp,
                    ),
            ),
        )
    }

    @Test
    fun `returns semantic content width limits`() {
        assertEquals(720.dp, contentMaxWidthFor(CompanionContentWidth.READABLE))
        assertEquals(960.dp, contentMaxWidthFor(CompanionContentWidth.WIDE))
        assertEquals(null, contentMaxWidthFor(CompanionContentWidth.FULL_BLEED))
    }
}
