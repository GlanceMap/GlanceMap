package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong

@RunWith(AndroidJUnit4::class)
class RecordingDashboardLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dismissingWhileOpening_keepsSnapshotAvailableUntilFadeOutFinishes() {
        var expanded by mutableStateOf(false)

        composeRule.setContent {
            GlanceMapTheme {
                RecordingDashboardAnimationProbe(expanded = expanded)
            }
        }
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle { expanded = true }
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.runOnIdle { expanded = false }
        composeRule.mainClock.advanceTimeBy(100L)

        composeRule.onNodeWithTag(SNAPSHOT_TAG).assertExists()

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithTag(SNAPSHOT_TAG).assertDoesNotExist()
    }

    @Test
    fun renameSleepWakeAndSave_preservesNestedRenameStateAndEditedTitle() {
        var screenInteractive by mutableStateOf(true)
        var savedTitle: String? = null

        composeRule.setContent {
            withWearDeviceConfig(config = SmallRoundWatchMaxText) {
                GlanceMapTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RecordingStopPromptCard(
                            state = testRecordingState,
                            snapshot = if (screenInteractive) testSnapshot else null,
                            isMetric = true,
                            visible = screenInteractive,
                            onDiscard = {},
                            onSave = { savedTitle = it },
                            onCancel = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Rename activity").performClick()
        composeRule.onNode(isEditable()).performTextReplacement("Morning hike")

        composeRule.runOnIdle { screenInteractive = false }
        composeRule.runOnIdle { screenInteractive = true }

        composeRule.onNode(isEditable()).assertTextEquals("Morning hike")
        composeRule.onAllNodesWithText("Save")[0].performClick()
        composeRule.onNodeWithText("Morning hike").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("Morning hike", savedTitle)
    }
}

@Composable
private fun RecordingDashboardAnimationProbe(expanded: Boolean) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val presentationVisible =
        shouldUpdateRecordingDashboardPresentation(
            isScreenInteractive = true,
            suppressed = false,
            expandedTransitionCurrent = visibility.currentState,
            expandedTransitionTarget = visibility.targetState,
            expandedTransitionRunning = !visibility.isIdle,
            stopPromptVisible = false,
        )
    val snapshot = if (presentationVisible) testSnapshot else null

    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(animationSpec = tween(1_000)),
        exit = fadeOut(animationSpec = tween(1_000)),
    ) {
        androidx.wear.compose.material3.Text(
            text = checkNotNull(snapshot).pointCount.toString(),
            modifier = Modifier.testTag(SNAPSHOT_TAG),
        )
    }
}

private val testRecordingState =
    TraceRecordingUiState(
        saving = true,
        startedAtMillis = 1_000L,
        points =
            listOf(
                RecordedTracePoint(
                    latLong = LatLong(48.0, 2.0),
                    elevationMeters = 100.0,
                    timeMillis = 1_000L,
                    accuracyMeters = 4f,
                    speedMps = 2f,
                ),
                RecordedTracePoint(
                    latLong = LatLong(48.001, 2.001),
                    elevationMeters = 101.0,
                    timeMillis = 61_000L,
                    accuracyMeters = 4f,
                    speedMps = 2f,
                ),
            ),
    )

private val testSnapshot =
    RecordingDashboardSnapshot(
        durationSeconds = 60.0,
        distanceMeters = 100.0,
        elevationGainMeters = 1.0,
        elevationLossMeters = 0.0,
        currentElevationMeters = 101.0,
        currentSpeedMps = 2f,
        averageSpeedMps = 2.0,
        gpsAccuracyMeters = 4f,
        pointCount = 2,
        gpsActiveDurationSeconds = 60.0,
        recordingGapCount = 0,
        recordingMaxGapSeconds = 0.0,
    )

private const val SNAPSHOT_TAG = "recording-dashboard-snapshot"
