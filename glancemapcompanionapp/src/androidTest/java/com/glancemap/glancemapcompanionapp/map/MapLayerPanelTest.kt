package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun layerQuickDisplayOmitsGpxAndPoiAndAddsSlopeReliefForOfflineBase() {
        setContent()
        listOf(R.string.map_tools_gpx_global_visibility, R.string.map_tools_poi_global_visibility).forEach { label ->
            composeRule
                .onNodeWithContentDescription(composeRule.activity.getString(label))
                .assertDoesNotExist()
        }
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.map_tools_maps_hill_shading))
            .assertDoesNotExist()
        val night = composeRule.activity.getString(R.string.map_tools_maps_night_mode)
        composeRule.onNodeWithContentDescription(night).assertDoesNotExist()
        val slopeRelief = composeRule.activity.getString(R.string.map_layer_slope_title)
        composeRule.onNodeWithContentDescription(slopeRelief).assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(base = PhoneMapSource.Offline(offlineMap)) }
        composeRule.onNodeWithContentDescription(night).performScrollTo().assertExists()
        composeRule.onNodeWithContentDescription(slopeRelief).performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(state.value.mapSettings.reliefOverlayEnabled) }
    }

    @Test
    fun offlineThemesAndStyleAppearsAboveQuickDisplay() {
        state.value = state.value.copy(base = PhoneMapSource.Offline(offlineMap))
        setContent()

        val themesTop =
            composeRule
                .onNodeWithText(composeRule.activity.getString(R.string.map_layer_themes))
                .fetchSemanticsNode()
                .boundsInRoot.top
        val quickDisplayTop =
            composeRule
                .onNodeWithText(composeRule.activity.getString(R.string.map_tools_maps_quick_display_heading))
                .fetchSemanticsNode()
                .boundsInRoot.top

        assertTrue(themesTop < quickDisplayTop)
    }

    @Test
    fun comparisonPickerSelectsOnlineAndOfflineAndOffRetainsTheMix() {
        setContent()
        composeRule.onNode(progress).assertDoesNotExist()
        picker().performClick()
        val onlineGroup = composeRule.activity.getString(R.string.map_layer_group_online)
        val offlineGroup = composeRule.activity.getString(R.string.map_layer_group_offline)
        composeRule.onNodeWithText(onlineGroup).assertExists()
        composeRule.onNodeWithText(offlineGroup).assertExists()
        composeRule.onNodeWithText(offlineMap.displayName).assertDoesNotExist()
        composeRule.onNodeWithText(onlineGroup).performClick()
        PhoneMapRendererCatalog
            .supportedOnlineSources()
            .filterNot(
                PhoneMapRendererCatalog::isOnlineSourceAvailable,
            ).forEach {
                composeRule.onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(it)).assertDoesNotExist()
            }
        val clearComparison = composeRule.activity.getString(R.string.map_layer_clear)
        composeRule.onNodeWithText(clearComparison).assertDoesNotExist()
        composeRule.onNodeWithText(offlineGroup).performClick()
        composeRule.onNodeWithText(offlineMap.displayName).assertExists()
        composeRule.onNodeWithText(offlineGroup).performClick()
        composeRule.onNodeWithText(offlineMap.displayName).assertDoesNotExist()
        composeRule.onNodeWithText(offlineGroup).performClick()
        composeRule.onNodeWithText(offlineMap.displayName).performClick()
        composeRule.onNode(progress).performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
        composeRule.onNodeWithContentDescription(clearComparison).performClick()
        composeRule.runOnIdle {
            assertEquals(null, state.value.comparison.layer)
            assertEquals(75f, state.value.comparison.transparencyPercent, 0f)
        }
        composeRule.onNode(progress).assertDoesNotExist()
        picker().performClick()
        composeRule.onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(onlineLayer.source)).performClick()
        composeRule.runOnIdle { assertEquals(onlineLayer, state.value.comparison.layer) }
    }

    @Test
    fun sameOfflineMapOffersIndependentThemesAndBasePickerChangesTheBase() {
        state.value = state.value.copy(base = PhoneMapSource.Offline(offlineMap))
        setContent(expanded = true)
        picker().performClick()
        val onlineGroup = composeRule.activity.getString(R.string.map_layer_group_online)
        val sameMapOption =
            composeRule
                .onNodeWithText(composeRule.activity.getString(R.string.map_layer_same_map_theme))
                .fetchSemanticsNode()
                .boundsInRoot
        val onlineGroupOption =
            composeRule
                .onNodeWithText(onlineGroup)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(sameMapOption.top < onlineGroupOption.top)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_layer_same_map_theme)).performClick()
        composeRule.runOnIdle {
            assertEquals(PhoneMapComparisonLayer.Offline(offlineMap), state.value.comparison.layer)
            assertNotEquals(state.value.baseThemeConfig, state.value.comparisonThemeConfig)
        }
        val originalComparison = state.value.comparisonThemeConfig
        composeRule.onNodeWithTag("layer-base-theme").performScrollTo().performClick()
        composeRule.onNodeWithText("Mapsforge").performClick()
        composeRule.runOnIdle {
            assertEquals(PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID, state.value.baseThemeConfig.themeId)
            assertEquals(originalComparison, state.value.comparisonThemeConfig)
        }
        composeRule.onNodeWithTag("layer-base-picker").performScrollTo().performClick()
        composeRule.onNodeWithText(onlineGroup).performClick()
        composeRule.onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(onlineLayer.source)).performClick()
        composeRule.runOnIdle {
            assertEquals(PhoneMapSource.Online, state.value.base)
            assertEquals(onlineLayer.source, state.value.baseOnlineSource)
        }
    }

    @Test
    fun mixStaysAboveTheScrollableSelectorsInEveryComparisonCombination() {
        setContent()
        listOf(
            PhoneMapSource.Online to onlineLayer,
            PhoneMapSource.Online to PhoneMapComparisonLayer.Offline(offlineMap),
            PhoneMapSource.Offline(offlineMap) to onlineLayer,
            PhoneMapSource.Offline(offlineMap) to PhoneMapComparisonLayer.Offline(offlineMap),
        ).forEach { (base, layer) ->
            composeRule.runOnIdle {
                state.value =
                    state.value.copy(base = base, comparison = PhoneMapComparisonState(layer))
            }
            val mix =
                composeRule
                    .onNodeWithTag("layer-comparison-mix")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val a =
                composeRule
                    .onNodeWithTag("layer-base-picker")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val b = picker().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(mix.bottom <= a.top)
            assertEquals(a.top, b.top, 1f)
            composeRule
                .onNodeWithContentDescription(
                    composeRule.activity.getString(R.string.map_tools_poi_global_visibility),
                ).performScrollTo()
            val scrolledMix =
                composeRule
                    .onNodeWithTag("layer-comparison-mix")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertEquals(mix, scrolledMix)
            composeRule.onNodeWithTag("layer-base-picker").performScrollTo()
        }
    }

    private fun picker() = composeRule.onNodeWithTag("layer-comparison-picker")

    private fun setContent(expanded: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 660.dp)) {
                    val panel = MapToolPanelState().select(MapTool.LAYER).let { if (expanded) it.expand() else it }
                    MapToolScaffold(
                        presentation = MapToolScaffoldState(panel),
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
                            mapToolsLayerPanel(
                                state.value,
                                layerActions(),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun layerActions() =
        MapToolsLayerActions(
            onLayerChanged = {
                state.value =
                    state.value.copy(comparison = state.value.comparison.copy(layer = it))
            },
            onTransparencyChanged = {
                state.value =
                    state.value.copy(comparison = state.value.comparison.withTransparency(it))
            },
            onMapSettingsChanged = { state.value = state.value.copy(mapSettings = it) },
            onBaseMapChanged = { layer ->
                state.value =
                    when (layer) {
                        is PhoneMapComparisonLayer.Online ->
                            state.value.copy(
                                base = PhoneMapSource.Online,
                                baseOnlineSource = layer.source,
                            )
                        is PhoneMapComparisonLayer.Offline ->
                            state.value.copy(
                                base = PhoneMapSource.Offline(layer.map),
                            )
                    }
            },
            onBaseThemeChanged = { state.value = state.value.copy(baseThemeConfig = it) },
            onComparisonThemeChanged = {
                state.value =
                    state.value.copy(comparisonThemeConfig = it)
            },
        )
}
