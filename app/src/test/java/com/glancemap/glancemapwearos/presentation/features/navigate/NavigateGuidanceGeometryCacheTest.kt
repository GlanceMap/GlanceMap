package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceSession
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceTuning
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.buildCumulativeDistances
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.buildGpxGuidanceSession
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.computeTurnByTurnGuidanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class NavigateGuidanceGeometryCacheTest {
    @Test
    fun guideBackDestinationDoesNotComputeWhenDisabled() {
        var computations = 0

        val destination =
            guideBackDestinationIfEnabled(enabled = false) {
                computations += 1
                LatLong(45.0, 6.0)
            }

        assertNull(destination)
        assertEquals(0, computations)
    }

    @Test
    fun guideBackDestinationStillComputesWhenEnabled() {
        var computations = 0
        val expected = LatLong(45.0, 6.0)

        val destination =
            guideBackDestinationIfEnabled(enabled = true) {
                computations += 1
                expected
            }

        assertEquals(expected, destination)
        assertEquals(1, computations)
    }

    @Test
    fun `unrelated recomposition reuses geometry but each fix still gets per fix processing`() {
        val cache = NavigateGuidanceGeometryCache()
        val session = session(trackId = "route.gpx", startReached = true)
        var primaryComputations = 0
        var nearestComputations = 0
        var perFixProcessing = 0

        fun processFix() {
            val fixLocation = LatLong(45.0, 6.0002)
            cache.primaryState(
                session = session,
                currentLocation = fixLocation,
                tuning = GpxGuidanceTuning(),
                previousDistanceFromStartMeters = null,
            ) {
                primaryComputations += 1
                computeTurnByTurnGuidanceState(session, fixLocation)
            }
            cache.nearestRoutePoint(session, fixLocation) {
                nearestComputations += 1
                nearestGuidanceRoutePoint(session, fixLocation)
            }
            perFixProcessing += 1
        }

        processFix()
        processFix()

        assertEquals(1, primaryComputations)
        assertEquals(1, nearestComputations)
        assertEquals(2, perFixProcessing)
    }

    @Test
    fun `primary cache invalidates route crossing continuity input`() {
        val cache = NavigateGuidanceGeometryCache()
        val crossingPoints =
            listOf(
                LatLong(45.0000, 6.0000),
                LatLong(45.0010, 6.0010),
                LatLong(45.0000, 6.0020),
                LatLong(44.9990, 6.0010),
                LatLong(45.0000, 6.0000),
                LatLong(45.0010, 5.9990),
            )
        val crossingSession = session("crossing.gpx", crossingPoints, startReached = true)
        val cumulative = buildCumulativeDistances(crossingPoints)
        val crossingLocation = crossingPoints.first()
        var computations = 0

        cachedState(cache, PrimaryRequest(crossingSession, crossingLocation)) { computations += 1 }
        val crossingState =
            cachedState(
                cache,
                PrimaryRequest(
                    session = crossingSession,
                    location = crossingLocation,
                    previousDistanceFromStartMeters = cumulative[3],
                ),
            ) { computations += 1 }

        assertTrue((crossingState.distanceFromStartMeters ?: 0.0) > cumulative[2])
        assertEquals(2, computations)
    }

    @Test
    fun `primary cache preserves relocking computation`() {
        val cache = NavigateGuidanceGeometryCache()
        val relockSession =
            session(
                trackId = "relock.gpx",
                points =
                    listOf(
                        LatLong(45.0000, 6.0000),
                        LatLong(45.0000, 6.0010),
                        LatLong(45.0100, 6.0100),
                        LatLong(45.0100, 6.0110),
                    ),
                startReached = true,
            )
        var computations = 0
        val relockLocation = LatLong(45.0100, 6.0105)
        val relockedState =
            cachedState(
                cache,
                PrimaryRequest(
                    session = relockSession,
                    location = relockLocation,
                    previousDistanceFromStartMeters = 20.0,
                ),
            ) { computations += 1 }

        assertTrue((relockedState.distanceFromStartMeters ?: 0.0) > relockSession.cumulativeDistancesMeters[1])
        assertEquals(1, computations)
    }

    @Test
    fun `primary cache invalidates route replacement reversal start state and tuning`() {
        val cache = NavigateGuidanceGeometryCache()
        val baseSession = session("route.gpx", startReached = true)
        val replacementSession =
            session(
                "route.gpx",
                points =
                    listOf(
                        LatLong(45.0, 6.0),
                        LatLong(45.0, 6.0015),
                        LatLong(45.001, 6.0015),
                    ),
                startReached = true,
            )
        val reversedSession =
            session(
                "route.gpx",
                baseSession.trackPoints.map { it.latLong },
                startReached = true,
                reversed = true,
            )
        val startedSession = baseSession.copy(startReached = false)
        val location = LatLong(45.0, 6.0002)
        var computations = 0

        cachedState(cache, PrimaryRequest(baseSession, location)) { computations += 1 }
        cachedState(cache, PrimaryRequest(replacementSession, location)) { computations += 1 }
        cachedState(cache, PrimaryRequest(reversedSession, location)) { computations += 1 }
        cachedState(cache, PrimaryRequest(startedSession, location)) { computations += 1 }
        cachedState(
            cache,
            PrimaryRequest(
                session = baseSession,
                location = location,
                tuning = GpxGuidanceTuning(offRouteDistanceMeters = 80.0),
            ),
        ) { computations += 1 }

        assertEquals(5, computations)
    }

    @Test
    fun `nearest cache is separate and invalidates on a new location`() {
        val cache = NavigateGuidanceGeometryCache()
        val session = session("route.gpx", startReached = true)
        val firstLocation = LatLong(45.0, 6.0002)
        val secondLocation = LatLong(45.0, 6.0008)
        var primaryComputations = 0
        var nearestComputations = 0

        val primary =
            cache.primaryState(
                session = session,
                currentLocation = firstLocation,
                tuning = GpxGuidanceTuning(),
                previousDistanceFromStartMeters = null,
            ) {
                primaryComputations += 1
                computeTurnByTurnGuidanceState(session, firstLocation)
            }
        val nearest =
            cache.nearestRoutePoint(session, firstLocation) {
                nearestComputations += 1
                nearestGuidanceRoutePoint(session, firstLocation)
            }
        val cachedPrimary =
            cache.primaryState(session, firstLocation, GpxGuidanceTuning(), null) {
                error("cache miss")
            }
        val cachedNearest = cache.nearestRoutePoint(session, firstLocation) { error("cache miss") }
        assertSame(primary, cachedPrimary)
        assertEquals(nearest, cachedNearest)

        cache.primaryState(session, secondLocation, GpxGuidanceTuning(), null) {
            primaryComputations += 1
            computeTurnByTurnGuidanceState(session, secondLocation)
        }
        cache.nearestRoutePoint(session, secondLocation) {
            nearestComputations += 1
            nearestGuidanceRoutePoint(session, secondLocation)
        }

        assertEquals(2, primaryComputations)
        assertEquals(2, nearestComputations)
    }

    private fun session(
        trackId: String,
        points: List<LatLong> =
            listOf(
                LatLong(45.0, 6.0),
                LatLong(45.0, 6.001),
                LatLong(45.001, 6.001),
            ),
        startReached: Boolean,
        reversed: Boolean = false,
    ): GpxGuidanceSession =
        buildGpxGuidanceSession(
            trackId = trackId,
            trackTitle = trackId,
            trackPoints = points.map { TrackPoint(latLong = it, elevation = null) },
            startReached = startReached,
            reversed = reversed,
        )

    private data class PrimaryRequest(
        val session: GpxGuidanceSession,
        val location: LatLong,
        val tuning: GpxGuidanceTuning = GpxGuidanceTuning(),
        val previousDistanceFromStartMeters: Double? = null,
    )

    private fun cachedState(
        cache: NavigateGuidanceGeometryCache,
        request: PrimaryRequest,
        onCompute: () -> Unit,
    ): TurnByTurnGuidanceState =
        cache.primaryState(
            session = request.session,
            currentLocation = request.location,
            tuning = request.tuning,
            previousDistanceFromStartMeters = request.previousDistanceFromStartMeters,
        ) {
            onCompute()
            computeTurnByTurnGuidanceState(
                session = request.session,
                currentLocation = request.location,
                tuning = request.tuning,
                previousDistanceFromStartMeters = request.previousDistanceFromStartMeters,
            )
        }
}
