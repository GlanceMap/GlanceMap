package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhoneTerrainPreviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun quickDisplayOpensASeparateDismissiblePreviewWithoutChangingMapSettings() {
        val open = mutableStateOf(false)
        var settingsChanges = 0
        val data =
            PhoneTerrainPreviewData(
                PhoneMapCameraSnapshot(46.0, 7.0, 12.0),
                PhoneOnlineMapSource.OPEN_TOPO,
                emptyList(),
            )
        compose.setContent {
            MaterialTheme {
                Column {
                    layerQuickDisplay(layerState(), onTerrainPreview = { open.value = true }) { settingsChanges += 1 }
                }
                if (open.value) phoneTerrainPreviewDialog(data) { open.value = false }
            }
        }
        compose.onNodeWithText(label(R.string.terrain_preview_open)).performClick()
        compose.onNodeWithText(label(R.string.terrain_preview_summary)).assertIsDisplayed()
        compose
            .onNodeWithContentDescription(label(R.string.common_action_close))
            .performClick()
        compose.onNodeWithText(label(R.string.terrain_preview_summary)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, settingsChanges) }
    }

    @Test
    fun previewButtonIsDisabledAndExplainsWhyWhenOnlinePreviewIsUnavailable() {
        compose.setContent { MaterialTheme { Column { layerQuickDisplay(layerState(), onSettings = {}) } } }
        compose.onNodeWithText(label(R.string.terrain_preview_open)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.terrain_preview_offline)).assertIsDisplayed()
    }

    @Test
    fun selectingTheCurrentBasemapKeepsCameraButtonsEnabled() {
        val data =
            PhoneTerrainPreviewData(
                PhoneMapCameraSnapshot(45.98, 7.66, 12.0),
                PhoneOnlineMapSource.MAPTOOLKIT_HIKING,
                listOf(PhoneMapRouteSegment(listOf(GeoPoint(45.98, 7.66), GeoPoint(45.99, 7.67)))),
            )
        compose.setContent { MaterialTheme { phoneTerrainPreviewDialog(data) {} } }
        val reset = compose.onNodeWithContentDescription(label(R.string.terrain_preview_reset))
        compose.waitUntil(60_000) { runCatching { reset.assertIsEnabled() }.isSuccess }
        compose.onNodeWithText(label(R.string.terrain_preview_hiking)).performClick()
        compose.onAllNodesWithText(label(R.string.terrain_preview_hiking)).onLast().performClick()
        reset.assertIsEnabled().performClick()
        compose
            .onNodeWithContentDescription(label(R.string.terrain_preview_fit_tracks))
            .assertIsEnabled()
            .performClick()
    }

    private fun label(resource: Int) = compose.activity.getString(resource)

    private fun layerState() =
        MapToolsLayerState(
            base = PhoneMapSource.Online,
            baseOnlineSource = PhoneOnlineMapSource.OPEN_TOPO,
            offlineMaps = emptyList(),
            onlineSources = PhoneMapRendererCatalog.availableOnlineSources(),
            comparison = PhoneMapComparisonState(),
            hasElevationData = false,
            mapSettings = PhoneMapSettings(),
        )
}
