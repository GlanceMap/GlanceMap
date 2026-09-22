package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRendererTerrainDiagnosticsTest {
    @Test
    fun configurationLifecycleDistinguishesInitialRebuildAndReuse() {
        assertEquals(
            "initial",
            describeHillshadeConfigurationLifecycle(
                hasExistingConfiguration = false,
                signatureUnchanged = false,
            ),
        )
        assertEquals(
            "rebuild",
            describeHillshadeConfigurationLifecycle(
                hasExistingConfiguration = true,
                signatureUnchanged = false,
            ),
        )
        assertEquals(
            "reuse",
            describeHillshadeConfigurationLifecycle(
                hasExistingConfiguration = true,
                signatureUnchanged = true,
            ),
        )
    }

    @Test
    fun layerTelemetryDoesNotLeakPreviousDecision() {
        val replaced =
            describeHillshadeLayerTelemetry(
                hadExistingLayer = true,
                created = true,
                retained = false,
            )
        val initial =
            describeHillshadeLayerTelemetry(
                hadExistingLayer = false,
                created = true,
                retained = false,
            )
        val retained =
            describeHillshadeLayerTelemetry(
                hadExistingLayer = true,
                created = false,
                retained = true,
            )
        val cleared =
            describeHillshadeLayerTelemetry(
                hadExistingLayer = true,
                created = false,
                retained = false,
            )

        assertEquals("replaced", replaced.action)
        assertTrue(replaced.replaced)
        assertEquals("created", initial.action)
        assertEquals("retained", retained.action)
        assertTrue(retained.retained)
        assertEquals("cleared", cleared.action)
        assertTrue(cleared.cleared)
        assertFalse(cleared.replaced)
    }

    @Test
    fun visibleTerrainUnavailableIsIgnoredOutsideTheSelectedMapArea() {
        assertFalse(
            shouldShowVisibleHillshadeTerrainUnavailable(
                mapRequiredTileIds = setOf("N43E011"),
                visibleTileIds = setOf("N00E000", "N00W001", "S01E000", "S01W001"),
            ),
        )
        assertTrue(
            shouldShowVisibleHillshadeTerrainUnavailable(
                mapRequiredTileIds = setOf("N43E011"),
                visibleTileIds = setOf("N43E011"),
            ),
        )
        assertTrue(
            shouldShowVisibleHillshadeTerrainUnavailable(
                mapRequiredTileIds = null,
                visibleTileIds = setOf("N00E000"),
            ),
        )
    }
}
