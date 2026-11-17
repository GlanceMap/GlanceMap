package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapFocusAutoTimerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun interactionsAndExitRestartTheCountdownAndUnavailableScreenSuspendsIt() {
        var enabled by mutableStateOf(false)
        var available by mutableStateOf(true)
        var touchDown by mutableStateOf(false)
        var interaction by mutableLongStateOf(0L)
        var entries = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MapFocusAutoTimer(enabled, 5, available && !touchDown, interaction) {
                entries++
                available = false
            }
        }
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.runOnIdle {
            assertEquals(0, entries)
            enabled = true
        }
        composeRule.mainClock.advanceTimeBy(4_000)
        composeRule.runOnIdle { interaction++ }
        composeRule.mainClock.advanceTimeBy(4_000)
        composeRule.runOnIdle {
            assertEquals(0, entries)
            touchDown = true
        }
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.runOnIdle {
            assertEquals(0, entries)
            touchDown = false
        }
        composeRule.mainClock.advanceTimeBy(5_100)
        composeRule.runOnIdle { assertEquals(1, entries) }
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.runOnIdle {
            assertEquals(1, entries)
            available = true
        }
        composeRule.mainClock.advanceTimeBy(5_100)
        composeRule.runOnIdle { assertEquals(2, entries) }
    }
}
