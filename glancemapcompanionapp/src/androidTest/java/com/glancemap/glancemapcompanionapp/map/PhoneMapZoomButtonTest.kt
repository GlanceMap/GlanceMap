package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneMapZoomButtonTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapZoomsOnceAndHoldingRepeatsUntilRelease() {
        val zoomInCount = mutableIntStateOf(0)
        val zoomOutCount = mutableIntStateOf(0)
        var longPressTimeoutMillis = 500L
        composeRule.setContent {
            longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis
            MaterialTheme {
                Column {
                    PhoneMapZoomButton(
                        onZoom = { zoomInCount.intValue += 1 },
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Zoom in",
                        modifier = Modifier.testTag("zoom-in"),
                    )
                    PhoneMapZoomButton(
                        onZoom = { zoomOutCount.intValue += 1 },
                        imageVector = Icons.Filled.Remove,
                        contentDescription = "Zoom out",
                        modifier = Modifier.testTag("zoom-out"),
                    )
                }
            }
        }

        val zoomIn = composeRule.onNodeWithTag("zoom-in")
        zoomIn.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, zoomInCount.intValue) }

        zoomIn.performTouchInput {
            down(center)
            advanceEventTime(longPressTimeoutMillis + 20L)
            advanceEventTime(360L)
            up()
        }
        composeRule.runOnIdle {
            assertTrue("Holding zoom in should repeat camera zoom steps", zoomInCount.intValue >= 4)
            assertEquals(0, zoomOutCount.intValue)
        }

        composeRule.onNodeWithTag("zoom-out").performClick()
        composeRule.runOnIdle { assertEquals(1, zoomOutCount.intValue) }
    }
}
