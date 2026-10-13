package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.MapToolContentMode
import com.glancemap.glancemapcompanionapp.map.titleResource
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibrarySummary
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLibraryNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun categorySettingsAndAddActionsKeepTheirSpecificDestinations() {
        val filter = mutableStateOf(PhoneLibraryFilter.GPX)
        val settings = mutableListOf<MapTool>()
        val additions = mutableListOf<MapLibraryAction>()
        composeRule.setContent {
            MaterialTheme {
                libraryCategoryControls(filter.value, false, actions({ settings += it }, { additions += it }))
            }
        }
        composeRule.onNodeWithText(label(R.string.library_add_gpx)).assertIsDisplayed()
        listOf(PhoneLibraryFilter.GPX, PhoneLibraryFilter.POIS, PhoneLibraryFilter.MAPS).forEach { category ->
            composeRule.runOnIdle { filter.value = category }
            val tool = requireNotNull(category.tool)
            composeRule
                .onNodeWithContentDescription(label(tool.titleResource(MapToolContentMode.FEATURE_SETTINGS)))
                .performClick()
        }
        assertEquals(listOf(MapTool.GPX, MapTool.POI, MapTool.MAPS), settings)
        composeRule.runOnIdle { filter.value = PhoneLibraryFilter.POIS }
        composeRule.onNodeWithText(label(R.string.map_tool_shortcut_add_poi)).performClick()
        composeRule.runOnIdle { filter.value = PhoneLibraryFilter.MAPS }
        composeRule.onNodeWithText(label(R.string.library_import_map)).performClick()
        composeRule.runOnIdle { filter.value = PhoneLibraryFilter.BUNDLES }
        composeRule.onNodeWithText(label(R.string.map_bundle_action_download)).performClick()
        assertEquals(
            listOf(MapLibraryAction.ADD_POI, MapLibraryAction.IMPORT_MAP, MapLibraryAction.DOWNLOAD_BUNDLE),
            additions,
        )
    }

    @Test
    fun pickerSearchSelectsTheLibraryGpxByIdentity() {
        var selected: String? = null
        composeRule.setContent {
            MaterialTheme {
                phoneLibraryGpxPicker(
                    RouteLibraryUiState(
                        routes = listOf(route("one", "Forest loop"), route("two", "Alpine ridge")),
                        isLoading = false,
                    ),
                    onPick = { selected = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(label(R.string.library_search)).performTextInput("RIDGE")
        composeRule.onNodeWithText("Forest loop").assertDoesNotExist()
        composeRule.onNodeWithText("Alpine ridge").performClick()
        assertEquals("two", selected)
    }

    @Test
    fun todayHikeOffersImportAndSelectionAndBlocksDuplicateImports() {
        val state = mutableStateOf(RouteLibraryUiState(isLoading = false))
        var choices = 0
        composeRule.setContent {
            MaterialTheme { todayHikeGpxActions(state.value, {}, { choices++ }) }
        }
        composeRule.onNodeWithText(label(R.string.library_add_gpx)).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText(label(R.string.library_choose_gpx)).performClick()
        assertEquals(1, choices)
        composeRule.runOnIdle {
            state.value =
                RouteLibraryUiState(
                    routes = listOf(route("one", "Forest loop")),
                    selectedRouteId = "one",
                    isLoading = false,
                )
        }
        composeRule.onNodeWithText(label(R.string.library_change_gpx)).performClick()
        assertEquals(2, choices)
        composeRule.runOnIdle { state.value = state.value.copy(isImporting = true) }
        composeRule.onNodeWithText(label(R.string.library_add_gpx)).assertIsNotEnabled()
        composeRule.onNodeWithText(label(R.string.library_change_gpx)).assertIsNotEnabled()
    }

    private fun label(id: Int): String = composeRule.activity.getString(id)

    private fun actions(
        onSettings: (MapTool) -> Unit,
        onAdd: (MapLibraryAction) -> Unit,
    ) = PhoneLibraryActions(
        onBack = {},
        onRefresh = {},
        onImportGpx = { _, _, _ -> },
        onUseRoute = {},
        onOpenMapTools = {},
        onOpenSettings = onSettings,
        onMapAction = onAdd,
        onSendGpx = {},
        onFilterChanged = {},
    )

    private fun route(
        id: String,
        title: String,
    ) = RouteLibraryRoute(
        id,
        title,
        "$id.gpx",
        1L,
        RouteLibrarySummary(1000.0, 100.0, 80.0, 1000.0, 0, 1000.0, 100.0),
    )
}
