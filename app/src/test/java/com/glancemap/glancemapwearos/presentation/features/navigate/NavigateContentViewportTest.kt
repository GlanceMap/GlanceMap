package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateContentViewportTest {
    @Test
    fun ordinaryPanningDoesNotRefreshRouteToolOverlays() {
        assertFalse(
            shouldRefreshRouteToolOverlayForViewport(
                routeToolSessionActive = false,
            ),
        )
    }

    @Test
    fun routeToolPanningRefreshesRouteToolOverlays() {
        assertTrue(
            shouldRefreshRouteToolOverlayForViewport(
                routeToolSessionActive = true,
            ),
        )
    }
}
