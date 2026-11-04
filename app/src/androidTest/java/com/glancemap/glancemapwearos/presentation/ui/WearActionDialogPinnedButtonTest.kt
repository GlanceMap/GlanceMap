package com.glancemap.glancemapwearos.presentation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearActionDialogPinnedButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryButtonRemainsVisibleWhileNotesScroll() {
        composeRule.setContent {
            withWearDeviceConfig(SmallRoundWatchMaxText) {
                GlanceMapTheme {
                    WearActionDialog(
                        visible = true,
                        title = "What's new",
                        onDismissRequest = {},
                        buttons =
                            listOf(
                                WearActionDialogButton(text = "Rate app", onClick = {}),
                                WearActionDialogButton(text = "Not now", onClick = {}),
                            ),
                        pinPrimaryButton = true,
                    ) {
                        repeat(12) { Text("Update note ${it + 1}") }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Rate app").assertIsDisplayed()
        composeRule.onNodeWithText("Update note 12").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rate app").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rate app").assertIsDisplayed()
    }
}
