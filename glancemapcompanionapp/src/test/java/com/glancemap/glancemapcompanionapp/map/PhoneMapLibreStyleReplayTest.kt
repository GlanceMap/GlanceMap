package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapLibreStyleReplayTest {
    @Test
    fun baseStyleReplayRequiresEveryMapLibreOwnedOverlayAndLocationState() {
        val expected = PhoneMapLibreStyleReplayTracker().begin(rendererIdentity(1L, 1L))

        assertEquals(
            setOf(
                PhoneMapLibreStyleReplayPart.LOCATION,
                PhoneMapLibreStyleReplayPart.HILLSHADE,
                PhoneMapLibreStyleReplayPart.GPX,
                PhoneMapLibreStyleReplayPart.POI,
                PhoneMapLibreStyleReplayPart.ROUTE_ANALYSIS,
                PhoneMapLibreStyleReplayPart.SELECTION,
                PhoneMapLibreStyleReplayPart.DISTANCE,
            ),
            expected,
        )
    }

    @Test
    fun comparisonStyleReplayAlsoRequiresItsBasemapOpacity() {
        val expected =
            PhoneMapLibreStyleReplayTracker()
                .begin(rendererIdentity(1L, 1L, PhoneMapLibreRendererRole.COMPARISON))

        assertTrue(expected.contains(PhoneMapLibreStyleReplayPart.BASEMAP_OPACITY))
        assertTrue(expected.containsAll(baseReplayParts))
    }

    @Test
    fun styleReplayCompletesOnlyAfterEveryDeclarativeComponentHasReapplied() {
        val tracker = PhoneMapLibreStyleReplayTracker()
        val identity = rendererIdentity(1L, 1L)
        val expected = tracker.begin(identity)
        val expectedInOrder = expected.toList()

        val early = expectedInOrder.dropLast(1).map { part -> tracker.partApplied(identity, part) }

        assertTrue(early.all { update -> update.accepted && !update.completed })
        val completed = tracker.partApplied(identity, expectedInOrder.last())
        val repeated = tracker.partApplied(identity, expectedInOrder.last())

        assertTrue(completed.completed)
        assertFalse(repeated.partWasNew)
        assertFalse(repeated.completed)
    }

    @Test
    fun oldStyleCallbackCannotSatisfyReplayForTheReplacementGeneration() {
        val tracker = PhoneMapLibreStyleReplayTracker()
        val openTopo = rendererIdentity(1L, 12L)
        val ign = rendererIdentity(2L, 13L).copy(providerId = "ign_orthophoto")
        tracker.begin(openTopo)
        tracker.begin(ign)

        val stale = tracker.partApplied(openTopo, PhoneMapLibreStyleReplayPart.GPX)
        val current = tracker.partApplied(ign, PhoneMapLibreStyleReplayPart.GPX)

        assertFalse(stale.accepted)
        assertTrue(current.accepted)
        assertTrue(current.partWasNew)
        assertFalse(current.completed)
    }

    @Test
    fun rapidOpenTopoIgnOpenTopoSwitchesCompleteOnlyTheFinalReplay() {
        val tracker = PhoneMapLibreStyleReplayTracker()
        val providers =
            listOf(
                rendererIdentity(1L, 12L),
                rendererIdentity(2L, 13L).copy(providerId = "ign_orthophoto"),
                rendererIdentity(3L, 14L),
            )
        providers.forEach(tracker::begin)

        assertFalse(tracker.partApplied(providers[0], PhoneMapLibreStyleReplayPart.POI).accepted)
        assertFalse(tracker.partApplied(providers[1], PhoneMapLibreStyleReplayPart.POI).accepted)
        val finalParts =
            tracker.begin(providers.last()).map { part -> tracker.partApplied(providers.last(), part) }

        assertTrue(finalParts.all { update -> update.accepted })
        assertTrue(finalParts.last().completed)
    }

    private companion object {
        val baseReplayParts =
            setOf(
                PhoneMapLibreStyleReplayPart.LOCATION,
                PhoneMapLibreStyleReplayPart.HILLSHADE,
                PhoneMapLibreStyleReplayPart.GPX,
                PhoneMapLibreStyleReplayPart.POI,
                PhoneMapLibreStyleReplayPart.ROUTE_ANALYSIS,
                PhoneMapLibreStyleReplayPart.SELECTION,
                PhoneMapLibreStyleReplayPart.DISTANCE,
            )

        fun rendererIdentity(
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
    }
}
