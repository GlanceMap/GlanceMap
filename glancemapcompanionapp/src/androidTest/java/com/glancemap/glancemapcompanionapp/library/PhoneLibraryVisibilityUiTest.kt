package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.MapToolContentMode
import com.glancemap.glancemapcompanionapp.map.PhoneMapSource
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import com.glancemap.glancemapcompanionapp.map.titleResource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneLibraryVisibilityUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun gpxAndPoiSwitchesLeaveDetailsClosedAndTargetTheExistingSelections() {
        val state = mutableStateOf(libraryTestMapState(composeRule.activity))
        var details = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    val actions = libraryTestMapActions(state)
                    libraryItemRow(
                        PhoneLibraryRowState(PhoneLibraryItem.Gpx(libraryTestRoute()), true),
                        state.value,
                        actions,
                    ) { details++ }
                    libraryItemRow(
                        PhoneLibraryRowState(
                            PhoneLibraryItem.Pois(
                                state.value.poi.sources
                                    .single(),
                            ),
                            true,
                        ),
                        state.value,
                        actions,
                    ) { details++ }
                }
            }
        }
        listOf("gpx/route", "poi/spring.poi").forEach { key ->
            composeRule
                .onNodeWithTag("library-visibility-$key")
                .assertIsOff()
                .performClick()
                .assertIsOn()
            composeRule.onNodeWithTag("library-visibility-$key").performClick().assertIsOff()
        }
        assertEquals(0, details)
        composeRule.onNodeWithText("Forest loop").performClick()
        assertEquals(1, details)
    }

    @Test
    fun offlineRadioSelectsOneBaseMapAndLeavesGpxAndPoiSelectionsAlone() {
        val state = mutableStateOf(libraryTestMapState(composeRule.activity))
        val maps = listOf(PhoneOfflineMap(File("alps.map")), PhoneOfflineMap(File("forest.map")))
        val before = state.value
        var details = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    maps.forEach { map ->
                        libraryItemRow(
                            PhoneLibraryRowState(PhoneLibraryItem.OfflineMap(map, 100L), true),
                            state.value,
                            libraryTestMapActions(state),
                        ) { details++ }
                    }
                }
            }
        }
        maps.forEach { map ->
            composeRule.onNodeWithTag("library-map-offline/${map.selectionIdentity}").performClick().assertIsSelected()
            composeRule.runOnIdle {
                assertEquals(PhoneMapSource.Offline(map), state.value.maps.source)
                assertEquals(before.gpx, state.value.gpx)
                assertEquals(before.poi, state.value.poi)
            }
        }
        composeRule.onNodeWithTag("library-map-offline/${maps.first().selectionIdentity}").assertIsNotSelected()
        assertEquals(0, details)
    }

    @Test
    fun unavailableTrackIsDisabledWhilePoiRemainsUsableDuringTrackLoading() {
        val initial = libraryTestMapState(composeRule.activity)
        val state = mutableStateOf(initial.copy(gpx = initial.gpx.copy(items = emptyList(), isLoading = true)))
        composeRule.setContent {
            MaterialTheme {
                Column {
                    val actions = libraryTestMapActions(state)
                    libraryItemRow(
                        PhoneLibraryRowState(PhoneLibraryItem.Gpx(libraryTestRoute()), true),
                        state.value,
                        actions,
                    ) {}
                    libraryItemRow(
                        PhoneLibraryRowState(
                            PhoneLibraryItem.Pois(
                                state.value.poi.sources
                                    .single(),
                            ),
                            true,
                        ),
                        state.value,
                        actions,
                    ) {}
                }
            }
        }
        composeRule.onNodeWithTag("library-visibility-gpx/route").assertIsNotEnabled()
        composeRule
            .onNodeWithTag("library-visibility-poi/spring.poi")
            .assertIsEnabled()
            .performClick()
            .assertIsOn()
    }

    @Test
    fun categoryAndSearchSurviveReturningFromTheirSettings() {
        val mapState = mutableStateOf(libraryTestMapState(composeRule.activity))
        val filter = mutableStateOf(PhoneLibraryFilter.POIS)
        val settings = mutableStateOf(false)
        composeRule.setContent {
            val saved = rememberSaveableStateHolder()
            MaterialTheme {
                if (settings.value) {
                    Button(onClick = { settings.value = false }) { Text("Back from settings") }
                } else {
                    saved.SaveableStateProvider("library") {
                        Column {
                            phoneLibraryHeader(filter.value) { filter.value = it }
                            libraryPanelContent(
                                PhoneLibraryListState(
                                    items =
                                        listOf(
                                            PhoneLibraryItem.Pois(
                                                mapState.value.poi.sources
                                                    .single(),
                                            ),
                                        ),
                                    filter = filter.value,
                                ),
                                mapState.value,
                                libraryTestMapActions(mapState),
                                libraryTestActions({ settings.value = true }, { filter.value = it }),
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.library_search)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("spring")
        val description = composeRule.activity.getString(MapTool.POI.titleResource(MapToolContentMode.FEATURE_SETTINGS))
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.onNodeWithText("Back from settings").performClick()
        composeRule.onNode(hasSetTextAction()).assertTextContains("spring")
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.library_filter_pois)).assertIsSelected()
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("spring.poi"))
        composeRule.onNodeWithText("spring.poi").assertIsDisplayed()
    }
}
