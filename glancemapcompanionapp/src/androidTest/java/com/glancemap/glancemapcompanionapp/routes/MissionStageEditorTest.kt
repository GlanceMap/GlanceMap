package com.glancemap.glancemapcompanionapp.routes

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.ui.theme.GlanceMapTheme
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class MissionStageEditorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun moveStopUndoAndApplyKeepAdjacentStagesAndSkipDaysOff() {
        var savedIds: List<String>? = null
        var savedStops: List<Double>? = null
        val source = source()
        val initial = source.route.profile.suggestStageStops(3)
        composeRule.setContent {
            GlanceMapTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MissionStageEditor(source, null, true, { ids, stops ->
                        savedIds = ids
                        savedStops = stops
                    })
                }
            }
        }
        capturePreview()
        val description = "Arrival at ${initial.first().toMissionPlanDistance()} from the GPX start"
        composeRule
            .onNodeWithContentDescription(description)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it((initial.first() * 1.25).toFloat()) }
        composeRule
            .onNodeWithText("Undo last move")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText("Apply linked stages").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("a", "b", "c"), savedIds)
            assertEquals(initial, savedStops)
        }
    }

    @Test
    fun failedApplyKeepsTheDraftOpenForRetry() {
        var attempts = 0
        var dismissed = false
        val source = source()
        composeRule.setContent {
            GlanceMapTheme {
                MissionStagePlanner(
                    adventures = listOf(MissionAdventure("trip", "Trip")),
                    routes = listOf(source.route.route),
                    initialAdventureId = "trip",
                    initialRouteId = "route",
                    focusDayId = null,
                    onLoad = { _, _ -> source },
                    onImport = { error("Unused") },
                    onApply = { _, _, _, _ ->
                        attempts++
                        error("Storage unavailable")
                    },
                    onDismiss = { dismissed = true },
                    onApplied = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithText("Apply linked stages").performScrollTo().performClick()
        composeRule
            .onNodeWithText(
                "Could not apply these stages. Your draft is still here; check the itinerary and try again.",
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Apply linked stages")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(2, attempts)
            assertTrue(!dismissed)
        }
    }

    @Test
    fun failedLoadShowsTheAdventureValidationReason() {
        val source = source()
        val reason = "This adventure no longer exists. Reopen Adventures and try again."
        composeRule.setContent {
            GlanceMapTheme {
                MissionStagePlanner(
                    adventures = listOf(MissionAdventure("trip", "Trip")),
                    routes = listOf(source.route.route),
                    initialAdventureId = "trip",
                    initialRouteId = "route",
                    focusDayId = null,
                    onLoad = { _, _ -> throw IllegalArgumentException(reason) },
                    onImport = { error("Unused") },
                    onApply = { _, _, _, _ -> },
                    onDismiss = {},
                    onApplied = {},
                )
            }
        }
        composeRule.onNodeWithText(reason).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performScrollTo().assertIsEnabled()
    }

    private fun capturePreview() {
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "linked-stages-preview.png")
            .outputStream()
            .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun source(): MissionRouteStageSource {
        val profile =
            buildTrailRouteProfile(
                (0..30).map { index ->
                    TrailPoint(
                        GeoPoint(46.0 + index * 0.008, 11.0 + sin(index / 5.0) * 0.04),
                        900.0 + index * 12 + sin(index / 3.0) * 120,
                    )
                },
            )
        val summary =
            RouteLibrarySummary(
                profile.totalDistanceMeters,
                profile.totalAscentMeters,
                profile.totalDescentMeters,
                profile.estimatedDurationSeconds,
                profile.points.size,
                0.0,
                0.0,
            )
        return MissionRouteStageSource(
            RouteLibraryRouteDetails(
                RouteLibraryRoute("route", "Ridge trail", "route.gpx", 0L, summary),
                profile,
                emptyList(),
            ),
            listOf(
                MissionPlanDay("a", 1),
                MissionPlanDay("off", 2, isRestDay = true),
                MissionPlanDay("b", 3),
                MissionPlanDay("c", 4),
            ),
        )
    }
}
