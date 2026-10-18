package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PhoneMapLibreRenderDiagnosticsTest {
    @Test
    fun stoppingCaptureIncludesResourceErrorsSuppressedByTheThrottleEvenWithoutAnotherFrame() {
        val directory = Files.createTempDirectory("maplibre-capture-stop").toFile()
        PhoneDebugCapture.useTestStorage(directory)
        val tracker = PhoneMapLibreRendererHealthTracker(resourceSummaryIntervalMs = 5_000L)
        val sectionKey = PhoneMapLibreRenderDiagnostics.SECTION_KEY
        val publishSnapshot = {
            tracker.snapshots.toReportSectionOrNull()?.let {
                PhoneDebugCapture.updateSection(sectionKey, it)
            }
            Unit
        }
        val removeListener = PhoneDebugCapture.addBeforeStopListener(publishSnapshot)
        PhoneDebugCapture.start()
        val renderer = rendererIdentity(rendererId = 1L, generation = 1L)
        tracker.rendererCreated(renderer, nowElapsedMs = 0L)
        try {
            tracker.resourceFailure(renderer, PhoneMapLibreResourceFailureKind.TILE, nowElapsedMs = 100L)
            publishSnapshot()
            val suppressed =
                tracker.resourceFailure(renderer, PhoneMapLibreResourceFailureKind.GLYPHS, nowElapsedMs = 200L)
            assertEquals(false, suppressed?.publishSummary)
            val before =
                requireNotNull(PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, sectionKey))
            assertTrue(before.contains("tiles=1 glyphs=0 sprites=0"))

            PhoneDebugCapture.stop()
            PhoneDebugCapture.reloadFromStorageForTest()

            val exported =
                requireNotNull(PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, sectionKey))
            assertTrue(exported.contains("tiles=1 glyphs=1 sprites=0"))
        } finally {
            removeListener()
            PhoneDebugCapture.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulRenderClearsOnlyItsCurrentRenderErrorAndKeepsLoadFailures() {
        val base = baseFailure(generation = 4L).copy(category = PhoneMapLibreErrorCategory.RENDER)
        val comparison = base.copy(role = PhoneMapLibreRendererRole.COMPARISON)
        val state = PhoneMapLibreRenderErrorState(baseError = base, comparisonError = comparison)
        val source = PhoneMapLibreActiveSource(base.source, base.providerId)
        val errorSource = PhoneMapLibreRenderErrorSource(base.source, base.providerId)

        val recovered =
            requireNotNull(
                state.clearAfterRecoveryIfCurrent(
                    errorSource,
                    PhoneMapLibreRendererRole.BASE,
                    rendererIsCurrent = true,
                    activeSource = source,
                    recoveryCategory = PhoneMapLibreErrorCategory.RENDER,
                ),
            )
        assertNull(recovered.baseError)
        assertEquals(comparison, recovered.comparisonError)
        assertNull(
            state.clearAfterRecoveryIfCurrent(
                errorSource,
                PhoneMapLibreRendererRole.BASE,
                rendererIsCurrent = false,
                activeSource = source,
                recoveryCategory = PhoneMapLibreErrorCategory.RENDER,
            ),
        )
        assertNull(
            state.clearAfterRecoveryIfCurrent(
                errorSource,
                PhoneMapLibreRendererRole.BASE,
                rendererIsCurrent = true,
                activeSource = PhoneMapLibreActiveSource(null, null),
                recoveryCategory = PhoneMapLibreErrorCategory.RENDER,
            ),
        )
        val failedLoad = state.copy(baseError = base.copy(category = PhoneMapLibreErrorCategory.MAP_LOAD))
        assertNull(failedLoad.recordFailureIfCurrent(base, rendererIsCurrent = true, activeSource = source))
        assertNull(
            failedLoad.clearAfterRecoveryIfCurrent(
                errorSource,
                PhoneMapLibreRendererRole.BASE,
                rendererIsCurrent = true,
                activeSource = source,
                recoveryCategory = PhoneMapLibreErrorCategory.RENDER,
            ),
        )
        assertNull(
            recovered.clearAfterRecoveryIfCurrent(
                errorSource,
                PhoneMapLibreRendererRole.BASE,
                rendererIsCurrent = true,
                activeSource = source,
                recoveryCategory = PhoneMapLibreErrorCategory.RENDER,
            ),
        )
    }

    @Test
    fun previousRendererFailuresAreRejectedByGenerationAndIdentity() {
        val oldRenderer = Any()
        val currentRenderer = Any()
        val error = baseFailure(generation = 4L)

        assertNull(
            PhoneMapLibreRenderErrorState().recordFailureIfCurrent(
                error = error,
                rendererIsCurrent =
                    phoneMapLibreRendererCallbackIsCurrent(
                        callbackGeneration = error.generation,
                        currentGeneration = 5L,
                        callbackRenderer = oldRenderer,
                        currentRenderer = currentRenderer,
                    ),
                activeSource = PhoneMapLibreActiveSource(error.source, error.providerId),
            ),
        )
        assertNull(
            PhoneMapLibreRenderErrorState().recordFailureIfCurrent(
                error = error.copy(generation = 5L),
                rendererIsCurrent =
                    phoneMapLibreRendererCallbackIsCurrent(
                        callbackGeneration = 5L,
                        currentGeneration = 5L,
                        callbackRenderer = oldRenderer,
                        currentRenderer = currentRenderer,
                    ),
                activeSource = PhoneMapLibreActiveSource(error.source, error.providerId),
            ),
        )
        assertFalse(
            phoneMapLibreRendererCallbackIsCurrent(
                callbackGeneration = 4L,
                currentGeneration = 5L,
                callbackRenderer = oldRenderer,
                currentRenderer = currentRenderer,
            ),
        )
    }

    @Test
    fun comparisonFailureLeavesBaseHealthy() {
        val comparison =
            PhoneMapLibreRenderError(
                providerId = "cyclosm",
                source = PhoneOnlineMapSource.CYCLOSM,
                role = PhoneMapLibreRendererRole.COMPARISON,
                generation = 7L,
                category = PhoneMapLibreErrorCategory.MAP_LOAD,
            )
        val renderer = Any()
        val errors =
            PhoneMapLibreRenderErrorState().recordFailureIfCurrent(
                error = comparison,
                rendererIsCurrent =
                    phoneMapLibreRendererCallbackIsCurrent(
                        callbackGeneration = comparison.generation,
                        currentGeneration = comparison.generation,
                        callbackRenderer = renderer,
                        currentRenderer = renderer,
                    ),
                activeSource = PhoneMapLibreActiveSource(comparison.source, comparison.providerId),
            )

        assertEquals(null, requireNotNull(errors).baseError)
        assertEquals(comparison, errors.comparisonError)
    }

    @Test
    fun baseAndComparisonFailuresKeepIndependentStatus() {
        val base = baseFailure(generation = 2L)
        val comparison =
            PhoneMapLibreRenderError(
                providerId = "cyclosm",
                source = PhoneOnlineMapSource.CYCLOSM,
                role = PhoneMapLibreRendererRole.COMPARISON,
                generation = 7L,
                category = PhoneMapLibreErrorCategory.MAP_LOAD,
            )
        val baseRenderer = Any()
        val comparisonRenderer = Any()
        val baseOnly =
            PhoneMapLibreRenderErrorState().recordFailureIfCurrent(
                error = base,
                rendererIsCurrent =
                    phoneMapLibreRendererCallbackIsCurrent(
                        callbackGeneration = base.generation,
                        currentGeneration = base.generation,
                        callbackRenderer = baseRenderer,
                        currentRenderer = baseRenderer,
                    ),
                activeSource = PhoneMapLibreActiveSource(base.source, base.providerId),
            )
        val both =
            requireNotNull(baseOnly).recordFailureIfCurrent(
                error = comparison,
                rendererIsCurrent =
                    phoneMapLibreRendererCallbackIsCurrent(
                        callbackGeneration = comparison.generation,
                        currentGeneration = comparison.generation,
                        callbackRenderer = comparisonRenderer,
                        currentRenderer = comparisonRenderer,
                    ),
                activeSource = PhoneMapLibreActiveSource(comparison.source, comparison.providerId),
            )

        assertEquals(base, requireNotNull(both).baseError)
        assertEquals(comparison, both.comparisonError)
        assertEquals(
            listOf(base, comparison),
            both.errorsForSources(
                PhoneMapLibreActiveSource(base.source, base.providerId),
                PhoneMapLibreActiveSource(comparison.source, comparison.providerId),
            ),
        )
    }

    @Test
    fun currentSuccessfulLoadClearsOnlyThatRole() {
        val base = baseFailure(generation = 2L)
        val comparison =
            PhoneMapLibreRenderError(
                providerId = "cyclosm",
                source = PhoneOnlineMapSource.CYCLOSM,
                role = PhoneMapLibreRendererRole.COMPARISON,
                generation = 7L,
                category = PhoneMapLibreErrorCategory.RENDER,
            )
        val state = PhoneMapLibreRenderErrorState(baseError = base, comparisonError = comparison)
        val baseRenderer = Any()
        val comparisonRenderer = Any()

        val afterBaseLoad =
            state.clearAfterRecoveryIfCurrent(
                source = PhoneMapLibreRenderErrorSource(base.source, base.providerId),
                role = PhoneMapLibreRendererRole.BASE,
                rendererIsCurrent =
                    phoneMapLibreRendererCallbackIsCurrent(
                        callbackGeneration = base.generation,
                        currentGeneration = base.generation,
                        callbackRenderer = baseRenderer,
                        currentRenderer = baseRenderer,
                    ),
                activeSource = PhoneMapLibreActiveSource(base.source, base.providerId),
            )
        assertEquals(null, requireNotNull(afterBaseLoad).baseError)
        assertEquals(comparison, afterBaseLoad.comparisonError)

        assertNull(
            afterBaseLoad.clearAfterRecoveryIfCurrent(
                source = PhoneMapLibreRenderErrorSource(comparison.source, comparison.providerId),
                role = PhoneMapLibreRendererRole.COMPARISON,
                rendererIsCurrent =
                    phoneMapLibreRendererCallbackIsCurrent(
                        callbackGeneration = comparison.generation,
                        currentGeneration = comparison.generation,
                        callbackRenderer = comparisonRenderer,
                        currentRenderer = Any(),
                    ),
                activeSource = PhoneMapLibreActiveSource(comparison.source, comparison.providerId),
            ),
        )
    }

    @Test
    fun sourceAndProviderChangesClearObsoleteErrors() {
        val base = baseFailure(generation = 2L)
        val comparison =
            PhoneMapLibreRenderError(
                providerId = "cyclosm",
                source = PhoneOnlineMapSource.CYCLOSM,
                role = PhoneMapLibreRendererRole.COMPARISON,
                generation = 7L,
                category = PhoneMapLibreErrorCategory.RENDER,
            )
        val state = PhoneMapLibreRenderErrorState(baseError = base, comparisonError = comparison)

        val afterSourceChange =
            state.clearObsoleteSources(
                baseSource = PhoneMapLibreActiveSource(base.source, base.providerId),
                comparisonSource =
                    PhoneMapLibreActiveSource(PhoneOnlineMapSource.PLAN_IGN_V2, "plan_ign_v2"),
            )
        assertEquals(null, afterSourceChange.comparisonError)
        assertEquals(
            listOf(base),
            afterSourceChange
                .errorsForSources(
                    PhoneMapLibreActiveSource(base.source, base.providerId),
                    PhoneMapLibreActiveSource(PhoneOnlineMapSource.PLAN_IGN_V2, "plan_ign_v2"),
                ),
        )

        val afterProviderChange =
            state.clearObsoleteSources(
                baseSource = PhoneMapLibreActiveSource(base.source, base.providerId),
                comparisonSource = PhoneMapLibreActiveSource(comparison.source, "fallback_provider"),
            )
        assertEquals(null, afterProviderChange.comparisonError)
        assertEquals(
            listOf(base),
            state.errorsForSources(
                PhoneMapLibreActiveSource(base.source, base.providerId),
                PhoneMapLibreActiveSource(comparison.source, "fallback_provider"),
            ),
        )
    }

    @Test
    fun repeatedFailureProducesOnlyOneStateChangeForEachRoleAndCategory() {
        PhoneMapLibreRendererRole.entries.forEach { role ->
            val error = baseFailure(generation = 2L).copy(role = role)
            val source = PhoneMapLibreActiveSource(error.source, error.providerId)
            var state = PhoneMapLibreRenderErrorState()
            var diagnosticEntries = 0

            repeat(100) {
                state.recordFailureIfCurrent(error, rendererIsCurrent = true, activeSource = source)?.let { next ->
                    state = next
                    diagnosticEntries += 1
                }
            }

            assertEquals(1, diagnosticEntries)
            val changedCategory = error.copy(category = PhoneMapLibreErrorCategory.MAP_LOAD)
            val afterChangedCategory =
                requireNotNull(
                    state.recordFailureIfCurrent(changedCategory, rendererIsCurrent = true, activeSource = source),
                )
            assertNull(
                afterChangedCategory.recordFailureIfCurrent(
                    changedCategory,
                    rendererIsCurrent = true,
                    activeSource = source,
                ),
            )
            assertEquals(error, state.errorsForSources(source, source).single())
        }
    }

    @Test
    fun newRendererAndRecurrenceAfterSuccessfulLoadAreStillRecorded() {
        val error = baseFailure(generation = 2L)
        val source = PhoneMapLibreActiveSource(error.source, error.providerId)
        val state = PhoneMapLibreRenderErrorState(baseError = error)
        val replacementFailure = error.copy(generation = 3L)
        val afterReplacement =
            requireNotNull(
                state.recordFailureIfCurrent(replacementFailure, rendererIsCurrent = true, activeSource = source),
            )
        val recovered =
            requireNotNull(
                afterReplacement.clearAfterRecoveryIfCurrent(
                    source = PhoneMapLibreRenderErrorSource(error.source, error.providerId),
                    role = PhoneMapLibreRendererRole.BASE,
                    rendererIsCurrent = true,
                    activeSource = source,
                ),
            )

        assertEquals(replacementFailure, afterReplacement.baseError)
        assertNull(recovered.baseError)
        assertEquals(
            replacementFailure,
            requireNotNull(
                recovered.recordFailureIfCurrent(
                    replacementFailure,
                    rendererIsCurrent = true,
                    activeSource = source,
                ),
            ).baseError,
        )
    }

    @Test
    fun staleSourceCallbacksAndClearedSelectionsCannotKeepOrClearCurrentErrors() {
        val base = baseFailure(generation = 2L)
        val comparison = base.copy(role = PhoneMapLibreRendererRole.COMPARISON)
        val state = PhoneMapLibreRenderErrorState(baseError = base, comparisonError = comparison)
        val differentSource = PhoneMapLibreActiveSource(PhoneOnlineMapSource.CYCLOSM, "cyclosm")
        val noSource = PhoneMapLibreActiveSource(null, null)

        assertNull(state.recordFailureIfCurrent(base, rendererIsCurrent = true, activeSource = differentSource))
        assertNull(
            state.clearAfterRecoveryIfCurrent(
                source = PhoneMapLibreRenderErrorSource(base.source, base.providerId),
                role = PhoneMapLibreRendererRole.BASE,
                rendererIsCurrent = true,
                activeSource = differentSource,
            ),
        )
        assertEquals(
            PhoneMapLibreRenderErrorState(),
            state.clearObsoleteSources(noSource, noSource),
        )
        assertEquals(emptyList<PhoneMapLibreRenderError>(), state.errorsForSources(noSource, noSource))
    }

    @Test
    fun lifecycleEventsAreSuppressedUntilTheRendererChangesState() {
        val tracker = PhoneMapLibreRendererHealthTracker()
        val renderer = rendererIdentity(rendererId = 11L, generation = 3L)

        assertEquals(
            PhoneMapLibreLifecycleKind.RENDERER_CREATED,
            tracker
                .rendererCreated(renderer, nowElapsedMs = 10L)
                ?.events
                ?.single()
                ?.kind,
        )
        assertNull(tracker.rendererCreated(renderer, nowElapsedMs = 11L))
        assertEquals(
            PhoneMapLibreLifecycleKind.STYLE_LOAD_STARTED,
            tracker
                .styleLoadStarted(renderer, nowElapsedMs = 12L)
                ?.events
                ?.single()
                ?.kind,
        )
        assertNull(tracker.styleLoadStarted(renderer, nowElapsedMs = 13L))
        assertEquals(
            PhoneMapLibreLifecycleKind.STYLE_LOAD_COMPLETED,
            tracker
                .styleLoadCompleted(renderer, nowElapsedMs = 14L)
                ?.events
                ?.single()
                ?.kind,
        )
        assertNull(tracker.styleLoadCompleted(renderer, nowElapsedMs = 15L))

        val firstFrame = tracker.renderFrameFinished(renderer, fullyRendered = false, nowElapsedMs = 16L)
        assertEquals(PhoneMapLibreLifecycleKind.RENDER_FRAME_FINISHED, firstFrame?.events?.single()?.kind)
        assertNull(tracker.renderFrameFinished(renderer, fullyRendered = false, nowElapsedMs = 17L))
        assertEquals(
            PhoneMapLibreLifecycleKind.FULL_RENDER_FRAME_FINISHED,
            tracker
                .renderFrameFinished(renderer, fullyRendered = true, nowElapsedMs = 18L)
                ?.events
                ?.single()
                ?.kind,
        )
        assertNull(tracker.renderFrameFinished(renderer, fullyRendered = true, nowElapsedMs = 19L))

        assertEquals(
            PhoneMapLibreLifecycleKind.FAILURE,
            tracker
                .failure(renderer, PhoneMapLibreErrorCategory.RENDER, nowElapsedMs = 20L)
                ?.events
                ?.single()
                ?.kind,
        )
        assertNull(tracker.failure(renderer, PhoneMapLibreErrorCategory.RENDER, nowElapsedMs = 21L))
        assertEquals(
            PhoneMapLibreLifecycleKind.RECOVERY,
            tracker
                .renderFrameFinished(renderer, fullyRendered = true, nowElapsedMs = 22L)
                ?.events
                ?.single()
                ?.kind,
        )
        assertNull(tracker.renderFrameFinished(renderer, fullyRendered = true, nowElapsedMs = 23L))
        assertEquals(
            PhoneMapLibreLifecycleKind.DISPOSED,
            tracker
                .rendererDisposed(renderer, nowElapsedMs = 24L)
                ?.events
                ?.single()
                ?.kind,
        )
        assertNull(tracker.rendererDisposed(renderer, nowElapsedMs = 25L))
    }

    @Test
    fun callbacksFromReplacedRendererCannotChangeCurrentSnapshot() {
        val tracker = PhoneMapLibreRendererHealthTracker()
        val oldRenderer = rendererIdentity(rendererId = 1L, generation = 1L)
        val currentRenderer = rendererIdentity(rendererId = 2L, generation = 2L)
        tracker.rendererCreated(oldRenderer, nowElapsedMs = 1L)
        tracker.rendererCreated(currentRenderer, nowElapsedMs = 2L)

        assertNull(tracker.styleLoadCompleted(oldRenderer, nowElapsedMs = 3L))
        assertNull(tracker.failure(oldRenderer, PhoneMapLibreErrorCategory.MAP_LOAD, nowElapsedMs = 4L))
        assertNull(
            tracker.resourceFailure(oldRenderer, PhoneMapLibreResourceFailureKind.TILE, nowElapsedMs = 5L),
        )
        val oldDisposal = tracker.rendererDisposed(oldRenderer, nowElapsedMs = 6L)

        assertEquals(PhoneMapLibreLifecycleKind.DISPOSED, oldDisposal?.events?.single()?.kind)
        assertEquals(false, oldDisposal?.publishSummary)
        val current = tracker.snapshots.single()
        assertEquals(currentRenderer, current.identity)
        assertEquals(PhoneMapLibreRendererStatus.ACTIVE, current.status)
        assertNull(current.lastFailureCategory)
        assertEquals(0, current.resourceFailures.tile)
    }

    @Test
    fun lateStyleCallbackFromDisposedRendererCannotReplaceCurrentProviderSnapshot() {
        val tracker = PhoneMapLibreRendererHealthTracker()
        val openTopo = rendererIdentity(rendererId = 1L, generation = 1L)
        val ign =
            rendererIdentity(rendererId = 2L, generation = 2L).copy(
                source = PhoneOnlineMapSource.IGN_ORTHOPHOTO,
                providerId = "ign_orthophoto",
            )
        tracker.rendererCreated(openTopo, nowElapsedMs = 1L)
        tracker.styleLoadStarted(openTopo, nowElapsedMs = 2L)
        tracker.rendererDisposed(openTopo, nowElapsedMs = 3L)
        tracker.rendererCreated(ign, nowElapsedMs = 4L)

        assertNull(tracker.styleLoadCompleted(openTopo, nowElapsedMs = 5L))
        assertEquals(ign, tracker.snapshots.single().identity)
        assertEquals(PhoneMapLibreRendererStatus.ACTIVE, tracker.snapshots.single().status)
        assertNull(tracker.snapshots.single().styleLoadCompletedAtElapsedMs)
    }

    @Test
    fun baseAndComparisonHealthAreKeptAsSeparateRendererSnapshots() {
        val tracker = PhoneMapLibreRendererHealthTracker()
        val base = rendererIdentity(rendererId = 10L, generation = 1L)
        val comparison =
            rendererIdentity(
                rendererId = 20L,
                generation = 1L,
                role = PhoneMapLibreRendererRole.COMPARISON,
            ).copy(
                source = PhoneOnlineMapSource.CYCLOSM,
                providerId = "cyclosm",
            )
        tracker.rendererCreated(base, nowElapsedMs = 1L)
        tracker.rendererCreated(comparison, nowElapsedMs = 2L)
        tracker.styleLoadStarted(base, nowElapsedMs = 3L)
        tracker.failure(comparison, PhoneMapLibreErrorCategory.MAP_LOAD, nowElapsedMs = 4L)

        val snapshots = tracker.snapshots
        assertEquals(
            listOf(PhoneMapLibreRendererRole.BASE, PhoneMapLibreRendererRole.COMPARISON),
            snapshots.map { it.identity.role },
        )
        assertEquals(null, snapshots[0].lastFailureCategory)
        assertEquals(PhoneMapLibreErrorCategory.MAP_LOAD, snapshots[1].lastFailureCategory)
        val report = requireNotNull(tracker.snapshots.toReportSectionOrNull())
        assertTrue(report.contains("base renderer"))
        assertTrue(report.contains("comparison renderer"))
    }

    @Test
    fun tileAndResourceErrorsAreAggregatedAndSummaryIsThrottled() {
        val tracker = PhoneMapLibreRendererHealthTracker(resourceSummaryIntervalMs = 5_000L)
        val renderer = rendererIdentity(rendererId = 7L, generation = 4L)
        tracker.rendererCreated(renderer, nowElapsedMs = 0L)

        val first = tracker.resourceFailure(renderer, PhoneMapLibreResourceFailureKind.TILE, nowElapsedMs = 100L)
        val second = tracker.resourceFailure(renderer, PhoneMapLibreResourceFailureKind.GLYPHS, nowElapsedMs = 200L)
        val third = tracker.resourceFailure(renderer, PhoneMapLibreResourceFailureKind.SPRITE, nowElapsedMs = 5_100L)

        assertEquals(PhoneMapLibreLifecycleKind.RESOURCE_FAILURE_SUMMARY, first?.events?.single()?.kind)
        assertEquals(false, second?.publishSummary)
        assertEquals(PhoneMapLibreLifecycleKind.RESOURCE_FAILURE_SUMMARY, third?.events?.single()?.kind)
        val snapshot = tracker.snapshots.single()
        assertEquals(1, snapshot.resourceFailures.tile)
        assertEquals(1, snapshot.resourceFailures.glyphs)
        assertEquals(1, snapshot.resourceFailures.sprite)
        val report = requireNotNull(tracker.snapshots.toReportSectionOrNull())
        assertTrue(report.contains("Aggregate SDK resource errors: tiles=1 glyphs=1 sprites=1"))
        assertTrue(report.contains("HTTP status: unavailable"))
        assertFalse(report.contains("http"))
    }

    private fun rendererIdentity(
        rendererId: Long,
        generation: Long,
        role: PhoneMapLibreRendererRole = PhoneMapLibreRendererRole.BASE,
    ) = PhoneMapLibreRendererIdentity(
        role = role,
        rendererId = rendererId,
        generation = generation,
        source = PhoneOnlineMapSource.OPEN_TOPO,
        providerId = "open_topo_map",
    )

    private fun baseFailure(generation: Long): PhoneMapLibreRenderError =
        PhoneMapLibreRenderError(
            providerId = "open_topo_map",
            source = PhoneOnlineMapSource.OPEN_TOPO,
            role = PhoneMapLibreRendererRole.BASE,
            generation = generation,
            category = PhoneMapLibreErrorCategory.RENDER,
        )
}
