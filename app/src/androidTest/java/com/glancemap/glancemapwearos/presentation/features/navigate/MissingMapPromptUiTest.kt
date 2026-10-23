package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MissingMapPromptUiTest {
    @get:Rule val composeRule = createComposeRule()
    private var visible by mutableStateOf(true)
    private var downloadCalls = 0
    private var dismissCalls = 0
    private var neverAgainCalls = 0

    @Test
    fun downloadActionIsReachableOnSmallWatchAndOnlyOpensDownloads() {
        launch()
        composeRule.onNodeWithText("No map for this area").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Send a .map file from your phone using Send to Watch, or download a bundle directly on your watch.",
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Download bundle").performScrollTo().performClick()
        assertActions(download = 1)
    }

    @Test
    fun notNowOnlyDismissesTheSession() {
        launch()
        composeRule.onNodeWithText("Not now").performScrollTo().performClick()
        assertActions(dismiss = 1)
    }

    @Test
    fun neverAgainHasItsOwnActionAndClosesThePrompt() {
        launch()
        composeRule.onNodeWithText("Never show again").performScrollTo().performClick()
        assertActions(neverAgain = 1)
    }

    @Test
    fun backDismissesWithoutDisablingFutureSessions() {
        launch()
        Espresso.pressBack()
        assertActions(dismiss = 1)
    }

    private fun assertActions(
        download: Int = 0,
        dismiss: Int = 0,
        neverAgain: Int = 0,
    ) {
        composeRule.runOnIdle {
            assertEquals(download, downloadCalls)
            assertEquals(dismiss, dismissCalls)
            assertEquals(neverAgain, neverAgainCalls)
        }
        composeRule.onNodeWithText("No map for this area").assertDoesNotExist()
    }

    private fun launch() {
        composeRule.setContent {
            withWearDeviceConfig(SmallRoundWatchMaxText) {
                GlanceMapTheme {
                    MissingMapPrompt(
                        visible = visible,
                        onDownload = {
                            downloadCalls++
                            visible = false
                        },
                        onDismiss = {
                            dismissCalls++
                            visible = false
                        },
                        onNeverShowAgain = {
                            neverAgainCalls++
                            visible = false
                        },
                    )
                }
            }
        }
    }
}
