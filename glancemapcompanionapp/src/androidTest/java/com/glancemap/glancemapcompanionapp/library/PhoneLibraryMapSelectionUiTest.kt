package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneMapSource
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneLibraryMapSelectionUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun tappingARowActivatesThatMapAndKeepsTrackAndPoiSelections() {
        val map = PhoneOfflineMap(File("Alps.map"))
        val initial = libraryTestMapState(composeRule.activity)
        val state = mutableStateOf(initial.copy(maps = initial.maps.copy(offlineMaps = listOf(map))))
        var details = 0
        composeRule.setContent {
            MaterialTheme {
                val actions = libraryTestMapActions(state)
                LazyColumn {
                    libraryMapSourceEntries(state.value.maps, actions.maps, "")
                    item {
                        libraryItemRow(
                            PhoneLibraryRowState(PhoneLibraryItem.OfflineMap(map, 100L), true),
                            state.value,
                            actions,
                        ) { details++ }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("library-item-offline/${map.selectionIdentity}").performClick()
        composeRule.runOnIdle {
            assertEquals(PhoneMapSource.Offline(map), state.value.maps.source)
            assertEquals(initial.gpx, state.value.gpx)
            assertEquals(initial.poi, state.value.poi)
        }
        composeRule.onNodeWithTag("library-online-OPEN_STREET_MAP").performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals(PhoneMapSource.Online, state.value.maps.source) }
        assertEquals(0, details)
    }

    @Test fun activeMapCardNamesTheMapAndWhetherItIsOffline() {
        val map = PhoneOfflineMap(File("Alps.map"))
        val initial = libraryTestMapState(composeRule.activity)
        val state = mutableStateOf(initial)
        composeRule.setContent {
            MaterialTheme {
                LazyColumn { libraryMapSourceEntries(state.value.maps, libraryTestMapActions(state).maps, "") }
            }
        }
        val card = composeRule.onNodeWithTag("library-active-map", useUnmergedTree = true)
        card.assertExists()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.map_source_no_offline_maps))
            .assertExists()
        composeRule.runOnIdle {
            state.value =
                initial.copy(
                    maps = initial.maps.copy(source = PhoneMapSource.Offline(map), offlineMaps = listOf(map)),
                )
        }
        composeRule.onNodeWithText("Alps.map").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.library_map_mode_offline)).assertIsDisplayed()
    }

    @Test fun loadingBlocksMapActivationFromRows() {
        val map = PhoneOfflineMap(File("Alps.map"))
        val initial = libraryTestMapState(composeRule.activity)
        val state = mutableStateOf(initial.copy(maps = initial.maps.copy(offlineMaps = listOf(map), isLoading = true)))
        composeRule.setContent {
            MaterialTheme {
                val actions = libraryTestMapActions(state)
                LazyColumn {
                    libraryMapSourceEntries(state.value.maps, actions.maps, "")
                    item {
                        libraryItemRow(
                            PhoneLibraryRowState(PhoneLibraryItem.OfflineMap(map, 100L), true),
                            state.value,
                            actions,
                        ) {}
                    }
                }
            }
        }
        composeRule.onNodeWithTag("library-item-offline/${map.selectionIdentity}").performClick()
        composeRule.onNodeWithTag("library-online-OPEN_STREET_MAP").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(PhoneMapSource.Online, state.value.maps.source) }
    }

    @Test fun offlineMapRowsDescribeBothCoverageStatesOnTheirChips() {
        val state = mutableStateOf(libraryTestMapState(composeRule.activity))
        val map = PhoneOfflineMap(File("Alps.map"))
        val coverage = PhoneLibraryMapCoverage(null, 4, 2, 1, 1)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    libraryItemRow(
                        PhoneLibraryRowState(PhoneLibraryItem.OfflineMap(map, 100L, coverage), true),
                        state.value,
                        libraryTestMapActions(state),
                    ) {}
                }
            }
        }
        val elevation =
            composeRule.activity.getString(
                R.string.library_offline_component,
                composeRule.activity.getString(R.string.library_offline_elevation),
                composeRule.activity.getString(R.string.library_map_coverage_partial, 2, 4),
            )
        val routing =
            composeRule.activity.getString(
                R.string.library_offline_component,
                composeRule.activity.getString(R.string.library_offline_routing),
                composeRule.activity.getString(R.string.library_map_coverage_available, 1, 1),
            )
        composeRule.onNodeWithContentDescription(elevation).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(routing).assertIsDisplayed()
    }
}
