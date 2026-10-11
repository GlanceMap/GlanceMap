package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterDefaults
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.buildRecordingDashboardSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.mapsforge.core.model.LatLong

class GpxLibrarySummaryTest {
    private val sig = FileSig(1L, 100L)
    private val filterConfig = GpxElevationFilterDefaults.defaultConfig()
    private val etaConfig = GpxEtaModelConfig(flatSpeedMps = 1.0)
    private val points =
        listOf(
            TrackPoint(LatLong(45.0, 6.0), 100.0),
            TrackPoint(LatLong(45.001, 6.001), 110.0),
        )

    @Test
    fun `unchanged libraries reuse summaries after full profiles and projections are evicted`() =
        runTest {
            for (size in listOf(0, 3, 40)) {
                val paths = List(size) { "track-$it.gpx" }
                val summaries = LinkedHashMap<String, GpxLibrarySummary>(64, 0.75f, true)
                val profiles = LinkedHashMap<String, TrackProfile>(16, 0.75f, true)
                val projections = LinkedHashMap<String, GpxEtaProjection?>(16, 0.75f, true)
                var profileBuilds = 0
                var etaBuilds = 0

                suspend fun reload(activePaths: Set<String>): List<GpxFileState>? =
                    processFilesUntilCurrent(
                        files = paths,
                        isCurrent = { true },
                        processFile = { path ->
                            summaries[path].loadOrReuseGpxFileState(sig, filterConfig, etaConfig, path in activePaths) {
                                profileBuilds += 1
                                val profile = buildProfile(sig, points, filterConfig)
                                profiles[path] = profile
                                profiles.trimTo(24)
                                etaBuilds += 1
                                val projection = buildEtaProjection(profile, etaConfig)
                                projections[path] = projection
                                projections.trimTo(24)
                                val state =
                                    fileState(path).copy(
                                        distance = profile.totalDistance,
                                        elevationGain = profile.totalAscent,
                                        elevationLoss = profile.totalDescent,
                                        estimatedDurationSec = projection?.totalSeconds,
                                        isActive = path in activePaths,
                                    )
                                summaries[path] = summary(state)
                                summaries.trimTo(128)
                                state
                            }
                        },
                    )

                val cold = requireNotNull(reload(emptySet()))
                assertEquals(size, profileBuilds)
                assertEquals(minOf(size, 24), profiles.size)
                assertEquals(minOf(size, 24), projections.size)
                val activePaths = paths.take(1).toSet()
                val warm = requireNotNull(reload(activePaths))

                assertEquals(cold.map { it.copy(isActive = it.path in activePaths) }, warm)
                assertEquals(size, profileBuilds)
                assertEquals(size, etaBuilds)
                assertEquals(size, summaries.size)
            }
        }

    @Test
    fun `file signature and elevation or ETA changes require fresh processing`() =
        runTest {
            val cached = summary()
            val fresh = fileState().copy(distance = 500.0)
            var loads = 0
            val load: suspend () -> GpxFileState = {
                loads += 1
                fresh
            }

            assertSame(
                fresh,
                cached.loadOrReuseGpxFileState(sig.copy(lastModified = 2L), filterConfig, etaConfig, false, load),
            )
            assertSame(
                fresh,
                cached.loadOrReuseGpxFileState(sig.copy(length = 200L), filterConfig, etaConfig, false, load),
            )
            assertSame(
                fresh,
                cached.loadOrReuseGpxFileState(
                    sig,
                    filterConfig.copy(smoothingDistanceMeters = filterConfig.smoothingDistanceMeters + 1f),
                    etaConfig,
                    false,
                    load,
                ),
            )
            for (changedEta in listOf(etaConfig.copy(flatSpeedMps = 2.0), etaConfig.copy(userWeightKg = 90.0))) {
                assertSame(fresh, cached.loadOrReuseGpxFileState(sig, filterConfig, changedEta, false, load))
            }
            val absent: GpxLibrarySummary? = null
            assertSame(fresh, absent.loadOrReuseGpxFileState(sig, filterConfig, etaConfig, false, load))
            assertEquals(6, loads)
        }

    @Test
    fun `cached null ETA is reusable and selection changes only the active flag`() =
        runTest {
            val state = fileState().copy(estimatedDurationSec = null)
            val cached = summary(state)
            val reused =
                cached.loadOrReuseGpxFileState(sig, filterConfig, etaConfig, false) { error("unexpected load") }
            val selected =
                cached.loadOrReuseGpxFileState(sig, filterConfig, etaConfig, true) { error("unexpected load") }

            assertSame(state, reused)
            assertEquals(state.copy(isActive = true), selected)
        }

    @Test
    fun `saved activity metrics survive reuse while DEM derived elevation still refreshes`() =
        runTest {
            val recording =
                buildRecordingDashboardSnapshot(
                    TraceRecordingUiState(active = true, distanceMeters = 10_500.0),
                    nowMillis = 3_600_000L,
                ).copy(hasElevationData = true)
            val state =
                fileState().copy(
                    distance = 10_000.0,
                    isActivity = true,
                    activitySummary = recording,
                )
            val cached = summary(state)
            val reused = cached.loadOrReuseGpxFileState(sig, filterConfig, etaConfig, true) { error("unexpected load") }

            assertEquals(state.copy(isActive = true), reused)
            assertSame(recording, reused.activitySummary)
            assertEquals(10_500.0, requireNotNull(reused.activitySummary).distanceMeters, 0.0)

            val recovered = state.copy(elevationGain = 250.0)
            var recoveryLoads = 0
            val recoveryPending = cached.copy(requiresDemRecovery = true)
            repeat(2) {
                assertSame(
                    recovered,
                    recoveryPending.loadOrReuseGpxFileState(sig, filterConfig, etaConfig, false) {
                        recoveryLoads += 1
                        recovered
                    },
                )
            }
            assertEquals(2, recoveryLoads)
        }

    @Test
    fun `cancellation during a required load is propagated`() =
        runTest {
            val cancelled = CancellationException("cancelled GPX load")
            try {
                summary().loadOrReuseGpxFileState(sig.copy(length = 200L), filterConfig, etaConfig, false) {
                    throw cancelled
                }
                fail("Cancellation must propagate")
            } catch (caught: CancellationException) {
                assertSame(cancelled, caught)
            }
        }

    private fun summary(
        state: GpxFileState = fileState(),
    ): GpxLibrarySummary =
        GpxLibrarySummary(
            sig = sig,
            elevationFilterConfig = filterConfig,
            etaModelConfig = etaConfig,
            fileState = state,
            requiresDemRecovery = false,
        )

    private fun fileState(path: String = "track.gpx"): GpxFileState =
        GpxFileState(
            name = path,
            path = path,
            title = "Track",
            distance = 100.0,
            elevationGain = 10.0,
            elevationLoss = 5.0,
            estimatedDurationSec = 120.0,
        )
}
