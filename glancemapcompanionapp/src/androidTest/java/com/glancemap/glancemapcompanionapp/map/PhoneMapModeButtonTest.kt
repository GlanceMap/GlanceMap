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

        fun description(label: Int) =
            composeRule.activity.getString(R.string.map_mode_content_description, composeRule.activity.getString(label))

        composeRule
            .onNodeWithContentDescription(
                description(R.string.map_mode_north_up),
            ).performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertTrue(mode.value.isOffline)
            assertEquals(0, taps)
        }
        composeRule
            .onNodeWithContentDescription(
                description(R.string.map_mode_offline),
            ).assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertTrue(mode.value.isOffline) }
        composeRule
            .onNodeWithContentDescription(
                description(R.string.map_mode_offline),
            ).performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(PhoneMapMode(), mode.value) }
        composeRule.onNodeWithContentDescription(description(R.string.map_mode_north_up)).performClick()
        composeRule.runOnIdle { assertEquals(PhoneMapOrientation.HEADING_UP, mode.value.orientation) }
    }
}
