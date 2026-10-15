package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneMapPointerOverlayTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun panningShowsCenteredPointerAndRecenteringHidesIt() {
        val mode = mutableStateOf(PhoneMapMode())
        var drags = 0
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier =
                        Modifier
                            .size(320.dp, 500.dp)
                            .testTag("map-viewport")
                            .pointerInput(Unit) {
                                detectDragGestures { _, _ ->
                                    drags += 1
                                    mode.value = mode.value.detachFromLocation()
                                }
                            },
                ) {
                    phoneMapPointerOverlay(mode.value.isDetachedFromLocation, false, null, {}, {})
                }
            }
        }
        val description = composeRule.activity.getString(R.string.map_pointer_content_description)
        val pointer = composeRule.onNodeWithContentDescription(description)
        pointer.assertDoesNotExist()
        val map = composeRule.onNodeWithTag("map-viewport")
        map.performTouchInput { swipeLeft() }
        pointer.assertIsDisplayed()
        val mapBounds = map.fetchSemanticsNode().boundsInRoot
        val pointerBounds = pointer.fetchSemanticsNode().boundsInRoot
        assertEquals(mapBounds.center.x, pointerBounds.center.x, 0.5f)
        assertEquals(mapBounds.center.y, pointerBounds.center.y, 0.5f)

        composeRule.runOnIdle { drags = 0 }
        pointer.performTouchInput { swipeLeft() }
        composeRule.runOnIdle {
            assertTrue("The pointer must let map gestures through", drags > 0)
            mode.value = mode.value.recenterOnLocation()
        }
        pointer.assertDoesNotExist()
    }

    @Test
    fun poiUsesTheUpdatedPointerPositionAndCancelDoesNotSelect() {
        val active = mutableStateOf(true)
        val target = mutableStateOf<PhoneMapCoordinate?>(null)
        var selected: PhoneMapCoordinate? = null
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(320.dp, 500.dp)) {
                    phoneMapPointerOverlay(
                        showPointer = false,
                        poiCreationActive = active.value,
                        pointerCoordinate = target.value,
                        onSelectPoiAtPointer = { point ->
                            selected = point
                            active.value = false
                        },
                        onCancelPoi = { active.value = false },
                    )
                }
            }
        }
        val description = composeRule.activity.getString(R.string.map_pointer_content_description)
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        val usePointer = composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_poi_use_pointer))
        usePointer.assertIsNotEnabled()
        composeRule.runOnIdle { target.value = PhoneMapCoordinate(46.0, 7.0) }
        val movedTarget = PhoneMapCoordinate(46.5, 7.5)
        composeRule.runOnIdle { target.value = movedTarget }
        usePointer.performClick()
        composeRule.runOnIdle {
            assertEquals(movedTarget, selected)
            selected = null
            active.value = true
        }
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.common_action_close)).performClick()
        composeRule.runOnIdle { assertNull(selected) }
        composeRule.onNodeWithContentDescription(description).assertDoesNotExist()
    }

    @Test
    fun routeSelectionKeepsItsPointerWithoutPoiControls() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(320.dp, 500.dp)) {
                    phoneMapPointerOverlay(true, false, null, {}, {})
                }
            }
        }
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.map_pointer_content_description))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_poi_use_pointer)).assertDoesNotExist()
    }
}
