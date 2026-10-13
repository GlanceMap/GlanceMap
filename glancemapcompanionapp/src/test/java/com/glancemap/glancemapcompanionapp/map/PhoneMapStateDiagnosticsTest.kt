package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureSlot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneMapStateDiagnosticsTest {
    @Test
    fun locationMarkerOwnerMatchesAllComparisonCombinationsAtEveryTransparency() {
        val offlineMap = PhoneOfflineMap(File("fixture.map"), contentRevision = "fixture")
        val onlineLayer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP)
        val combinations =
            listOf(
                PhoneMapSource.Online to onlineLayer,
                PhoneMapSource.Online to PhoneMapComparisonLayer.Offline(offlineMap),
                PhoneMapSource.Offline(offlineMap) to onlineLayer,
            )
        for ((base, layer) in combinations) {
            for (transparency in listOf(0f, 25f, 50f, 75f, 100f)) {
                val comparison = PhoneMapComparisonState(layer = layer, transparencyPercent = transparency)
                val offlineActive = layer is PhoneMapComparisonLayer.Offline && comparison.overlayAlpha() > 0f
                val onlineActive = layer is PhoneMapComparisonLayer.Online && comparison.overlayAlpha() > 0f
                val markerOwner = phoneMapDiagnosticLocationOwner(offlineActive, onlineActive)
                val followsComparison = phoneMapComparisonOwnsFollowAndCommands(base, onlineActive)
                val expectedMarkerOwner = if (transparency < 100f) "comparison" else "base"
                val diagnostic =
                    state().copy(
                        locationOwner = markerOwner,
                        locationFollowOwner = if (followsComparison) "comparison" else "base",
                    )

                assertEquals(expectedMarkerOwner, diagnostic.locationOwner)
                assertEquals(
                    transparency < 100f,
                    phoneMapComparisonOwnsSemanticOverlays(offlineActive, onlineActive),
                )
                assertTrue(diagnostic.toReportSection(0L).contains("Location owner: $expectedMarkerOwner"))
                if (base == PhoneMapSource.Online) assertEquals("base", diagnostic.locationFollowOwner)
            }
        }
    }

    @After
    fun clearDiagnostics() {
        PhoneMapStateDiagnostics.clear()
        PhoneDebugCapture.stop()
    }

    @Test
    fun captureStartSeedsCurrentMapAndUnchangedStateDoesNotLogAgain() {
        val state = state()
        PhoneDebugCapture.start()
        val firstSession = PhoneDebugCapture.state.value.sessionId
        PhoneMapStateDiagnostics.record(state, firstSession, captureActive = true, timestampMs = 100L)

        val section =
            requireNotNull(PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, MAP_STATE_SECTION_KEY))
        assertTrue(section.contains("Captured at (epoch ms): 100"))
        assertTrue(section.contains("Base renderer: maplibre-base"))
        assertTrue(section.contains("generation=4"))
        assertTrue(section.contains("Camera authority: base"))
        assertFalse(section.contains("latitude"))
        val transitionCount = PhoneDebugCapture.snapshot().size

        PhoneMapStateDiagnostics.record(state, firstSession, captureActive = true, timestampMs = 101L)
        assertEquals(transitionCount, PhoneDebugCapture.snapshot().size)

        PhoneDebugCapture.stop()
        PhoneDebugCapture.start()
        val secondSession = PhoneDebugCapture.state.value.sessionId
        PhoneMapStateDiagnostics.record(state, secondSession, captureActive = true, timestampMs = 102L)
        val restartedSection =
            requireNotNull(PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, MAP_STATE_SECTION_KEY))
        assertTrue(secondSession > firstSession)
        assertTrue(restartedSection.contains("Captured at (epoch ms): 102"))
    }

    @Test
    fun opacityChangesKeepTheLatestSnapshotWithoutDuplicatingOwnershipTransitions() {
        PhoneDebugCapture.start()
        val sessionId = requireNotNull(PhoneDebugCapture.activeSessionId())
        val initial = state().copy(comparisonTransparencyPercent = 0f, appliedComparisonOpacity = 1f)
        PhoneMapStateDiagnostics.record(initial, sessionId, captureActive = true)
        val initialLogs = PhoneDebugCapture.snapshot().size

        (1..99).forEach { transparency ->
            PhoneMapStateDiagnostics.record(
                initial.copy(
                    comparisonTransparencyPercent = transparency.toFloat(),
                    appliedComparisonOpacity = 1f - transparency / 100f,
                ),
                sessionId,
                captureActive = true,
            )
        }
        assertEquals(initialLogs, PhoneDebugCapture.snapshot().size)
        assertTrue(
            requireNotNull(PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, MAP_STATE_SECTION_KEY))
                .contains("Comparison transparency: 99.0%"),
        )

        PhoneMapStateDiagnostics.record(
            initial.copy(touchOwner = "comparison", overlayOwner = "comparison", locationOwner = "comparison"),
            sessionId,
            captureActive = true,
        )
        assertEquals(initialLogs + 1, PhoneDebugCapture.snapshot().size)
    }

    @Test
    fun offlineMapRevisionTransitionUsesOnlyOpaqueIdsForTheSameSelection() {
        val previous =
            mapOf(
                PhoneOfflineMapRendererRole.BASE to
                    PhoneMapOfflineRevisionSelection("/private/maps/alps.map", "offline-old"),
            )
        val changed =
            mapOf(
                PhoneOfflineMapRendererRole.BASE to
                    PhoneMapOfflineRevisionSelection("/private/maps/alps.map", "offline-new"),
            )

        val line = phoneMapOfflineRevisionChangeLines(previous, changed).single()

        assertTrue(line.contains("role=base previous=offline-old current=offline-new"))
        assertFalse(line.contains("/private/maps"))
        assertTrue(
            phoneMapOfflineRevisionChangeLines(
                previous,
                mapOf(
                    PhoneOfflineMapRendererRole.BASE to
                        PhoneMapOfflineRevisionSelection("/private/maps/new.map", "offline-next"),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun repeatedRevisionSnapshotEmitsOnlyOneTransition() {
        PhoneDebugCapture.start()
        val sessionId = requireNotNull(PhoneDebugCapture.activeSessionId())
        val original =
            mapOf(
                PhoneOfflineMapRendererRole.BASE to
                    PhoneMapOfflineRevisionSelection("private-selection", "offline-old"),
            )
        val replacement =
            mapOf(
                PhoneOfflineMapRendererRole.BASE to
                    PhoneMapOfflineRevisionSelection("private-selection", "offline-new"),
            )
        PhoneMapStateDiagnostics.record(state(), sessionId, captureActive = true, offlineMapSelections = original)
        PhoneMapStateDiagnostics.record(state(), sessionId, captureActive = true, offlineMapSelections = replacement)
        PhoneMapStateDiagnostics.record(state(), sessionId, captureActive = true, offlineMapSelections = replacement)

        assertEquals(1, PhoneDebugCapture.snapshot().count { it.contains("event=offline_map_revision_changed") })
        assertFalse(PhoneDebugCapture.snapshot().joinToString().contains("private-selection"))
    }

    private fun state() =
        PhoneMapDiagnosticState(
            requestedBaseSourceId = "open_topo_map",
            effectiveBaseSourceId = "open_topo_map",
            baseFallbackReason = null,
            requestedComparisonSourceId = "none",
            effectiveComparisonSourceId = "none",
            comparisonFallbackReason = null,
            baseRenderer = PhoneMapDiagnosticRenderer("maplibre-base", 4L, PhoneMapDiagnosticRendererStatus.ACTIVE),
            comparisonRenderer = null,
            comparisonMode = "none",
            comparisonTransparencyPercent = null,
            appliedComparisonOpacity = null,
            cameraAuthority = "base",
            touchOwner = "base",
            overlayOwner = "base",
            locationOwner = "base",
            locationFollowOwner = "base",
            followMode = "free",
            orientation = "north_up",
            panelMode = "closed",
            panelTool = "none",
            panelContent = "main",
            toolLauncherExpanded = false,
        )
}
