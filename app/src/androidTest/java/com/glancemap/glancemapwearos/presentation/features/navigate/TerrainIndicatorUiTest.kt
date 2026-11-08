package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class TerrainIndicatorUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun failed3dTilesShowAnAccessibleErrorAndKeepTheAngleControl() {
        var clicks = 0
        composeRule.setContent {
            Box(Modifier.size(120.dp).background(Color.Black)) {
                SlopeOverlayStatusIndicator(
                    visible = true,
                    processing = false,
                    progressPercent = 50,
                    enabled = true,
                    screenSize = WearScreenSize.SMALL,
                    terrain3d = true,
                    renderError = true,
                    onClick = { clicks++ },
                )
            }
        }
        composeRule.onNodeWithText("3D!").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Some 3D map tiles could not be loaded").performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun loading3dButtonShowsTheReportedProgress() {
        composeRule.setContent {
            Box(Modifier.size(120.dp).background(Color.Black).testTag("terrain")) {
                SlopeOverlayStatusIndicator(
                    visible = true,
                    processing = true,
                    progressPercent = 75,
                    enabled = true,
                    screenSize = WearScreenSize.SMALL,
                    terrain3d = true,
                    onClick = {},
                )
            }
        }
        val yellowRingPixels = yellowRingPixels()
        assertTrue("Expected a 75% progress arc, found $yellowRingPixels pixels", yellowRingPixels > 330)
    }

    @Test
    fun ready3dButtonShowsACompletedProgressRing() {
        composeRule.setContent {
            Box(Modifier.size(120.dp).background(Color.Black).testTag("terrain")) {
                SlopeOverlayStatusIndicator(
                    visible = true,
                    processing = false,
                    progressPercent = null,
                    enabled = true,
                    screenSize = WearScreenSize.SMALL,
                    terrain3d = true,
                    onClick = {},
                )
            }
        }
        val yellowRingPixels = yellowRingPixels()
        assertTrue("Expected a full progress ring, found $yellowRingPixels pixels", yellowRingPixels > 500)
    }

    @Test
    fun loadingAfterReadyReducesThe3dProgressRing() {
        val processing = mutableStateOf(false)
        val progress = mutableStateOf<Int?>(null)
        composeRule.setContent {
            Box(Modifier.size(120.dp).background(Color.Black).testTag("terrain")) {
                SlopeOverlayStatusIndicator(
                    visible = true,
                    processing = processing.value,
                    progressPercent = progress.value,
                    enabled = true,
                    screenSize = WearScreenSize.SMALL,
                    terrain3d = true,
                    onClick = {},
                )
            }
        }
        val before = yellowRingPixels()
        composeRule.runOnIdle {
            processing.value = true
            progress.value = 50
        }
        val after = yellowRingPixels()
        assertTrue("Expected the ring to reduce from $before pixels to $after", before > after && after > 100)
    }

    private fun yellowRingPixels(): Int {
        val image = composeRule.onNodeWithTag("terrain").captureToImage()
        val pixels = image.toPixelMap()
        val density = image.width / 120f
        val centerX = image.width - 24f * density
        val centerY = image.height / 2f
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val radius = hypot(x - centerX, y - centerY) / density
                val color = pixels[x, y]
                if (radius in 9f..12f && color.red > 0.7f && color.green > 0.4f && color.blue < 0.2f) count++
            }
        }
        return count
    }
}
