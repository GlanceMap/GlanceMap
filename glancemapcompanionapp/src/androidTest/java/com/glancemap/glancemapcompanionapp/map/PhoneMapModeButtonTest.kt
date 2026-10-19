package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneMapModeButtonTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun manualRotationShowsRecenterAndRestoresTheRememberedOrientation() {
        val mode = mutableStateOf(PhoneMapMode())
        composeRule.setContent {
            MaterialTheme {
                PhoneMapModeButton(
                    mode = mode.value,
                    onClick = { mode.value = mode.value.recenterOnLocation() },
                    onToggleOffline = { mode.value = mode.value.toggleOfflineMode() },
                )
            }
        }

        fun description(label: Int) = composeRule.activity.getString(R.string.map_mode_content_description, composeRule.activity.getString(label))
        PhoneMapOrientation.entries.forEach { orientation ->
            composeRule.runOnIdle {
                mode.value = PhoneMapMode(orientation).detachFromLocation().withManualBearing(90f)
            }
            composeRule.onNodeWithContentDescription(description(R.string.map_mode_recenter)).performClick()
            composeRule.runOnIdle { assertEquals(PhoneMapMode(orientation), mode.value) }
            val label = if (orientation == PhoneMapOrientation.NORTH_UP) R.string.map_mode_north_up else R.string.map_mode_heading_up
            composeRule.onNodeWithContentDescription(description(label)).assertHasClickAction()
        }
    }

    @Test
    fun longPressTogglesOfflineWithoutAlsoPerformingTheNormalTapAction() {
        val mode = mutableStateOf(PhoneMapMode())
        var taps = 0
        composeRule.setContent {
            MaterialTheme {
                PhoneMapModeButton(
                    mode = mode.value,
                    onClick = {
                        taps += 1
                        mode.value = mode.value.toggleOrientation()
                    },
                    onToggleOffline = { mode.value = mode.value.toggleOfflineMode() },
                )
            }
        }

        fun description(label: Int) = composeRule.activity.getString(R.string.map_mode_content_description, composeRule.activity.getString(label))

        composeRule
            .onNodeWithContentDescription(
                description(R.string.map_mode_north_up),
            ).performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertTrue(mode.value.isOffline)
            assertEquals(0, taps)
            mode.value = mode.value.withManualBearing(125f)
        }
        composeRule
            .onNodeWithContentDescription(
                description(R.string.map_mode_offline),
            ).assertHasClickAction()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(PhoneMapMode().toggleOfflineMode(), mode.value)
            assertEquals(1, taps)
        }
        composeRule
            .onNodeWithContentDescription(
                description(R.string.map_mode_offline),
            ).performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(PhoneMapMode(), mode.value) }
        composeRule.onNodeWithContentDescription(description(R.string.map_mode_north_up)).performClick()
        composeRule.runOnIdle { assertEquals(PhoneMapOrientation.HEADING_UP, mode.value.orientation) }
    }
}
