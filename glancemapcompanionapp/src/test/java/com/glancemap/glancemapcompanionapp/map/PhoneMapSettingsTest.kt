package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapSettingsTest {
    @Test
    fun defaultsMirrorWatchMapDisplayAndZoomDefaults() {
        val settings = PhoneMapSettings()

        assertEquals(PhoneMapMarkerAnchor.CENTER, settings.markerAnchor)
        assertFalse(settings.autoRecenterEnabled)
        assertEquals(5, settings.autoRecenterDelaySeconds)
        assertEquals(PhoneMapNorthIndicatorMode.ALWAYS, settings.northIndicatorMode)
        assertEquals(PhoneMapMarkerStyle.DOT, settings.markerStyle)
        assertEquals(PhoneMapZoomButtonsMode.BOTH, settings.zoomButtonsMode)
        assertFalse(settings.tapToToggleToolbarEnabled)
        assertFalse(settings.gpsAccuracyCircleEnabled)
        assertEquals(250, settings.zoomDefaultScaleMeters)
        assertEquals(5_000_000, settings.zoomMinScaleMeters)
        assertEquals(1, settings.zoomMaxScaleMeters)
        assertFalse(settings.liveElevationEnabled)
        assertFalse(settings.liveDistanceEnabled)
        assertFalse(settings.distanceMeasurementEnabled)
        assertFalse(settings.hillShadingEnabled)
        assertFalse(settings.tiltedViewEnabled)
        assertTrue(settings.onlineElevationEnabled)
        assertFalse(settings.reliefOverlayEnabled)
        assertEquals(100, settings.reliefOverlayOpacityPercent)
        assertFalse(settings.nightModeEnabled)
        assertEquals(PhoneMapNorthReferenceMode.TRUE, settings.northReferenceMode)
        assertEquals(PhoneOfflineDemSource.STANDARD, settings.demSource)
        assertTrue(settings.hiddenOnlineSources.isEmpty())
        assertEquals(PhoneOnlineMapSource.entries, settings.onlineSourceOrder)
    }

    @Test
    fun onlineSourceOrderRepairsMissingEntriesAndMovesVisibleMapsWithoutReorderingUnavailableMaps() {
        val allSources = PhoneOnlineMapSource.entries
        val normalized =
            normalizePhoneOnlineSourceOrder(
                listOf(PhoneOnlineMapSource.CYCLOSM, PhoneOnlineMapSource.CYCLOSM),
            )
        assertEquals(
            listOf(PhoneOnlineMapSource.CYCLOSM) + allSources.filterNot { it == PhoneOnlineMapSource.CYCLOSM },
            normalized,
        )

        val visible =
            listOf(PhoneOnlineMapSource.OPEN_TOPO, PhoneOnlineMapSource.CYCLOSM, PhoneOnlineMapSource.OPEN_STREET_MAP)
        val reordered =
            reorderPhoneOnlineSourceOrder(
                allSources,
                visible,
                PhoneOnlineMapSource.OPEN_TOPO,
                targetIndex = 1,
            )

        assertEquals(
            listOf(PhoneOnlineMapSource.OPEN_STREET_MAP, PhoneOnlineMapSource.OPEN_TOPO, PhoneOnlineMapSource.CYCLOSM),
            reordered.filter { it in visible },
        )
        assertEquals(allSources.filterNot { it in visible }, reordered.filterNot { it in visible })
    }

    @Test
    fun distanceMeasurementIsOptInFromMapSettings() {
        assertFalse(PhoneMapSettings().distanceMeasurementEnabled)
        assertTrue(PhoneMapSettings().copy(distanceMeasurementEnabled = true).distanceMeasurementEnabled)
    }

    @Test
    fun normalizationClampsDelayAndKeepsZoomScalesOrdered() {
        val normalized =
            PhoneMapSettings(
                autoRecenterDelaySeconds = 100,
                zoomDefaultScaleMeters = 1,
                zoomMinScaleMeters = 20,
                zoomMaxScaleMeters = 200_000,
            ).normalized()

        assertEquals(MAX_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS, normalized.autoRecenterDelaySeconds)
        assertEquals(200_000, normalized.zoomMinScaleMeters)
        assertEquals(20, normalized.zoomMaxScaleMeters)
        assertEquals(20, normalized.zoomDefaultScaleMeters)
    }

    @Test
    fun terrain3dOptionsStartOffAndReliefStrengthIsClamped() {
        val settings = PhoneMapSettings()
        assertFalse(settings.terrain3dElevationColorsEnabled)
        assertEquals(0, settings.terrain3dReliefStrengthPercent)
        val tooStrong = PhoneMapSettings(terrain3dReliefStrengthPercent = 150).normalized()
        val negative = PhoneMapSettings(terrain3dReliefStrengthPercent = -5).normalized()
        assertEquals(100, tooStrong.terrain3dReliefStrengthPercent)
        assertEquals(0, negative.terrain3dReliefStrengthPercent)
    }

    @Test
    fun normalizationClampsSlopeReliefOpacity() {
        val normalized = PhoneMapSettings(reliefOverlayOpacityPercent = 150).normalized()

        assertEquals(100, normalized.reliefOverlayOpacityPercent)
    }

    @Test
    fun northIndicatorModesOnlyShowInTheirConfiguredOrientation() {
        val northUp = PhoneMapMode(orientation = PhoneMapOrientation.NORTH_UP)
        val headingUp = PhoneMapMode(orientation = PhoneMapOrientation.HEADING_UP)

        assertEquals(true, PhoneMapNorthIndicatorMode.ALWAYS.isVisibleFor(northUp, false))
        assertEquals(true, PhoneMapNorthIndicatorMode.ALWAYS.isVisibleFor(headingUp, true))
        assertEquals(true, PhoneMapNorthIndicatorMode.NORTH_UP_ONLY.isVisibleFor(northUp, true))
        assertEquals(false, PhoneMapNorthIndicatorMode.NORTH_UP_ONLY.isVisibleFor(headingUp, true))
        assertEquals(true, PhoneMapNorthIndicatorMode.COMPASS_ONLY.isVisibleFor(headingUp, true))
        assertEquals(false, PhoneMapNorthIndicatorMode.COMPASS_ONLY.isVisibleFor(northUp, true))
        assertEquals(false, PhoneMapNorthIndicatorMode.COMPASS_ONLY.isVisibleFor(headingUp, false))
        assertEquals(false, PhoneMapNorthIndicatorMode.NEVER.isVisibleFor(northUp, true))
    }

    @Test
    fun planIgnV2IsHiddenOnceForSettingsSavedBeforeTheDefault() {
        val plan = PhoneOnlineMapSource.PLAN_IGN_V2.name
        val saved = setOf(PhoneOnlineMapSource.CYCLOSM.name)

        val beforeDefault = phoneHiddenOnlineSourceNamesWithPlanIgnV2Default(saved, planIgnV2DefaultApplied = false)
        val afterDefault = phoneHiddenOnlineSourceNamesWithPlanIgnV2Default(saved, planIgnV2DefaultApplied = true)

        assertEquals(saved + plan, beforeDefault)
        assertEquals(saved, afterDefault)
    }

    @Test
    fun draggedSourceTargetsTheSlotItPassesHalfwayAndStaysInTheList() {
        assertEquals(2, phoneOnlineSourceDragTargetIndex(2, dragOffsetPx = 49f, rowPitchPx = 100, count = 5))
        assertEquals(3, phoneOnlineSourceDragTargetIndex(2, dragOffsetPx = 51f, rowPitchPx = 100, count = 5))
        assertEquals(0, phoneOnlineSourceDragTargetIndex(2, dragOffsetPx = -260f, rowPitchPx = 100, count = 5))
        assertEquals(4, phoneOnlineSourceDragTargetIndex(2, dragOffsetPx = 900f, rowPitchPx = 100, count = 5))
        assertEquals(-1, phoneOnlineSourceDragTargetIndex(-1, dragOffsetPx = 300f, rowPitchPx = 100, count = 5))
    }

    @Test
    fun rowsBetweenTheDraggedRowAndItsTargetSlideTowardTheOldSlot() {
        // Dragging row 1 down to slot 3: rows 2 and 3 move up, the others stay.
        assertEquals(listOf(0, 0, -1, -1, 0), (0..4).map { phoneOnlineSourceDragShift(it, 1, 3) })
        // Dragging row 3 up to slot 1: rows 1 and 2 move down.
        assertEquals(listOf(0, 1, 1, 0, 0), (0..4).map { phoneOnlineSourceDragShift(it, 3, 1) })
        assertEquals(listOf(0, 0, 0), (0..2).map { phoneOnlineSourceDragShift(it, -1, -1) })
    }
}
