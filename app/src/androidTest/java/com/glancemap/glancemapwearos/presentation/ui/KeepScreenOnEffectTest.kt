package com.glancemap.glancemapwearos.presentation.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepScreenOnEffectTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun disposal_restoresExistingWindowBrightnessAndKeepScreenOnFlag() {
        val showEffect = mutableStateOf(true)
        configureWindow(brightness = 0.70f, keepScreenOn = true)

        composeRule.setContent {
            if (showEffect.value) KeepScreenOnEffect(screenBrightness = 0.20f)
        }

        composeRule.runOnIdle {
            assertEquals(0.20f, composeRule.activity.window.attributes.screenBrightness, 0.001f)
            assertTrue(keepScreenOnFlagIsSet())
            showEffect.value = false
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(0.70f, composeRule.activity.window.attributes.screenBrightness, 0.001f)
            assertTrue(keepScreenOnFlagIsSet())
        }
    }

    @Test
    fun disposal_releasesWindowBrightnessAndKeepScreenOnFlagWhenItOwnedThem() {
        val showEffect = mutableStateOf(true)
        configureWindow(brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, keepScreenOn = false)

        composeRule.setContent {
            if (showEffect.value) KeepScreenOnEffect(screenBrightness = 0.20f)
        }

        composeRule.runOnIdle {
            assertTrue(keepScreenOnFlagIsSet())
            showEffect.value = false
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                composeRule.activity.window.attributes.screenBrightness,
                0.001f,
            )
            assertFalse(keepScreenOnFlagIsSet())
        }
    }

    private fun configureWindow(
        brightness: Float,
        keepScreenOn: Boolean,
    ) {
        composeRule.runOnUiThread {
            val window = composeRule.activity.window
            window.attributes = window.attributes.apply { screenBrightness = brightness }
            if (keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun keepScreenOnFlagIsSet(): Boolean = composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
}
