package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigatePermissionRequiredContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun laterRemainsReachableWithLargeTextAndSelectsGpsOff() {
        var laterSelected = false
        composeRule.setContent {
            withWearDeviceConfig(config = SmallRoundWatchMaxText) {
                GlanceMapTheme {
                    NavigatePermissionRequiredContent(
                        sizing = rememberNavigateContentSizing(rememberWearAdaptiveSpec(), rememberWearScreenSize(), ""),
                        onPermissionLaunch = {},
                        onLater = { laterSelected = true },
                    )
                }
            }
        }
        val laterText =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.navigate_later_gps_off)
        composeRule.onNodeWithText(laterText).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(true, laterSelected) }
    }
}
