package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLongPressActionPolicyTest {
    @Test
    fun `opens actions only when a GPX inspection did not claim the press`() {
        assertFalse(
            shouldOpenMapLongPressActions(
                actionsEnabled = false,
                gpxInspectionHandled = false,
                selectingGpxPointB = false,
            ),
        )
        assertFalse(
            shouldOpenMapLongPressActions(
                actionsEnabled = true,
                gpxInspectionHandled = true,
                selectingGpxPointB = false,
            ),
        )
        assertFalse(
            shouldOpenMapLongPressActions(
                actionsEnabled = true,
                gpxInspectionHandled = false,
                selectingGpxPointB = true,
            ),
        )
        assertTrue(
            shouldOpenMapLongPressActions(
                actionsEnabled = true,
                gpxInspectionHandled = false,
                selectingGpxPointB = false,
            ),
        )
    }
}
