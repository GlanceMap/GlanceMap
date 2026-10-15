package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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

    @Test fun sourceSwitchRestoresThePreferredMapAndKeepsTrackAndPoiSelections() {
        val first = PhoneOfflineMap(File("first.map"))
        val preferred = PhoneOfflineMap(File("preferred.map"))
        val initial = libraryTestMapState(composeRule.activity)
        val state =
            mutableStateOf(
                initial.copy(
                    maps =
                        initial.maps.copy(
                            offlineMaps = listOf(first, preferred),
                            preferredOfflineMapName = preferred.displayName,
                        ),
                ),
            )
        composeRule.setContent {
            MaterialTheme {
                val actions = libraryTestMapActions(state)
                libraryOnlineMapSelection(
                    state.value.maps,
                    actions.maps.copy(onSwitchToOnline = {
                        state.value = state.value.copy(maps = state.value.maps.copy(source = PhoneMapSource.Online))
                    }),
                )
            }
        }
        val toggle = composeRule.onNodeWithTag("library-map-source-toggle")
        toggle.assertIsOff().performClick().assertIsOn()
        composeRule.runOnIdle {
            assertEquals(PhoneMapSource.Offline(preferred), state.value.maps.source)
            assertEquals(initial.gpx, state.value.gpx)
            assertEquals(initial.poi, state.value.poi)
        }
        toggle.performClick().assertIsOff()
        composeRule.runOnIdle { assertEquals(PhoneMapSource.Online, state.value.maps.source) }
    }

    @Test fun sourceSwitchHandlesLoadingAndNoMapsAndFallsBackAfterAMapWasRemoved() {
        val initial = libraryTestMapState(composeRule.activity)
        val state = mutableStateOf(initial)
        composeRule.setContent {
            MaterialTheme { libraryOnlineMapSelection(state.value.maps, libraryTestMapActions(state).maps) }
        }
        val toggle = composeRule.onNodeWithTag("library-map-source-toggle")
        toggle.assertIsNotEnabled()
        val map = PhoneOfflineMap(File("remaining.map"))
        composeRule.runOnIdle {
            state.value =
                initial.copy(
                    maps =
                        initial.maps.copy(
                            offlineMaps = listOf(map),
                            preferredOfflineMapName = "removed.map",
                            isLoading = true,
                        ),
                )
        }
        toggle.assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(maps = state.value.maps.copy(isLoading = false)) }
        toggle.performClick().assertIsOn()
        composeRule.runOnIdle { assertEquals(PhoneMapSource.Offline(map), state.value.maps.source) }
    }

    @Test fun offlineMapRowsShowBothCoverageStatesWithoutTruncatingTheirLabels() {
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
        composeRule.onNodeWithText(elevation).assertIsDisplayed()
        composeRule.onNodeWithText(routing).assertIsDisplayed()
    }
}
