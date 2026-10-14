package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
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
    private val sync = mutableStateOf(false)
    private val opened = mutableListOf<String>()

    @Test
    fun libraryShortcutsOpenExistingPanelsAndScreensAndToolsStaySeparate() {
        setContent()
        click(R.string.library_title)
        click(R.string.map_tool_poi_title)
        composeRule.runOnIdle {
            assertEquals(MapTool.POI, ui.value.toolPanel.activeTool)
            assertFalse(ui.value.toolLauncherExpanded)
        }
        click(R.string.library_title)
        composeRule.onNodeWithTag("map-shortcut-${R.string.library_title}").performClick()
        assertEquals(listOf("library"), opened)
        click(R.string.library_title)
        composeRule.onNodeWithText(label(R.string.menu_routes)).assertDoesNotExist()
        click(R.string.map_tool_gpx_title)
        composeRule.runOnIdle { assertEquals(MapTool.GPX, ui.value.toolPanel.activeTool) }
        click(R.string.map_tool_launcher_label)
        composeRule.onNodeWithText(label(R.string.map_navigation_layers)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.map_tool_settings_title)).assertDoesNotExist()
        click(R.string.map_tool_shortcut_add_poi)
        assertEquals(listOf("library", "add-poi"), opened)
        click(R.string.map_tool_launcher_label)
        click(R.string.map_tool_shortcut_rec)
        assertEquals(listOf("library", "add-poi", "rec"), opened)
    }

    @Test
    fun watchSyncToggleDoesNotOpenDashboardAndBackDismissesShortcutsFirst() {
        setContent()
        click(R.string.map_navigation_layers)
        click(R.string.map_navigation_watch)
        val toggle = composeRule.onNodeWithContentDescription(label(R.string.menu_live_hike_sync))
        toggle
            .assertIsOff()
            .performClick()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        composeRule.runOnIdle {
            assertTrue(opened.isEmpty())
            assertTrue(ui.value.toolLauncherExpanded)
        }
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle {
            assertFalse(ui.value.toolLauncherExpanded)
            assertEquals(MapTool.LAYER, ui.value.toolPanel.activeTool)
        }
        click(R.string.map_navigation_watch)
        click(R.string.menu_live_hike)
        assertEquals(listOf("hike"), opened)
        composeRule.runOnIdle { assertFalse(ui.value.toolLauncherExpanded) }
        click(R.string.map_navigation_watch)
        click(R.string.transfer_title_send_to_watch)
        assertEquals(listOf("hike", "send"), opened)
    }

    private fun label(resource: Int): String = composeRule.activity.getString(resource)

    private fun click(resource: Int) = composeRule.onNodeWithText(label(resource)).performClick()

    private fun setContent() {
        composeRule.setContent {
            BackHandler(enabled = ui.value.toolLauncherExpanded) { ui.value = ui.value.onMapBack() }
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    mapToolLauncher(
                        presentation =
                            MapToolScaffoldState(
                                panel = ui.value.toolPanel,
                                launcherExpanded = ui.value.toolLauncherExpanded,
                                liveHikeSyncEnabled = sync.value,
                            ),
                        actions =
                            MapToolScaffoldActions(
                                onToolSelected = { ui.value = ui.value.selectTool(it) },
                                onToggleLauncher = { ui.value = ui.value.toggleToolLauncher() },
                                onAddPoi = { opened += "add-poi" },
                                onOpenRecording = { opened += "rec" },
                                onOpenGpxTools = { opened += "gpx" },
                                onCycleSize = {},
                                onResize = {},
                                onResizeFinished = {},
                                onBack = {},
                                onClose = {},
                                onFeatureSettings = {},
                            ),
                        navigation =
                            MapNavigationActions(
                                onOpenLibrary = { opened += "library" },
                                onSendToWatch = { opened += "send" },
                                onLiveHike = { opened += "hike" },
                                onLiveHikeSyncChanged = { sync.value = it },
                            ),
                    )
                }
            }
        }
    }
}
