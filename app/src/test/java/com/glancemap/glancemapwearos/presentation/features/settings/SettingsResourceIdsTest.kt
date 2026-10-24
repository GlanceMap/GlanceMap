package com.glancemap.glancemapwearos.presentation.features.settings

import com.glancemap.glancemapwearos.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsResourceIdsTest {
    @Test
    fun debuggingLabelsUseLinkedResourceIds() {
        val screen =
            Class.forName("com.glancemap.glancemapwearos.presentation.features.settings.DebuggingSettingsScreenKt")
        listOf(
            "CLEAN_CAPTURE_DEFAULT_LABEL" to R.string.debug_clear_all_captured_logs,
            "CLEAN_CAPTURE_CLEARED_LABEL" to R.string.debug_all_captured_logs_have_been_cleared,
            "DIAGNOSTICS_DEFAULT_STATUS" to R.string.debug_export_to_support_email,
        ).forEach { (name, expected) ->
            assertNotEquals("Linked resource ID must be valid", 0, expected)
            // Read the actual compiled aliases used during the screen's initial composition.
            val field = screen.getDeclaredField(name).apply { isAccessible = true }
            assertEquals(name, expected, field.getInt(null))
        }
    }

    @Test
    fun gpxFilterLabelsUseLinkedResourceIds() {
        listOf(
            R.string.settings_smoothing_distance to GpxElevationFilterUi.SMOOTHING_LABEL,
            R.string.settings_noise_threshold to GpxElevationFilterUi.NOISE_THRESHOLD_LABEL,
            R.string.settings_trend_threshold to GpxElevationFilterUi.TREND_THRESHOLD_LABEL,
        ).forEach { (expected, actual) ->
            assertNotEquals("Linked resource ID must be valid", 0, expected)
            assertEquals(expected, actual)
        }
    }
}
