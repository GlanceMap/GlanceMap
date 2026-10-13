package com.glancemap.glancemapcompanionapp.map

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.layout.CompanionLayoutContext
import com.glancemap.glancemapcompanionapp.layout.CompanionWidthClass
import com.glancemap.glancemapcompanionapp.layout.LocalCompanionLayoutContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapToolPanelResizeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var ui: MutableState<PhoneMapUiState>
    private var mapViewCreations = 0
    private var mapViewReleases = 0
    private var resizeCompletions = 0
    private var longPressMillis = 1_000L

    @Test
    fun defaultBottomAreaIncludesTheToolbarAndCustomSizingCanReach100Percent() {
        setContent()
        val map = composeRule.onNodeWithTag("map-view").fetchSemanticsNode().boundsInRoot
        val toolbar = composeRule.onNodeWithTag("map-tool-toolbar").fetchSemanticsNode().boundsInRoot
        val bottom =
            composeRule
                .onNodeWithTag("panel-content")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertEquals((bottom - map.top) * 0.32f, bottom - toolbar.top, 1f)

        composeRule.runOnIdle { ui.value = ui.value.copy(toolPanel = ui.value.toolPanel.resize(1f)) }
        val maximumToolbar = composeRule.onNodeWithTag("map-tool-toolbar").fetchSemanticsNode().boundsInRoot
        val maximumMap = composeRule.onNodeWithTag("map-view").fetchSemanticsNode().boundsInRoot
        assertEquals(map.top, maximumToolbar.top, 1f)
        assertTrue(maximumMap.height > 0f)
        composeRule.runOnIdle {
            assertEquals(1, mapViewCreations)
            assertEquals(0, mapViewReleases)
        }
    }

    @Test
    fun tappingTheHandleCyclesFullDefaultAndSavedCustom() {
        setContent()
        composeRule.runOnIdle { ui.value = ui.value.copy(toolPanel = ui.value.toolPanel.resize(0.487f)) }
        val button = composeRule.onNode(hasContentDescription(resizeHint(), substring = true))
        button.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(MapToolPanelMode.EXPANDED, ui.value.toolPanel.mode) }
        button.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0.32f, ui.value.toolPanel.splitFraction, 0f) }
        button.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0.487f, ui.value.toolPanel.splitFraction, 0f) }
    }

    @Test
    fun longPressDragResizesWithoutClickingAndMapViewSurvivesSizeAndToolChanges() {
        setContent()
        val button = composeRule.onNode(hasContentDescription(resizeHint(), substring = true))
        button.performTouchInput {
            down(center)
            advanceEventTime(longPressMillis + 50)
            moveBy(Offset(0f, -100f))
            up()
        }
        var custom = 0f
        composeRule.runOnIdle {
            custom = ui.value.toolPanel.splitFraction
            assertTrue(custom > 0.32f)
            assertEquals(custom, ui.value.toolPanel.customSplitFraction)
            assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode)
            assertEquals(1, resizeCompletions)
        }
        button.performClick()
        composeRule.runOnIdle { assertEquals(MapToolPanelMode.EXPANDED, ui.value.toolPanel.mode) }
        button.performClick()
        composeRule.runOnIdle { assertEquals(0.32f, ui.value.toolPanel.splitFraction, 0f) }
        button.performClick()
        composeRule.runOnIdle {
            assertEquals(custom, ui.value.toolPanel.splitFraction, 0f)
            ui.value =
                ui.value
                    .selectTool(MapTool.POI)
                    .closeTool()
                    .selectTool(MapTool.GPX)
        }
        composeRule.runOnIdle {
            assertEquals(custom, ui.value.toolPanel.splitFraction, 0f)
            assertEquals(1, mapViewCreations)
            assertEquals(0, mapViewReleases)
        }
    }

    @Test
    fun holdingWithoutDraggingDoesNotCycleAndListScrollingDoesNotResize() {
        setContent()
        composeRule.onNode(hasContentDescription(resizeHint(), substring = true)).performTouchInput {
            down(center)
            advanceEventTime(longPressMillis + 50)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode)
            assertEquals(0, resizeCompletions)
        }
        composeRule.onNodeWithTag("panel-content").performTouchInput { swipeUp() }
        composeRule.runOnIdle {
            assertEquals(MapToolPanelMode.SPLIT, ui.value.toolPanel.mode)
            assertEquals(0.32f, ui.value.toolPanel.splitFraction, 0f)
            assertEquals(null, ui.value.toolPanel.customSplitFraction)
        }
    }

    @Test
    fun widePanelsResizeHorizontallyAndRestoreCustomSizing() {
        val restoration = StateRestorationTester(composeRule)
        setContent(restoration, wide = true)
        composeRule.onNode(hasContentDescription(resizeHint(), substring = true)).performTouchInput {
            down(center)
            advanceEventTime(longPressMillis + 50)
            moveBy(Offset(-80f, 0f))
            up()
        }
        lateinit var expected: PhoneMapUiState
        composeRule.runOnIdle {
            assertTrue(ui.value.toolPanel.splitFraction > 0.32f)
            expected = ui.value
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(expected, ui.value) }
    }

    @Test
    fun preferencesPersistCustomAndDefaultSeparatelyWithoutChangingMapSettings() {
        val context = composeRule.activity
        val preferences = PhoneMapSettingsPreferences(context)
        val previousSizing = preferences.loadToolPanelSizing()
        val previousSettings = preferences.load()
        try {
            val custom = MapToolPanelState().select(MapTool.MAPS).resize(0.48f)
            preferences.saveToolPanelSizing(custom.expand().cycleSize())
            val reloaded = PhoneMapSettingsPreferences(context).loadToolPanelSizing()
            assertEquals(0.32f, reloaded.splitFraction, 0f)
            assertEquals(0.48f, reloaded.customSplitFraction)
            assertEquals(previousSettings, preferences.load())
            assertEquals(0.48f, reloaded.select(MapTool.MAPS).cycleSize().splitFraction, 0f)
        } finally {
            preferences.saveToolPanelSizing(previousSizing)
        }
    }

    private fun resizeHint(): String = composeRule.activity.getString(R.string.map_tool_resize_hint)

    private fun setContent(
        restoration: StateRestorationTester? = null,
        wide: Boolean = false,
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            ui =
                rememberSaveable(stateSaver = phoneMapUiStateSaver) {
                    mutableStateOf(PhoneMapUiState().selectTool(MapTool.MAPS))
                }
            longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
            val layout = CompanionLayoutContext.Default.copy(widthClass = if (wide) CompanionWidthClass.MEDIUM else CompanionWidthClass.COMPACT)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalCompanionLayoutContext provides layout,
                LocalDensity provides if (wide) Density(density.density / 2f, density.fontScale) else density,
            ) {
                MaterialTheme {
                    MapToolScaffold(
                        presentation = MapToolScaffoldState(ui.value.toolPanel),
                        actions =
                            MapToolScaffoldActions(
                                onToolSelected = { ui.value = ui.value.selectTool(it) },
                                onToggleLauncher = {},
                                onAddPoi = {},
                                onOpenGpxTools = {},
                                onCycleSize = { ui.value = ui.value.copy(toolPanel = ui.value.toolPanel.cycleSize()) },
                                onResize = { ui.value = ui.value.copy(toolPanel = ui.value.toolPanel.resize(it)) },
                                onResizeFinished = { resizeCompletions++ },
                                onBack = { ui.value = ui.value.onMapBack() },
                                onClose = { ui.value = ui.value.closeTool() },
                                onFeatureSettings = { ui.value = ui.value.showFeatureSettings() },
                            ),
                        mapContent = {
                            AndroidView(
                                factory = {
                                    mapViewCreations++
                                    FrameLayout(it)
                                },
                                modifier = Modifier.fillMaxSize().testTag("map-view"),
                                onRelease = { mapViewReleases++ },
                            )
                        },
                        panelContent = { _, _, _ ->
                            Box(Modifier.fillMaxSize().testTag("panel-content")) {
                                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    Text("Scrollable content", Modifier.height(1_200.dp))
                                }
                            }
                        },
                    )
                }
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
    }
}
