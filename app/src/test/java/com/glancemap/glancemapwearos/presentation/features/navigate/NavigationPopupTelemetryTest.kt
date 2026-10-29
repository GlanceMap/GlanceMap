package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPopupTelemetryTest {
    @Test
    fun repeatedRecompositionsLogOnlyOneShowAndHideWithTheCurrentReason() {
        var now = 100L
        val lines = mutableListOf<String>()
        val telemetry = NavigationPopupTelemetry("waiting_for_location", { now }, { true }, lines::add)
        telemetry.update(visible = false, reason = "initial_location_search")
        repeat(10) { telemetry.update(visible = true, reason = "initial_location_search") }
        now = 1_600L
        telemetry.update(visible = false, reason = "location_received")
        telemetry.update(visible = false, reason = "screen_inactive")
        assertEquals(
            listOf(
                "event=navigation_popup popup=waiting_for_location action=show reason=initial_location_search",
                "event=navigation_popup popup=waiting_for_location " +
                    "action=hide reason=location_received durationMs=1500",
            ),
            lines,
        )
    }

    @Test
    fun temporarySuppressionAndScreenDisposalMeasureSeparateVisibleIntervals() {
        var now = 0L
        val lines = mutableListOf<String>()
        val telemetry = NavigationPopupTelemetry("no_map_active", { now }, { true }, lines::add)
        telemetry.update(visible = true, reason = "map_update_without_active_map")
        now = 300L
        telemetry.update(visible = false, reason = "fullscreen_popup")
        now = 800L
        telemetry.update(visible = true, reason = "map_update_without_active_map")
        now = 1_000L
        telemetry.update(visible = false, reason = "overlay_disposed")
        telemetry.update(visible = false, reason = "overlay_disposed")
        assertEquals(4, lines.size)
        assertTrue(lines[1].endsWith("reason=fullscreen_popup durationMs=300"))
        assertTrue(lines[3].endsWith("reason=overlay_disposed durationMs=200"))
    }

    @Test
    fun disabledDiagnosticsNeitherLogsNorMeasuresAndCannotLeaveAnOrphanHide() {
        var enabled = false
        var clockReads = 0
        val lines = mutableListOf<String>()
        val telemetry =
            NavigationPopupTelemetry(
                "no_map_active",
                {
                    clockReads += 1
                    0L
                },
                { enabled },
                lines::add,
            )
        telemetry.update(visible = true, reason = "map_update_without_active_map")
        telemetry.update(visible = false, reason = "timeout")
        assertTrue(lines.isEmpty())
        assertEquals(0, clockReads)
        enabled = true
        telemetry.update(visible = true, reason = "map_update_without_active_map")
        enabled = false
        telemetry.update(visible = false, reason = "map_activated")
        enabled = true
        telemetry.update(visible = false, reason = "map_activated")
        assertEquals(1, lines.size)
    }

    @Test
    fun noMapNoticeDistinguishesTimeoutMapActivationAndCompletedWork() {
        assertEquals("timeout", noActiveMapNoticeReason(false, true, false, null))
        assertEquals("map_activated", noActiveMapNoticeReason(true, true, false, null))
        assertEquals("map_update_finished", noActiveMapNoticeReason(false, false, false, null))
        assertEquals("fullscreen_popup", noActiveMapNoticeReason(false, true, true, "fullscreen_popup"))
        assertEquals("recording_status", noActiveMapNoticeReason(false, true, true, "recording_status"))
    }
}
