package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainConfirmation
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainDirection
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainPreview
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstruction
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.messageRes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TurnByTurnGuidancePresentationResourceTest {
    private lateinit var previousLocale: Locale

    @Before
    fun useEnglishFormattingLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreFormattingLocale() {
        Locale.setDefault(previousLocale)
    }

    private val resources =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun longStraightShowsCurrentActionAndPreparesTurn() {
        val text = guidanceCompactInstructionText(state(distanceMeters = 180.0), isMetric = true, resources = resources)

        assertEquals("180 m", text)
        assertTrue(guidanceShowsCurrentStraight(state(distanceMeters = 180.0)))
    }

    @Test
    fun maneuverZoneShowsTurnAndDistance() {
        val text = guidanceCompactInstructionText(state(distanceMeters = 35.0), isMetric = true, resources = resources)

        assertEquals("35 m", text)
    }

    @Test
    fun retainedTurnShowsNowUntilConfirmed() {
        val text = guidanceCompactInstructionText(state(distanceMeters = 0.0), isMetric = true, resources = resources)

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
                resources = resources,
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
                resources = resources,
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
                resources = resources,
            )

        assertEquals("▲ Uphill +20 m", presentation?.expandedText)
    }

    @Test
    fun voiceContinueStraightPromptFormatsMetricAndImperialDistances() {
        assertEquals(
            "Continue straight for 750 meters",
            spokenContinueStraightText(distanceMeters = 752.0, isMetric = true, resources = resources),
        )
        assertEquals(
            "Continue straight for 0.5 miles",
            spokenContinueStraightText(distanceMeters = 805.0, isMetric = false, resources = resources),
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
                    messageRes = command.messageRes,
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
