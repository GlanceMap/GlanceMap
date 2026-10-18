package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryFilter
import com.glancemap.glancemapcompanionapp.library.phoneLibraryHeader
import com.glancemap.glancemapcompanionapp.library.todayHikeGpxActions
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapToolLauncherTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val ui = mutableStateOf(PhoneMapUiState())
    private val opened = mutableListOf<String>()
    private val toolbarVisible = mutableStateOf(true)
    private val libraryFilter = mutableStateOf(PhoneLibraryFilter.GPX)

    @Test
    fun libraryOpensItsOwnSplitPanelAndToolsStaySeparate() {
        setContent()
        click(R.string.library_title)
        composeRule.onNodeWithText("Library content").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(MapTool.LIBRARY, ui.value.toolPanel.activeTool)
            assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode)
            assertFalse(ui.value.toolLauncherExpanded)
            assertTrue(opened.isEmpty())
        }
        listOf(R.string.map_tool_poi_title, R.string.map_tool_gpx_title, R.string.map_tool_maps_title).forEach {
            composeRule.onNodeWithTag("map-shortcut-$it").assertDoesNotExist()
        }
        click(R.string.library_title)
        composeRule.runOnIdle { assertEquals(MapToolPanelMode.CLOSED, ui.value.toolPanel.mode) }
        click(R.string.map_tool_launcher_label)
        composeRule.onNodeWithText(label(R.string.map_tool_settings_title)).assertDoesNotExist()
        click(R.string.map_tool_shortcut_add_poi)
        assertEquals(listOf("add-poi"), opened)
        click(R.string.map_tool_launcher_label)
        click(R.string.map_tool_shortcut_rec)
        assertEquals(listOf("add-poi", "rec"), opened)
    }

    @Test
    fun hikeReplacesWatchOpensSplitAndKeepsTheMapWhenExpandedAndClosed() {
        setContent()
        composeRule.onNodeWithText(label(R.string.map_navigation_watch)).assertDoesNotExist()
        click(R.string.map_navigation_layers)
        click(R.string.map_navigation_hike)
        composeRule.onNodeWithText("Hike content").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(MapTool.HIKE, ui.value.toolPanel.activeTool)
            assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode)
            assertEquals(DEFAULT_MAP_TOOL_PANEL_FRACTION, ui.value.toolPanel.splitFraction)
            assertTrue(opened.isEmpty())
        }
        val mapBounds = composeRule.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        val panelBounds = composeRule.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        assertTrue(panelBounds.top >= mapBounds.bottom)
        val expandDescription =
            label(R.string.map_tool_action_expand_content_description) + ". " + label(R.string.map_tool_resize_hint)
        composeRule.onNodeWithContentDescription(expandDescription).performClick()
        composeRule.runOnIdle { assertEquals(MapToolPanelMode.EXPANDED, ui.value.toolPanel.mode) }
        pressBack()
        composeRule.runOnIdle { assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode) }
        click(R.string.map_tool_launcher_label)
        click(R.string.map_navigation_hike)
        composeRule.runOnIdle {
            assertFalse(ui.value.toolLauncherExpanded)
            assertEquals(MapToolPanelMode.CLOSED, ui.value.toolPanel.mode)
        }
        click(R.string.map_navigation_hike)
        pressBack()
        composeRule.onNodeWithText("Hike content").assertDoesNotExist()
        composeRule.onNodeWithTag("map").assertIsDisplayed()
    }

    @Test
    fun hikeHeaderOffersOneGpxMenuAndKeepsThePanelSizeWhenChoosingLibrary() {
        setContent()
        click(R.string.map_navigation_hike)
        composeRule.onNodeWithContentDescription(label(R.string.today_hike_add_or_change_gpx)).performClick()
        composeRule.onNodeWithText(label(R.string.library_import_from_storage)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.library_choose_gpx)).performClick()
        assertEquals(listOf("pick-hike-gpx"), opened)
        composeRule.onNodeWithText(label(R.string.library_import_from_storage)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(MapTool.HIKE, ui.value.toolPanel.activeTool)
            assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode)
            assertEquals(DEFAULT_MAP_TOOL_PANEL_FRACTION, ui.value.toolPanel.splitFraction)
        }
        click(R.string.map_navigation_layers)
        composeRule.onNodeWithContentDescription(label(R.string.today_hike_add_or_change_gpx)).assertDoesNotExist()
    }

    @Test
    fun toolsGpxOpensTheResizablePanelAndBackReturnsToTheMap() {
        setContent()
        composeRule.runOnIdle {
            ui.value = ui.value.copy(toolPanel = MapToolPanelState(splitFraction = 0.61f, customSplitFraction = 0.61f))
        }
        click(R.string.map_tool_launcher_label)
        click(R.string.map_tool_shortcut_gpx)
        composeRule.onNodeWithText("GPX tools content").assertIsDisplayed()
        composeRule.runOnIdle {
            assertFalse(ui.value.toolLauncherExpanded)
            assertEquals(MapTool.GPX_TOOLS, ui.value.toolPanel.activeTool)
            assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode)
            assertEquals(0.61f, ui.value.toolPanel.splitFraction, 0f)
        }
        val mapBounds = composeRule.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        val panelBounds = composeRule.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        assertTrue(panelBounds.top >= mapBounds.bottom)
        pressBack()
        composeRule.onNodeWithText("GPX tools content").assertDoesNotExist()
        composeRule.onNodeWithTag("map").assertIsDisplayed()
    }

    @Test
    fun libraryCategoriesReplaceTheTitleAndKeepResizeAndCloseOnTheSameRow() {
        setContent(Modifier.width(320.dp))
        click(R.string.library_title)
        val header = composeRule.onNodeWithTag("map-tool-panel-header").fetchSemanticsNode().boundsInRoot
        composeRule
            .onNode(
                hasText(label(R.string.library_title)) and hasAnyAncestor(hasTestTag("map-tool-panel-header")),
            ).assertDoesNotExist()
        PhoneLibraryFilter.entries.forEach { category ->
            val tab = composeRule.onNodeWithTag("library-tab-${category.name}")
            tab.assertIsDisplayed().performClick().assertIsSelected()
            composeRule.runOnIdle { assertEquals(category, libraryFilter.value) }
            val bounds = tab.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= header.left && bounds.right <= header.right)
            assertEquals(header.center.y, bounds.center.y, 1f)
        }
        composeRule.onNodeWithText(label(R.string.library_filter_bundles)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label(R.string.library_filter_bundles)).assertIsSelected()
        val expand =
            label(R.string.map_tool_action_expand_content_description) + ". " + label(R.string.map_tool_resize_hint)
        composeRule.onNodeWithContentDescription(expand).performClick()
        composeRule.runOnIdle { assertEquals(MapToolPanelMode.EXPANDED, ui.value.toolPanel.mode) }
        composeRule.onNodeWithTag("library-tab-BUNDLES").assertIsSelected()
        composeRule.onNodeWithContentDescription(label(R.string.common_action_close)).performClick()
        composeRule.runOnIdle { assertEquals(MapToolPanelMode.CLOSED, ui.value.toolPanel.mode) }
        click(R.string.library_title)
        composeRule.onNodeWithTag("library-tab-BUNDLES").assertIsSelected()
    }

    @Test
    fun hidingAndRestoringToolbarPreservesTheMapAndOpenPanel() {
        setContent()
        click(R.string.map_navigation_layers)
        val mapBounds = composeRule.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        val panelBounds = composeRule.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { toolbarVisible.value = false }
        composeRule.onNodeWithTag("map-tool-toolbar").assertDoesNotExist()
        composeRule.onNodeWithTag("map").assertIsDisplayed()
        assertEquals(mapBounds, composeRule.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot)
        assertEquals(panelBounds, composeRule.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot)
        composeRule.runOnIdle {
            assertEquals(MapTool.LAYER, ui.value.toolPanel.activeTool)
            toolbarVisible.value = true
        }
        composeRule.onNodeWithTag("map-tool-toolbar").assertIsDisplayed()
        assertEquals(panelBounds, composeRule.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot)
    }

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    private fun label(resource: Int): String = composeRule.activity.getString(resource)

    private fun click(resource: Int) {
        if (resource == R.string.library_title) {
            composeRule
                .onNode(hasText(label(resource)) and hasAnyAncestor(hasTestTag("map-tool-toolbar")))
                .performClick()
        } else {
            composeRule.onNodeWithText(label(resource)).performClick()
        }
    }

    private fun setContent(modifier: Modifier = Modifier) {
        composeRule.setContent {
            BackHandler(enabled = ui.value.toolLauncherExpanded || ui.value.toolPanel.mode != MapToolPanelMode.CLOSED) {
                ui.value = ui.value.onMapBack()
            }
            MaterialTheme {
                Box(modifier.fillMaxSize()) {
                    MapToolScaffold(
                        headerContent = { tool ->
                            if (tool == MapTool.LIBRARY) {
                                phoneLibraryHeader(libraryFilter.value) { libraryFilter.value = it }
                            }
                            if (tool == MapTool.HIKE) {
                                todayHikeGpxActions(
                                    RouteLibraryUiState(isLoading = false),
                                    onImport = {},
                                    onChoose = { opened += "pick-hike-gpx" },
                                )
                            }
                        },
                        presentation =
                            MapToolScaffoldState(
                                panel = ui.value.toolPanel,
                                launcherExpanded = ui.value.toolLauncherExpanded,
                                toolbarVisible = toolbarVisible.value,
                            ),
                        actions =
                            MapToolScaffoldActions(
                                onToolSelected = { ui.value = ui.value.selectTool(it) },
                                onToggleLauncher = { ui.value = ui.value.toggleToolLauncher() },
                                onAddPoi = { opened += "add-poi" },
                                onOpenRecording = { opened += "rec" },
                                onOpenGpxTools = {
                                    opened += "gpx"
                                    ui.value = ui.value.openToolPage(MapTool.GPX_TOOLS, featureSettings = false)
                                },
                                onCycleSize = { ui.value = ui.value.copy(toolPanel = ui.value.toolPanel.cycleSize()) },
                                onResize = {},
                                onResizeFinished = {},
                                onBack = { ui.value = ui.value.onMapBack() },
                                onClose = { ui.value = ui.value.closeTool() },
                                onFeatureSettings = {},
                            ),
                        mapContent = { Box(Modifier.fillMaxSize().testTag("map")) },
                        panelContent = { tool, _, _ ->
                            Box(Modifier.fillMaxSize().testTag("panel")) {
                                if (tool == MapTool.GPX_TOOLS) Text("GPX tools content")
                                if (tool == MapTool.HIKE) Text("Hike content")
                                if (tool == MapTool.LIBRARY) Text("Library content")
                            }
                        },
                    )
                }
            }
        }
    }
}
