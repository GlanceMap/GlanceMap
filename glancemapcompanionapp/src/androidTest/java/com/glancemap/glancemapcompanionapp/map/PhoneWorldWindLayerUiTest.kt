package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneWorldWindLayerUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun terrainLayerTogglesOnAndOff() {
        val state =
            mutableStateOf(
                MapToolsLayerState(
                    base = PhoneMapSource.Online,
                    baseOnlineSource = PhoneOnlineMapSource.OPEN_TOPO,
                    offlineMaps = emptyList(),
                    onlineSources = listOf(PhoneOnlineMapSource.OPEN_TOPO),
                    comparison = PhoneMapComparisonState(),
                    hasElevationData = false,
                    mapSettings = PhoneMapSettings(),
                ),
            )
        composeRule.setContent {
            MaterialTheme {
                layerQuickDisplay(
                    state = state.value,
                    onTerrainPreview = { state.value = state.value.copy(terrain3dEnabled = !state.value.terrain3dEnabled) },
                    onSettings = {},
                )
            }
        }
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.terrain_preview_open))
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
            .performClick()
            .assertIsNotSelected()
    }
}
