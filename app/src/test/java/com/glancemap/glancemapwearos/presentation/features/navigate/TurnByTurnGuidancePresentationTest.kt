package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainConfirmation
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainDirection
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainPreview
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstruction
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class TurnByTurnGuidancePresentationTest {
    @Test
    fun longStraightShowsCurrentActionAndPreparesTurn() {
        val text = guidanceCompactInstructionText(state(distanceMeters = 180.0), isMetric = true)

        assertEquals("180 m", text)
        assertTrue(guidanceShowsCurrentStraight(state(distanceMeters = 180.0)))
    }

    @Test
    fun maneuverZoneShowsTurnAndDistance() {
        val text = guidanceCompactInstructionText(state(distanceMeters = 35.0), isMetric = true)

        assertEquals("35 m", text)
    }

    @Test
    fun retainedTurnShowsNowUntilConfirmed() {
        val text = guidanceCompactInstructionText(state(distanceMeters = 0.0), isMetric = true)

        assertEquals("Now", text)
    }

    @Test
    fun compactGuidanceAddsTerrainBadgeForTheRoadAfterTheTurn() {
        val text =
            guidanceCompactInstructionText(
                state(
                    distanceMeters = 180.0,
                    terrain =
                        GuidanceTerrainPreview(
                            direction = GuidanceTerrainDirection.UPHILL,
                            elevationChangeMeters = 20.0,
                            distanceMeters = 100.0,
                        ),
                ),
                isMetric = true,
            )

        assertEquals("180m +▲", text)
    }

    @Test
    fun expandedGuidanceLinksTerrainToTheUpcomingTurn() {
        val presentation =
            guidanceTerrainPopupPresentation(
                state(
                    distanceMeters = 180.0,
                    terrain =
                        GuidanceTerrainPreview(
                            direction = GuidanceTerrainDirection.FLAT,
                            elevationChangeMeters = 1.0,
                            distanceMeters = 100.0,
                        ),
                ),
                isMetric = true,
            )

        assertEquals("— Flat", presentation?.expandedText)
    }

    @Test
    fun expandedGuidanceConfirmsTheTerrainOfTheTakenBranch() {
        val presentation =
            guidanceTerrainPopupPresentation(
                state(
                    distanceMeters = 180.0,
                    recentManeuverTerrain =
                        GuidanceTerrainConfirmation(
                            maneuver = RouteInstructionCommand.LEFT,
                            terrain =
                                GuidanceTerrainPreview(
                                    direction = GuidanceTerrainDirection.UPHILL,
                                    elevationChangeMeters = 20.0,
                                    distanceMeters = 100.0,
                                ),
                        ),
                ),
                isMetric = true,
            )

        assertEquals("▲ Uphill +20 m", presentation?.expandedText)
    }

    @Test
    fun pausedGuidanceKeepsTheLatestActiveDashboardState() {
        val latestActiveState = state(distanceMeters = 180.0)
        val pausedCurrentState =
            latestActiveState.copy(
                active = false,
                mode = GuidanceMode.WAITING_FOR_LOCATION,
                nextInstruction = null,
                distanceToInstructionMeters = null,
                distanceRemainingMeters = null,
                routeProgressFraction = null,
            )

        val displayState =
            pausedGuidanceDisplayState(
                currentState = pausedCurrentState,
                latestActiveState = latestActiveState,
                paused = true,
            )

        assertEquals(latestActiveState, displayState)
    }

    @Test
    fun voiceContinueStraightPromptOnlySpeaksForMeaningfulStraightSections() {
        assertTrue(shouldSpeakContinueStraightPrompt(state(distanceMeters = 600.0)))
        assertFalse(shouldSpeakContinueStraightPrompt(state(distanceMeters = 300.0)))
        assertFalse(
            shouldSpeakContinueStraightPrompt(
                state(
                    distanceMeters = 600.0,
                    command = RouteInstructionCommand.CONTINUE,
                ),
            ),
        )
    }

    @Test
    fun voiceContinueStraightPromptFormatsMetricAndImperialDistances() {
        assertEquals(
            "Continue straight for 750 meters",
            spokenContinueStraightText(distanceMeters = 752.0, isMetric = true),
        )
        assertEquals(
            "Continue straight for 0.5 miles",
            spokenContinueStraightText(distanceMeters = 805.0, isMetric = false),
        )
    }

    private fun state(
        distanceMeters: Double,
        command: RouteInstructionCommand = RouteInstructionCommand.RIGHT,
        terrain: GuidanceTerrainPreview? = null,
        recentManeuverTerrain: GuidanceTerrainConfirmation? = null,
    ): TurnByTurnGuidanceState =
        TurnByTurnGuidanceState(
            active = true,
            mode = GuidanceMode.FOLLOW_ROUTE,
            trackTitle = "Route",
            nextInstruction =
                RouteInstruction(
                    command = command,
                    message = command.message,
                    latLong = LatLong(45.0, 6.0),
                    trackPointIndex = 1,
                    distanceFromStartMeters = 200.0,
                    turnAngleDegrees = 90f,
                ),
            distanceToInstructionMeters = distanceMeters,
            distanceToStartMeters = 0.0,
            bearingToStartDegrees = null,
            distanceToRouteMeters = 0.0,
            bearingToRouteDegrees = null,
            distanceRemainingMeters = 500.0,
            routeProgressFraction = 0.25f,
            offRoute = false,
            nextSegmentTerrain = terrain,
            recentManeuverTerrain = recentManeuverTerrain,
        )
}
