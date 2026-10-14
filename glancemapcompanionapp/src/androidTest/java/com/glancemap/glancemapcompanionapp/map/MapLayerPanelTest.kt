package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MapLayerPanelTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val offlineMap = PhoneOfflineMap(File("Long offline map name for the comparison dropdown.map"))
    private val onlineLayer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP)
    private val state =
        mutableStateOf(
            MapToolsLayerState(
                base = PhoneMapSource.Online,
                baseOnlineSource = PhoneOnlineMapSource.OPEN_TOPO,
                offlineMaps = listOf(offlineMap),
                onlineSources = PhoneMapRendererCatalog.availableComparisonOnlineSources(),
                comparison = PhoneMapComparisonState(),
                hasElevationData = true,
                mapSettings = PhoneMapSettings(),
            ),
        )
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun quickDisplayIsAvailableInLayersAndOfflineControlsFollowTheBaseMap() {
        setContent()
        quickControl(R.string.map_tools_maps_quick_display_heading).assertExists()
        quickControl(R.string.map_tools_maps_live_elevation).assertExists()
        quickControl(R.string.map_tools_maps_live_distance).assertExists()
        quickControl(R.string.map_tools_maps_night_mode).assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(base = PhoneMapSource.Offline(offlineMap)) }
        quickControl(R.string.map_tools_maps_night_mode).assertExists()
    }

    private fun quickControl(label: Int) = composeRule.onNodeWithText(composeRule.activity.getString(label))

    @Test
    fun onlineBaseDropdownSelectsOnlineAndOfflineLayersAndClearKeepsTransparency() {
        setContent()
        composeRule.onNode(progress).assertIsNotEnabled()
        composeRule.onNodeWithText(offlineMap.displayName).assertDoesNotExist()
        picker().performClick()
        composeRule
            .onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(state.value.baseOnlineSource))
            .assertDoesNotExist()
        PhoneMapRendererCatalog
            .supportedOnlineSources()
            .filterNot(PhoneMapRendererCatalog::isOnlineSourceAvailable)
            .forEach { source ->
                composeRule.onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(source)).assertDoesNotExist()
            }
        composeRule.onNodeWithText(offlineMap.displayName).assertIsDisplayed().performClick()
        picker().assertIsDisplayed()
        composeRule.onNodeWithText(offlineMap.displayName).assertDoesNotExist()
        composeRule.onNode(progress).performTouchInput { click(Offset(center.x * 1.5f, center.y)) }
        composeRule.runOnIdle {
            assertEquals(PhoneMapComparisonLayer.Offline(offlineMap), state.value.comparison.layer)
            assertTrue(state.value.comparison.transparencyPercent in 65f..85f)
        }
        composeRule.onNode(progress).performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        clear().performClick()
        composeRule.runOnIdle {
            assertEquals(null, state.value.comparison.layer)
            assertEquals(100f, state.value.comparison.transparencyPercent, 0f)
        }
        clear().assertDoesNotExist()
        composeRule.onNode(progress).assertIsNotEnabled()
        picker().performClick()
        composeRule.onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(onlineLayer.source)).performClick()
        composeRule.runOnIdle { assertEquals(onlineLayer, state.value.comparison.layer) }
    }

    @Test
    fun offlineBaseDropdownOnlyOffersOnlineLayers() {
        state.value = state.value.copy(base = PhoneMapSource.Offline(offlineMap))
        setContent()
        picker().performClick()
        composeRule.onNodeWithText(offlineMap.displayName).assertDoesNotExist()
        composeRule.onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(onlineLayer.source)).performClick()
        picker().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(onlineLayer, state.value.comparison.layer)
            assertEquals(PhoneMapSource.Offline(offlineMap), state.value.base)
        }
    }

    @Test
    fun allThreeCombinationsShowBaseLayerClearAndSliderAtDefaultSizeOnASmallPhone() {
        setContent()
        val combinations =
            listOf(
                PhoneMapSource.Online to onlineLayer,
                PhoneMapSource.Online to PhoneMapComparisonLayer.Offline(offlineMap),
                PhoneMapSource.Offline(offlineMap) to onlineLayer,
            )
        combinations.forEach { (base, layer) ->
            composeRule.runOnIdle {
                state.value = state.value.copy(base = base, comparison = PhoneMapComparisonState(layer, 50f))
            }
            val panel = composeRule.onNodeWithTag("layer-test-panel").fetchSemanticsNode().boundsInRoot
            val baseBounds = composeRule.onNodeWithText(baseLabel()).fetchSemanticsNode().boundsInRoot
            val pickerBounds = picker().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val clearBounds = clear().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val sliderBounds =
                composeRule
                    .onNode(progress)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(baseBounds.top >= panel.top)
            assertTrue(baseBounds.bottom <= pickerBounds.top)
            assertEquals(pickerBounds.center.y, clearBounds.center.y, 1f)
            assertTrue(sliderBounds.top >= pickerBounds.bottom)
            assertTrue("Slider must fit without scrolling", sliderBounds.bottom <= panel.bottom + 1f)
        }
    }

    private fun picker() =
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.map_layer_select_source) + ": " +
                when (val layer = state.value.comparison.layer) {
                    is PhoneMapComparisonLayer.Online -> PhoneMapRendererCatalog.onlineSourceLabel(layer.source)
                    is PhoneMapComparisonLayer.Offline -> layer.map.displayName
                    null -> composeRule.activity.getString(R.string.map_layer_none)
                },
        )

    private fun clear() = composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.map_layer_clear))

    private fun baseLabel(): String =
        composeRule.activity.getString(R.string.map_layer_base_source) + ": " +
            when (val base = state.value.base) {
                PhoneMapSource.Online -> PhoneMapRendererCatalog.onlineSourceLabel(state.value.baseOnlineSource)
                is PhoneMapSource.Offline -> base.map.displayName
            }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 660.dp)) {
                    MapToolScaffold(
                        presentation = MapToolScaffoldState(MapToolPanelState().select(MapTool.LAYER)),
                        actions =
                            MapToolScaffoldActions(
                                onToolSelected = {},
                                onToggleLauncher = {},
                                onAddPoi = {},
                                onOpenRecording = {},
                                onOpenGpxTools = {},
                                onCycleSize = {},
                                onResize = {},
                                onResizeFinished = {},
                                onBack = {},
                                onClose = {},
                                onFeatureSettings = {},
                            ),
                        mapContent = { Box(Modifier.fillMaxSize()) },
                        panelContent = { _, _, _ ->
                            Box(Modifier.fillMaxSize().testTag("layer-test-panel")) {
                                mapToolsLayerPanel(
                                    state = state.value,
                                    onLayerChanged = { layer ->
                                        state.value = state.value.copy(comparison = state.value.comparison.copy(layer = layer))
                                    },
                                    onTransparencyChanged = { transparency ->
                                        state.value =
                                            state.value.copy(
                                                comparison = state.value.comparison.copy(transparencyPercent = transparency),
                                            )
                                    },
                                    onMapSettingsChanged = {},
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
