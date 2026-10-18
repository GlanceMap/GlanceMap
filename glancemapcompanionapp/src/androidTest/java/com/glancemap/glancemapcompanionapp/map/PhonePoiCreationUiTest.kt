package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePoiCreationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(PhonePoiCreationState(null, "", false, false))
    private val visible = mutableStateOf(true)
    private var mapRequests = 0
    private var saves = 0

    @Test
    fun initialChoicesOfferMapOrCoordinatesWithoutSavingAnything() {
        showSheet()
        compose.onNodeWithText(label(R.string.poi_creation_type_coordinates)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.poi_creation_pick_map)).performClick()
        compose.runOnIdle {
            assertEquals(1, mapRequests)
            assertEquals(0, saves)
            assertNull(state.value.point)
        }
        compose.onNodeWithTag("poi-creation-sheet").assertDoesNotExist()
    }

    @Test
    fun coordinatesAreValidatedThenReviewedBeforeSaveAndFailuresKeepTheDraft() {
        showSheet()
        compose.onNodeWithText(label(R.string.poi_creation_type_coordinates)).performClick()
        compose.onNodeWithTag("poi-latitude").performTextReplacement("91")
        compose.onNodeWithTag("poi-use-coordinates").assertIsNotEnabled()
        compose.onNodeWithTag("poi-latitude").performTextReplacement("NaN")
        compose.onNodeWithTag("poi-use-coordinates").assertIsNotEnabled()
        compose.onNodeWithTag("poi-latitude").performTextReplacement("-33,9")
        compose.onNodeWithTag("poi-longitude").performTextReplacement("18,4")
        compose.onNodeWithTag("poi-use-coordinates").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(PhoneMapCoordinate(-33.9, 18.4), state.value.point)
            assertEquals(0, saves)
        }
        compose.onNodeWithTag("poi-name").performTextReplacement("Camp")
        compose
            .onNodeWithTag("poi-save")
            .performScrollTo()
            .performClick()
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.common_action_close)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, saves)
            state.value = state.value.copy(saving = false, saveFailed = true)
        }
        compose.onNodeWithText(label(R.string.map_poi_save_failed)).assertExists()
        compose.onNodeWithTag("poi-name").assertTextContains("Camp")
        compose.onNodeWithTag("poi-save").assertIsEnabled()
    }

    @Test
    fun changingTheLocationKeepsTheNameAndAcceptsTheSameCoordinatesAgain() {
        state.value = state.value.copy(point = PhoneMapCoordinate(45.0, 6.0), name = "Water stop")
        showSheet()
        compose.onNodeWithText(label(R.string.map_poi_change_location)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.poi_creation_type_coordinates)).performClick()
        compose.onNodeWithTag("poi-use-coordinates").performScrollTo().performClick()
        compose.onNodeWithTag("poi-name").assertTextContains("Water stop")
        compose.onNodeWithText(label(R.string.map_poi_change_location)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.poi_creation_pick_map)).performClick()
        compose.runOnIdle {
            assertEquals(1, mapRequests)
            assertEquals("Water stop", state.value.name)
            assertEquals(0, saves)
        }
    }

    private fun showSheet() {
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    phonePoiCreationSheet(
                        state = state.value,
                        coordinateSeed = PhoneMapCoordinate(45.0, 6.0),
                        actions =
                            PhonePoiCreationActions(
                                onPickMap = {
                                    mapRequests++
                                    visible.value = false
                                },
                                onCoordinatesSelected = { state.value = state.value.copy(point = it) },
                                onNameChanged = { state.value = state.value.copy(name = it) },
                                onSave = {
                                    saves++
                                    state.value = state.value.copy(saving = true)
                                },
                                onDismiss = { visible.value = false },
                            ),
                    )
                }
            }
        }
    }

    private fun label(resource: Int) = compose.activity.getString(resource)
}
