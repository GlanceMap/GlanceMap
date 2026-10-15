package com.glancemap.glancemapcompanionapp.filepicker

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionNavigationDrawerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun menuUsesHalfTheWidthKeepsFooterVisibleAndBackPreservesMap() {
        lateinit var drawer: DrawerState
        var mapMounts = 0
        var mapDisposals = 0
        var mapBackPresses = 0
        var selected: CompanionHomeArea? = null
        val utilities = mutableListOf<String>()
        composeRule.setContent {
            drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            MaterialTheme {
                companionNavigationDrawer(
                    drawerState = drawer,
                    state = CompanionMenuState(CompanionHomeArea.MAP, debugCaptureActive = false),
                    actions =
                        CompanionMenuActions(
                            onNavigate = { selected = it },
                            onDebug = { utilities += "debug" },
                            onQuickGuide = { utilities += "guide" },
                            onCreditsLegal = { utilities += "legal" },
                        ),
                ) {
                    DisposableEffect(Unit) {
                        mapMounts++
                        onDispose { mapDisposals++ }
                    }
                    BackHandler { mapBackPresses++ }
                    Box(Modifier.fillMaxSize().testTag("map")) {
                        Button(onClick = { scope.launch { drawer.open() } }) { Text("Open menu") }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Open menu").performClick()
        composeRule.waitForIdle()
        val mapBounds = composeRule.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        val menuBounds =
            composeRule
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(mapBounds.width / 2f, menuBounds.width, 1f)
        clickUtilities()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_title)).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.library_title)).assertDoesNotExist()
        assertEquals(listOf("debug", "guide", "legal"), utilities)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_today_hike)).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_mission_plan)).performClick()
        assertEquals(CompanionHomeArea.MISSION_PLAN, selected)
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(drawer.isOpen)
            assertEquals(1, mapMounts)
            assertEquals(0, mapDisposals)
            assertEquals(0, mapBackPresses)
        }
    }

    @Test
    fun settingsDestinationBackReopensTheMenu() {
        val area = mutableStateOf(CompanionHomeArea.MAP)
        lateinit var drawer: DrawerState
        composeRule.setContent {
            drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val returnToMenu: () -> Unit = {
                area.value = CompanionHomeArea.MAP
                scope.launch { drawer.open() }
            }
            BackHandler(enabled = area.value != CompanionHomeArea.MAP, onBack = returnToMenu)
            MaterialTheme {
                companionNavigationDrawer(
                    drawerState = drawer,
                    state = CompanionMenuState(area.value, debugCaptureActive = false),
                    actions =
                        CompanionMenuActions(
                            onNavigate = {
                                area.value = it
                                scope.launch { drawer.close() }
                            },
                            onDebug = {},
                            onQuickGuide = {},
                            onCreditsLegal = {},
                        ),
                ) {
                    Box(Modifier.fillMaxSize().testTag("screen")) {
                        if (area.value == CompanionHomeArea.MAP) {
                            Button(onClick = { scope.launch { drawer.open() } }) { Text("Open menu") }
                        } else {
                            Button(onClick = returnToMenu) { Text("Back to map menu") }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Open menu").performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_tool_settings_title)).performClick()
        composeRule.onNodeWithText("Back to map menu").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(CompanionHomeArea.SETTINGS, area.value)
            assertFalse(drawer.isOpen)
        }
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_title)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(CompanionHomeArea.MAP, area.value)
            assertTrue(drawer.isOpen)
        }
    }

    @Test
    fun watchGroupKeepsSyncIndependentAndBackRestoresItsShortcuts() {
        val area = mutableStateOf(CompanionHomeArea.MAP)
        val sync = mutableStateOf(false)
        val drawer = setWatchContent(area, sync)
        composeRule.onNodeWithText("Open menu").performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_navigation_watch)).performClick()
        val toggle =
            composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_live_hike_sync))
        toggle
            .assertIsOff()
            .performClick()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        composeRule.runOnIdle {
            assertEquals(CompanionHomeArea.MAP, area.value)
            assertTrue(drawer.isOpen)
        }
        listOf(CompanionHomeArea.LIVE_HIKE, CompanionHomeArea.SEND_TO_WATCH).forEach { destination ->
            composeRule.onNodeWithText(composeRule.activity.getString(destination.titleResource)).performClick()
            composeRule.onNodeWithText("Watch destination").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(destination, area.value)
                assertFalse(drawer.isOpen)
            }
            composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_live_hike)).assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(CompanionHomeArea.MAP, area.value)
                assertTrue(drawer.isOpen)
            }
        }
    }

    private fun setWatchContent(
        area: MutableState<CompanionHomeArea>,
        sync: MutableState<Boolean>,
    ): DrawerState {
        lateinit var drawer: DrawerState
        composeRule.setContent {
            drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val returnToMenu: () -> Unit = {
                area.value = CompanionHomeArea.MAP
                scope.launch { drawer.open() }
            }
            BackHandler(enabled = area.value != CompanionHomeArea.MAP, onBack = returnToMenu)
            MaterialTheme {
                companionNavigationDrawer(
                    drawerState = drawer,
                    state =
                        CompanionMenuState(
                            area.value,
                            debugCaptureActive = false,
                            liveHikeSyncEnabled = sync.value,
                        ),
                    actions =
                        CompanionMenuActions(
                            onNavigate = {
                                area.value = it
                                scope.launch { drawer.close() }
                            },
                            onDebug = {},
                            onQuickGuide = {},
                            onCreditsLegal = {},
                            onLiveHikeSyncChanged = { sync.value = it },
                        ),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (area.value == CompanionHomeArea.MAP) {
                            Button(onClick = { scope.launch { drawer.open() } }) { Text("Open menu") }
                        } else {
                            Text("Watch destination")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return drawer
    }

    private fun clickUtilities() {
        listOf(
            R.string.home_debug_capture_start_content_description,
            R.string.common_quick_guide_content_description,
            R.string.settings_credits_legal_title,
        ).forEach { resource ->
            composeRule
                .onNodeWithContentDescription(composeRule.activity.getString(resource))
                .assertIsDisplayed()
                .performClick()
        }
    }
}
