package com.glancemap.glancemapwearos.presentation.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearInfoDialogSwipeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun swipeDownAtTopDismissesWhenEnabled() {
        var dismissed = false
        composeRule.setContent {
            GlanceMapTheme {
                WearInfoDialog(
                    visible = true,
                    title = "What's new",
                    onDismiss = { dismissed = true },
                    swipeDownToDismiss = true,
                ) {
                    item { Text("Release notes") }
                }
            }
        }
        composeRule.onRoot().performTouchInput { swipeDown() }
        composeRule.runOnIdle { assertTrue(dismissed) }
    }
}
