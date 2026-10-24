package com.glancemap.glancemapwearos.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class BRouterRoutePlannerHelpersTest {
    @Test
    fun andorraAreaMapsToSingleExpectedTile() {
        val tiles =
            requiredRoutingSegmentFileNames(
                origin = LatLong(42.50, 1.52),
                destination = LatLong(42.57, 1.73),
            )

        assertEquals(listOf("E0_N40.rd5"), tiles)
    }

    @Test
    fun routeCrossingFiveDegreeBoundaryIncludesNeighborTiles() {
        val tiles =
            requiredRoutingSegmentFileNames(
                origin = LatLong(42.50, 4.90),
                destination = LatLong(42.50, 5.10),
            )

        assertEquals(
            listOf("E0_N40.rd5", "E5_N40.rd5"),
            tiles,
        )
    }

    @Test
    fun roundTripNearBoundaryIncludesNeighborTiles() {
        val tiles =
            requiredRoundTripSegmentFileNames(
                start = LatLong(42.50, 4.99),
                searchRadiusMeters = 20_000,
            )

        assertEquals(
            listOf("E0_N40.rd5", "E5_N40.rd5"),
            tiles,
        )
    }

    @Test
    fun loopPointCandidatesIncludeBrouterDefaultCircuitCount() {
        val pointCounts = candidateLoopPointCounts(defaultPointCount = 6)

        assertEquals(listOf(6, 5, 4, 3), pointCounts)
    }

    @Test
    fun roundTripSearchRadiusIsSmallerThanRequestedLoopDistance() {
        val radius =
            estimateRoundTripSearchRadiusMeters(
                targetDistanceMeters = 10_000,
                pointCount = 5,
                allowSameWayBack = false,
            )

        assertTrue(radius in 2_400..2_700)
    }

    @Test
    fun sameWayBackLoopUsesAboutHalfTheRequestedDistanceAsRadius() {
        val radius =
            estimateRoundTripSearchRadiusMeters(
                targetDistanceMeters = 10_000,
                pointCount = 3,
                allowSameWayBack = true,
            )

        assertEquals(5_000, radius)
    }

    @Test
    fun shortLoopRequestUsesShortSearchRadius() {
        val radius =
            estimateRoundTripSearchRadiusMeters(
                targetDistanceMeters = 3_000,
                pointCount = 6,
                allowSameWayBack = false,
            )

        assertTrue(radius in 600..900)
    }

    @Test
    fun loopCoverageRadiusUsesSearchRadiusCandidatesNotRequestedDistance() {
        val radius =
            estimateLoopCoverageRadiusMeters(
                targetDistanceMeters = 10_000,
                defaultPointCount = 5,
                allowOutAndBack = false,
            )

        assertTrue(radius in 3_700..3_900)
    }

    @Test
    fun cityLoopRetryDirectionsPreferDiagonalStarts() {
        assertEquals(listOf(45, 135, 225, 315), cityLoopRetryDirections())
    }

    @Test
    fun cityLoopRetryPresetRelaxesNonUrbanFriendlyProfiles() {
        assertEquals(
            RoutePlannerPreset.PREFER_EASIEST,
            cityLoopRetryPreset(RoutePlannerPreset.BALANCED_HIKE),
        )
        assertEquals(
            RoutePlannerPreset.PREFER_EASIEST,
            cityLoopRetryPreset(RoutePlannerPreset.PREFER_TRAILS),
        )
        assertEquals(
            RoutePlannerPreset.PREFER_EASIEST,
            cityLoopRetryPreset(RoutePlannerPreset.PREFER_EASIEST),
        )
    }

    @Test
    fun loopVariantRotationChangesDirectionOrderOnRetry() {
        assertEquals(
            listOf(90, 180, 270, 0),
            rotateLoopVariants(listOf(0, 90, 180, 270), variationIndex = 1),
        )
    }

    @Test
    fun preferredLoopCandidateIndexCyclesAcrossCloseEnoughAlternatives() {
        assertEquals(0, preferredLoopCandidateIndex(candidateCount = 1, variationIndex = 4))
        assertEquals(1, preferredLoopCandidateIndex(candidateCount = 3, variationIndex = 1))
        assertEquals(2, preferredLoopCandidateIndex(candidateCount = 3, variationIndex = 2))
        assertEquals(0, preferredLoopCandidateIndex(candidateCount = 3, variationIndex = 3))
    }
}
