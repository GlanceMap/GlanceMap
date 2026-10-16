package com.glancemap.glancemapcompanionapp.routes

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.ui.theme.GlanceMapTheme
import com.glancemap.trailcore.profile.TrailWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MissionAdventureScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun createsAnAdventureBeforeAnyGpxExists() {
        var saved: MissionAdventureUpdate? = null
        setContent(mutableStateOf(MissionPlanUiState(isLoading = false)), onSave = { saved = it })
        composeRule.onAllNodesWithText(text(R.string.adventure_new))[0].performClick()
        composeRule.onNodeWithText(text(R.string.adventure_name)).performTextReplacement("Mountain escape")
        composeRule.onNodeWithText(text(R.string.adventure_people)).performTextReplacement("2")
        composeRule.onNodeWithText(text(R.string.common_action_save)).performClick()
        composeRule.runOnIdle {
            assertNotNull(saved)
            assertEquals("Mountain escape", saved?.name)
            assertEquals(2, saved?.people)
            assertEquals(LocalDate.now().toString(), saved?.startDate)
        }
    }

    @Test
    fun draftDayCanBecomeADayOffWithoutLosingMealsOrNotes() {
        val day = MissionPlanDay("day", 1, breakfast = MissionMeal.PACK, notes = "Keep my notes")
        val state =
            mutableStateOf(
                MissionPlanUiState(
                    isLoading = false,
                    adventures =
                        listOf(
                            MissionAdventure(
                                "trip",
                                "Trip",
                                people = 2,
                            ),
                        ),
                    selectedAdventureId = "trip",
                    planDays = listOf(day),
                ),
            )
        setContent(state, onSave = {}, onChange = { id, edit ->
            state.value =
                state.value.copy(
                    planDays = state.value.planDays.map { if (it.id == id) it.withUpdate(edit(it)) else it },
                )
        })
        composeRule.onNodeWithText(text(R.string.adventure_choose_route)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.adventure_route_to_choose)).performClick()
        composeRule.onNodeWithText(text(R.string.adventure_day_off)).performClick()
        composeRule.onNodeWithText(text(R.string.adventure_choose_route)).assertDoesNotExist()
        composeRule.onNodeWithText("Keep my notes").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                MissionMeal.PACK,
                state.value.planDays
                    .single()
                    .breakfast,
            )
            assertEquals(
                true,
                state.value.planDays
                    .single()
                    .isRestDay,
            )
        }
    }

    @Test
    fun compactOverviewShowsAllFourDaysIncludingADayOff() {
        val dates = (0..3).map { LocalDate.now().plusDays(it.toLong()).toString() }
        val plans = compactPlans(dates)
        val routes =
            listOf(
                fixture(plans[0], "Valley → Lake", 14_200.0, 900.0, 200.0),
                fixture(plans[1], "Lake → Ridge refuge", 11_600.0, 700.0, 450.0),
                fixture(plans[3], "Ridge refuge → Valley", 13_100.0, 180.0, 1_130.0),
            )
        val adventure = MissionAdventure("trip", "Mountain escape", dates.first(), dates.last(), people = 2)
        setContent(
            mutableStateOf(
                MissionPlanUiState(
                    isLoading = false,
                    adventures = listOf(adventure),
                    selectedAdventureId = adventure.id,
                    planDays = plans,
                    days = routes,
                ),
            ),
            onSave = {},
        )
        composeRule.onNodeWithText("Valley → Lake").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.adventure_day_off)).assertIsDisplayed()
        composeRule.onNodeWithText("Ridge refuge → Valley").assertIsDisplayed()
        capturePreview()
    }

    private fun compactPlans(dates: List<String>): List<MissionPlanDay> =
        listOf(
            MissionPlanDay(
                "first",
                1,
                "first-route",
                plannedDate = dates[0],
                overnightType = MissionOvernight.BIVOUAC,
                lunch = MissionMeal.PACK,
                dinner = MissionMeal.PACK,
            ),
            MissionPlanDay(
                "second",
                2,
                "second-route",
                plannedDate = dates[1],
                overnightType = MissionOvernight.REFUGE,
                breakfast = MissionMeal.PACK,
                lunch = MissionMeal.PACK,
            ),
            MissionPlanDay(
                "rest",
                3,
                plannedDate = dates[2],
                isRestDay = true,
                overnightType = MissionOvernight.REFUGE,
                notes = "Rest and resupply",
            ),
            MissionPlanDay(
                "last",
                4,
                "last-route",
                plannedDate = dates[3],
                overnightType = MissionOvernight.NONE,
                lunch = MissionMeal.PACK,
            ),
        )

    private fun capturePreview() {
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "mission-plan-preview.png",
        ).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun fixture(
        day: MissionPlanDay,
        name: String,
        distance: Double,
        up: Double,
        down: Double,
    ): MissionPlanDayUi {
        val summary = RouteLibrarySummary(distance, up, down, 14_400.0, 0, 0.0, 0.0)
        val route = RouteLibraryRoute(requireNotNull(day.routeId), name, "example.gpx", 0L, summary)
        val window = TrailWindow(0.0, distance, distance, up, down, 14_400.0)
        return MissionPlanDayUi(
            day,
            route,
            window,
            MissionDayTimeline(window, emptyList()),
            MissionDayPlanProfile(distance, 14_400.0, emptyList()),
        )
    }

    private fun setContent(
        state: State<MissionPlanUiState>,
        onSave: (MissionAdventureUpdate) -> Unit,
        onChange: (String, (MissionPlanDay) -> MissionPlanDayUpdate) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            GlanceMapTheme {
                MissionPlanScreen(
                    uiState = state.value,
                    routes = emptyList(),
                    onBack = {},
                    onSaveAdventure = { _, update -> onSave(update) },
                    onSelectAdventure = {},
                    onAddBlankDay = {},
                    onAssignRoute = { _, _ -> },
                    onImportRoute = { _, _ -> },
                    onCreateRoute = {},
                    onSetToday = {},
                    onUpdateDay = { _, _ -> },
                    onChangeDay = onChange,
                    onMoveDay = { _, _ -> },
                    onRemoveDay = {},
                    onLoadDayWeather = { _, _ -> },
                    onRetry = {},
                    onLoadStageSource = { _, _ -> error("Unused in this screen fixture") },
                    onImportStageRoute = { error("Unused in this screen fixture") },
                    onApplyStages = { _, _, _, _ -> },
                )
            }
        }
    }

    private fun text(id: Int): String = composeRule.activity.getString(id)
}
