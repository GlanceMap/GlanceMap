package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureSlot
import com.glancemap.glancemapcompanionapp.diagnostics.phoneDebugReportSections
import com.glancemap.glancemapcompanionapp.diagnostics.phoneDiagnosticsAdditionalSections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class PhoneOfflineMapRendererDiagnosticsTest {
    @After
    fun clearDiagnostics() {
        PhoneOfflineMapImportDiagnostics.clear()
        PhoneOfflineMapRendererDiagnostics.clear()
        PhoneCompassDiagnostics.clear()
        PhoneMapStateDiagnostics.clear()
        PhoneDebugCapture.stop()
    }

    @Test
    fun readySnapshotIdentifiesActiveRendererAndTimestamp() {
        val identity = rendererIdentity(nowMs = 10L)
        PhoneDebugCapture.start()
        PhoneOfflineMapRendererDiagnostics.record(attempt(identity))

        val snapshot = requireNotNull(PhoneOfflineMapRendererDiagnostics.snapshot(PhoneOfflineMapRendererRole.BASE))
        val report = requireNotNull(PhoneOfflineMapRendererDiagnostics.latestReportSection())
        assertEquals(identity, snapshot.identity)
        assertEquals(PhoneOfflineMapRendererStatus.ACTIVE, snapshot.status)
        assertTrue(snapshot.updatedAtMs >= 10L)
        assertTrue(report.contains("Renderer id: ${identity.rendererId}"))
        assertTrue(report.contains("Generation: ${identity.generation}"))
        assertTrue(report.contains("Status: ACTIVE"))
        assertTrue(report.contains("Updated at (epoch ms):"))
        assertTrue(report.contains("Result: READY"))
        assertTrue(PhoneDebugCapture.snapshot().single().contains("role=base"))
    }

    @Test
    fun disposedSnapshotGetsFreshTimestampAndStaleDisposalCannotAffectReplacement() {
        val oldIdentity = rendererIdentity(nowMs = 100L)
        assertTrue(PhoneOfflineMapRendererDiagnostics.markDisposed(oldIdentity, nowMs = 120L))
        val disposed = requireNotNull(PhoneOfflineMapRendererDiagnostics.snapshot(PhoneOfflineMapRendererRole.BASE))
        assertEquals(PhoneOfflineMapRendererStatus.DISPOSED, disposed.status)
        assertEquals(120L, disposed.updatedAtMs)

        val replacement =
            PhoneOfflineMapRendererDiagnostics.registerRenderer(
                PhoneOfflineMapRendererRole.BASE,
                nowMs = 130L,
            )
        assertNotEquals(oldIdentity.rendererId, replacement.rendererId)
        assertFalse(PhoneOfflineMapRendererDiagnostics.markDisposed(oldIdentity, nowMs = 140L))
        val current = requireNotNull(PhoneOfflineMapRendererDiagnostics.snapshot(PhoneOfflineMapRendererRole.BASE))
        assertEquals(replacement, current.identity)
        assertEquals(PhoneOfflineMapRendererStatus.ACTIVE, current.status)
        assertEquals(130L, current.updatedAtMs)
    }

    @Test
    fun baseAndComparisonSnapshotsKeepTheirOwnRuntimeByIdentityAndGeneration() {
        val baseIdentity =
            rendererIdentity(
                role = PhoneOfflineMapRendererRole.BASE,
                offlineMapId = "offline-shared-source",
                nowMs = 1L,
            )
        val comparisonIdentity =
            rendererIdentity(
                role = PhoneOfflineMapRendererRole.COMPARISON,
                offlineMapId = "offline-shared-source",
                nowMs = 2L,
            )
        PhoneOfflineMapRendererDiagnostics.record(attempt(baseIdentity))
        PhoneOfflineMapRendererDiagnostics.record(attempt(comparisonIdentity))
        PhoneOfflineMapRendererDiagnostics.recordRuntime(runtime(baseIdentity, zoom = 12))
        PhoneOfflineMapRendererDiagnostics.recordRuntime(runtime(comparisonIdentity, zoom = 16))

        val base = requireNotNull(PhoneOfflineMapRendererDiagnostics.snapshot(PhoneOfflineMapRendererRole.BASE))
        val comparison =
            requireNotNull(
                PhoneOfflineMapRendererDiagnostics.snapshot(PhoneOfflineMapRendererRole.COMPARISON),
            )
        assertEquals(baseIdentity.rendererId, base.runtime?.rendererId)
        assertEquals(12, base.runtime?.zoom)
        assertEquals(comparisonIdentity.rendererId, comparison.runtime?.rendererId)
        assertEquals(16, comparison.runtime?.zoom)

        val report = requireNotNull(PhoneOfflineMapRendererDiagnostics.latestReportSection())
        assertTrue(report.contains("base offline renderer"))
        assertTrue(report.contains("comparison offline renderer"))
        assertEquals(2, Regex("Runtime details").findAll(report).count())
    }

    @Test
    fun staleRuntimeFromAnEarlierGenerationIsIgnored() {
        val first = rendererIdentity(nowMs = 10L)
        val next = PhoneOfflineMapRendererDiagnostics.beginGeneration(first, "offline-next", nowMs = 20L)
        PhoneOfflineMapRendererDiagnostics.recordRuntime(runtime(first, zoom = 7))

        val current = requireNotNull(PhoneOfflineMapRendererDiagnostics.snapshot(PhoneOfflineMapRendererRole.BASE))
        assertEquals(next, current.identity)
        assertNull(current.runtime)
    }

    @Test
    fun failuresKeepTheirStageWithoutSerializingExceptionMessages() {
        listOf(
            PhoneOfflineMapRendererStage.TILE_CACHE_CREATE,
            PhoneOfflineMapRendererStage.THEME_APPLY,
            PhoneOfflineMapRendererStage.LAYER_ATTACH,
        ).forEachIndexed { index, stage ->
            val identity = rendererIdentity(nowMs = 30L + index * 10L)
            val error = IOException("Could not open content://private/resource from /private/maps/map.map")
            PhoneOfflineMapRendererDiagnostics.record(
                attempt(
                    identity = identity,
                    outcome = PhoneOfflineMapRendererOutcome.FAILED,
                    failureStage = stage,
                    exceptionClass = error.toPhoneOfflineMapRendererException().className,
                ),
            )

            val report = requireNotNull(PhoneOfflineMapRendererDiagnostics.latestReportSection())
            assertEquals(stage, requireNotNull(PhoneOfflineMapRendererDiagnostics.latestAttempt()).failureStage)
            assertTrue(report.contains("Failure stage: $stage"))
            assertFalse(report.contains("content://"))
            assertFalse(report.contains("/private/maps"))
        }
    }

    @Test
    fun rendererReportAppearsOnlyOnceWhenCaptureAndAdditionalSectionsBothContainIt() {
        val identity = rendererIdentity()
        PhoneDebugCapture.start()
        PhoneOfflineMapRendererDiagnostics.record(attempt(identity))
        PhoneOfflineMapRendererDiagnostics.recordRuntime(runtime(identity, zoom = 14))

        val sections = phoneDiagnosticsAdditionalSections()
        assertEquals(1, sections.count { it.startsWith("Offline map renderer snapshots") })
        assertNull(PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, "offline_map_runtime"))
        val capturedReport = requireNotNull(PhoneOfflineMapRendererDiagnostics.latestReportSection())
        val combined =
            phoneDebugReportSections(
                capturedSections =
                    mapOf(
                        "offline_map_renderer" to capturedReport,
                        "offline_map_runtime" to "Offline map runtime\nZoom: 99",
                    ),
                additionalSections =
                    listOf(
                        capturedReport.replace("Updated at (epoch ms):", "Updated at (epoch ms): stale"),
                    ),
            )
        assertEquals(1, combined.count { it.startsWith("Offline map renderer snapshots") })
        assertEquals(1, combined.count { it.contains("Runtime details") })
    }

    @Test
    fun reportsUseOpaqueOfflineIdsAndDoNotSerializeRawExceptionMessages() {
        val offlineId = PhoneOfflineMap(File("/private/maps/alps.map")).opaqueDiagnosticId()
        val identity = rendererIdentity(offlineMapId = offlineId)
        val unsafeMessage = "Could not open content://provider/private?key=hidden from /private/maps/alps.map"
        val error = IOException(unsafeMessage).toPhoneOfflineMapRendererException()
        PhoneOfflineMapRendererDiagnostics.record(attempt(identity, exceptionClass = error.className))

        val report = requireNotNull(PhoneOfflineMapRendererDiagnostics.latestReportSection())
        assertTrue(report.contains(offlineId))
        assertFalse(report.contains("alps.map"))
        assertFalse(report.contains("/private/maps"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("key=hidden"))
        assertFalse(report.contains(unsafeMessage))
    }

    @Test
    fun exportedDiagnosticsKeepImportAndRendererSections() {
        PhoneOfflineMapImportDiagnostics.record(
            PhoneOfflineMapImportAttempt(
                outcome = PhoneOfflineMapImportOutcome.SUCCESS,
                failureStage = null,
                sourceSizeBytes = 1L,
                streamOpened = true,
                bytesCopied = 1L,
                destinationSizeBytes = 1L,
                candidateValid = true,
                mapFileOpened = true,
                metadata = null,
                finalError = null,
            ),
        )
        val identity = rendererIdentity()
        PhoneOfflineMapRendererDiagnostics.record(attempt(identity))

        val sections = phoneDiagnosticsAdditionalSections()

        assertEquals(2, sections.size)
        assertTrue(sections[0].startsWith("Latest offline map import"))
        assertTrue(sections[1].startsWith("Offline map renderer snapshots"))
    }

    private fun rendererIdentity(
        role: PhoneOfflineMapRendererRole = PhoneOfflineMapRendererRole.BASE,
        rendererIdSource: String = "map",
        offlineMapId: String = "offline-$rendererIdSource",
        nowMs: Long = System.currentTimeMillis(),
    ): PhoneOfflineMapRendererIdentity =
        PhoneOfflineMapRendererDiagnostics.beginGeneration(
            previous = PhoneOfflineMapRendererDiagnostics.registerRenderer(role, nowMs),
            offlineMapId = offlineMapId,
            nowMs = nowMs + 1L,
        )

    private fun attempt(
        identity: PhoneOfflineMapRendererIdentity,
        outcome: PhoneOfflineMapRendererOutcome = PhoneOfflineMapRendererOutcome.READY,
        failureStage: PhoneOfflineMapRendererStage? = null,
        exceptionClass: String? = null,
    ) = PhoneOfflineMapRendererAttempt(
        outcome = outcome,
        role = identity.role,
        rendererId = identity.rendererId,
        generation = identity.generation,
        offlineMapId = requireNotNull(identity.offlineMapId),
        lastSuccessfulStage = PhoneOfflineMapRendererStage.MAPVIEW_CREATE,
        failureStage = failureStage,
        themeId = "elevate",
        styleId = "elv-hiking",
        mapFileOpened = true,
        tileCacheCreated = true,
        tileLayerAttached = true,
        themeObjectConstructed = true,
        themeApplied = true,
        viewAttached = true,
        firstCameraPublished = true,
        exceptionClass = exceptionClass,
    )

    private fun runtime(
        identity: PhoneOfflineMapRendererIdentity,
        zoom: Int,
    ): PhoneOfflineMapRuntimeDiagnostics =
        PhoneOfflineMapRuntimeDiagnostics(
            role = identity.role,
            rendererId = identity.rendererId,
            generation = identity.generation,
            offlineMapId = identity.offlineMapId,
            mapViewId = 22,
            layerId = 33,
            cacheId = "phone-offline-test",
            mapViewAttached = true,
            mapViewHasWindowFocus = true,
            mapViewWidth = 1080,
            mapViewHeight = 1800,
            mapViewRenderReady = true,
            androidMapViewDrawObserved = true,
            tileLayerDrawObserved = true,
            firstVisibleBaseTileObserved = true,
            layerCount = 2,
            tileLayerPresent = true,
            tileLayerVisible = true,
            frameBufferDimensionAvailable = true,
            frameBufferWidth = 1080,
            frameBufferHeight = 1800,
            frameBufferDrawingBitmapReady = true,
            zoom = zoom,
            cameraInsideMapBounds = true,
            visibleTileCount = 6,
            drawableVisibleTileCount = 2,
            parentFallbackTileCount = 1,
            pendingTileJobCount = 3,
            locationPermissionGranted = true,
            locationAvailable = false,
            locationAgeMillis = null,
            locationAccuracyMeters = null,
            locationInsideMapBounds = null,
            followMode = PhoneMapFollowMode.FREE,
            orientation = PhoneMapOrientation.NORTH_UP,
            locationMarkerAttached = false,
            locationMarkerVisible = null,
            locationMarkerDrawCalls = 0,
            locationMarkerBitmapDrawObserved = false,
            locationMarkerLastDrawResult = null,
        )
}
