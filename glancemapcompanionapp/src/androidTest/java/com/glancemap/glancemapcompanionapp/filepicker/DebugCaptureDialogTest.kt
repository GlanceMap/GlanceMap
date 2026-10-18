package com.glancemap.glancemapcompanionapp.filepicker

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.FileTransferViewModel
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugCaptureDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun latestCaptureAndStartActionsAreOnOneRowAndPreviousCaptureIsRemoved() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                DebugCaptureDialog(
                    context = composeRule.activity,
                    viewModel = FileTransferViewModel(),
                    debugCaptureState = PhoneDebugCaptureState(sessionId = 1L, hasPreviousCapture = true),
                    onDismiss = { dismissed = true },
                )
            }
        }

        val shareLabel = composeRule.activity.getString(R.string.home_debug_capture_share_latest_action)
        val startLabel = composeRule.activity.getString(R.string.home_debug_capture_start_new_action)
        val shareBounds = composeRule.onNodeWithText(shareLabel).fetchSemanticsNode().boundsInRoot
        val startBounds = composeRule.onNodeWithText(startLabel).fetchSemanticsNode().boundsInRoot

        assertTrue(shareBounds.center.x < startBounds.center.x)
        composeRule.onNodeWithText(shareLabel).assertExists()
        composeRule.onNodeWithText(startLabel).assertExists()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.home_debug_capture_share_previous_action))
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.common_action_close))
            .performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }
}
