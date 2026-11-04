package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.recording.RecordingPoint
import com.glancemap.trailcore.recording.RecordingSessionLifecycleState
import com.glancemap.trailcore.recording.RecordingSessionPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRecordingMenuUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = PhoneRecordingSessionStore.state.value
    private val originalSteps = PhoneRecordingSessionStore.steps.value

    @After
    fun restoreState() {
        PhoneRecordingSessionStore.publish(original)
        PhoneRecordingSessionStore.publishSteps(originalSteps.generation, originalSteps.count)
    }

    @Test
    fun stepsFromAnotherSessionAreNotShown() {
        val state =
            PhoneRecordingUiState(
                session =
                    RecordingSessionLifecycleState(
                        generation = 3,
                        phase = RecordingSessionPhase.RECORDING,
                        startedAtMillis = 1_000,
                    ),
            )
        PhoneRecordingSessionStore.publishSteps(2, 12)
        compose.setContent {
            MaterialTheme { phoneRecordingLiveStatisticsGrid(state, PhoneGeneralSettings(), 61_000) }
        }
        val steps = hasAnyAncestor(hasTestTag("recording-metric-${R.string.activity_steps}"))
        compose.onNode(steps and hasText("—")).assertIsDisplayed()
        compose.runOnIdle { PhoneRecordingSessionStore.publishSteps(3, 90) }
        compose.onNode(steps and hasText("90")).assertIsDisplayed()
    }

    @Test
    fun activeMenuShowsFourColumnsAndStopRecapConfirmsDiscard() {
        val now = System.currentTimeMillis()
        val points =
            listOf(
                RecordingPoint(GeoPoint(46.0, 11.0), null, now - 120_000, 5f, null),
                RecordingPoint(GeoPoint(46.0, 11.01), null, now - 1_000, 5f, null),
            )
        val state =
            PhoneRecordingUiState(
                session =
                    RecordingSessionLifecycleState(
                        phase = RecordingSessionPhase.RECORDING,
                        startedAtMillis = now - 120_000,
                    ),
                points = points,
                distanceMeters = 500.0,
            )
        PhoneRecordingSessionStore.publish(state)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    Row {
                        Text("REC")
                        phoneRecordingHeaderActions(PhoneGeneralSettings(), onSettingsChanged = {})
                    }
                    phoneRecordingLiveStatisticsGrid(state, PhoneGeneralSettings(), now)
                }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.phone_rec_pause)).assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.phone_rec_stop)).assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.map_tools_settings_activity_hike)).assertIsNotEnabled()
        val metrics =
            listOf(
                R.string.activity_distance,
                R.string.activity_steps,
                R.string.activity_total_time,
                R.string.activity_active_time,
                R.string.activity_ascent,
                R.string.activity_descent,
                R.string.activity_average_speed,
                R.string.activity_max_speed,
                R.string.activity_average_pace,
                R.string.activity_best_pace,
                R.string.activity_average_hr,
                R.string.activity_max_hr,
                R.string.activity_average_cadence,
                R.string.activity_total_calories,
                R.string.activity_active_calories,
                R.string.activity_resting_calories,
            ).map { compose.onNodeWithTag("recording-metric-$it").fetchSemanticsNode().boundsInRoot }
        metrics.chunked(4).forEach { row ->
            row.forEach { assertEquals(row.first().top, it.top, 1f) }
            assertTrue(row.zipWithNext().all { (left, right) -> left.right <= right.left })
        }
        assertTrue(metrics.chunked(4).zipWithNext().all { (top, bottom) -> top.first().bottom <= bottom.first().top })
        compose.onNodeWithContentDescription(label(R.string.phone_rec_stop)).performClick()
        compose.onNodeWithText(label(R.string.phone_rec_stop_title)).assertIsDisplayed()
        val title =
            compose
                .onNodeWithTag("recording-stop-title")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString()
        assertTrue(title.startsWith("${label(R.string.phone_rec_profile_hike)} "))
        compose.onNodeWithTag("activity-statistics").assertExists()
        compose.onNodeWithText(label(R.string.phone_rec_save)).assertIsEnabled()
        compose.onNodeWithText(label(R.string.phone_rec_discard)).performClick()
        compose.onNodeWithText(label(R.string.phone_rec_discard_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.common_action_cancel)).performClick()
        compose.onNodeWithText(label(R.string.phone_rec_discard_confirm_title)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.phone_rec_stop_title)).assertIsDisplayed()
    }

    @Test
    fun narrowPanelKeepsRecordingActionsInsideTheScaffold() {
        PhoneRecordingSessionStore.publish(
            PhoneRecordingUiState(session = RecordingSessionLifecycleState(phase = RecordingSessionPhase.RECORDING)),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(200.dp).height(400.dp).testTag("narrow-panel")) {
                    MapToolScaffold(
                        presentation =
                            MapToolScaffoldState(
                                MapToolPanelState(activeTool = MapTool.REC, mode = MapToolPanelMode.EXPANDED),
                            ),
                        actions =
                            MapToolScaffoldActions(
                                onToolSelected = {},
                                onToggleLauncher = {},
                                onAddPoi = {},
                                onOpenRecording = {},
                                onOpenGpxTools = {},
                                onCycleSize = {},
                                onResize = {},
                                onResizeFinished = {},
                                onBack = {},
                                onClose = {},
                                onFeatureSettings = {},
                            ),
                        mapContent = { Box(Modifier.fillMaxSize()) },
                        panelContent = { _, _, _ -> },
                        headerContent = { tool ->
                            if (tool == MapTool.REC) phoneRecordingHeaderActions(PhoneGeneralSettings()) {}
                        },
                    )
                }
            }
        }
        val panel = compose.onNodeWithTag("narrow-panel").fetchSemanticsNode().boundsInRoot
        listOf(R.string.phone_rec_pause, R.string.phone_rec_stop, R.string.map_tools_settings_activity_hike).forEach {
            val node = compose.onNodeWithContentDescription(label(it)).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= panel.left && bounds.right <= panel.right)
        }
    }

    private fun label(resource: Int): String = compose.activity.getString(resource)
}
