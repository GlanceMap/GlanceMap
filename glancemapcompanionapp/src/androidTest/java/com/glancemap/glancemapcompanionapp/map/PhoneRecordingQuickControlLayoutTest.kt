package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.buildTrailGuidanceSession
import com.glancemap.trailcore.guidance.computeTrailGuidanceState
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.recording.RecordingSessionLifecycleState
import com.glancemap.trailcore.recording.RecordingSessionPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRecordingQuickControlLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val originalRecording = PhoneRecordingSessionStore.state.value
    private val originalGuidance = PhoneGuidanceSessionStore.state.value

    @After
    fun restoreRecording() {
        PhoneRecordingSessionStore.publish(originalRecording)
        PhoneGuidanceSessionStore.publish(originalGuidance)
    }

    @Test
    fun recordingAndExpandedActionsStayRightOfRecenterOnANarrowMap() {
        PhoneGuidanceSessionStore.publish(PhoneGuidanceUiState())
        PhoneRecordingSessionStore.publish(
            PhoneRecordingUiState(session = RecordingSessionLifecycleState(phase = RecordingSessionPhase.RECORDING)),
        )
        var recenterTaps = 0
        showMap { recenterTaps++ }
        assertClearOfRecenter()
        compose.onNodeWithTag("recenter").performClick()
        compose.runOnIdle { assertEquals(1, recenterTaps) }
        compose.onNodeWithContentDescription(label(R.string.phone_rec_expand_controls)).performClick()
        assertClearOfRecenter()
        val recording = compose.onNodeWithTag("phone-recording-quick-control").fetchSemanticsNode().boundsInRoot
        listOf(R.string.phone_rec_pause, R.string.phone_rec_stop).forEach { text ->
            val button = compose.onNode(hasText(label(text)) and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue(button.left >= recording.left && button.right <= recording.right)
        }
        compose.onNodeWithContentDescription(label(R.string.phone_rec_collapse_controls)).performClick()
        compose.runOnIdle {
            PhoneRecordingSessionStore.publish(
                PhoneRecordingUiState(
                    session =
                        RecordingSessionLifecycleState(
                            phase = RecordingSessionPhase.PAUSED,
                            autoPaused = true,
                        ),
                ),
            )
        }
        assertClearOfRecenter()
        compose.onNodeWithContentDescription(label(R.string.phone_rec_expand_controls)).assertIsDisplayed()
    }

    @Test
    fun guidanceAndRecordingKeepRecenterClearWhenEitherSideExpands() {
        val guidance = guidanceFixture()
        PhoneGuidanceSessionStore.publish(guidance)
        PhoneRecordingSessionStore.publish(
            PhoneRecordingUiState(session = RecordingSessionLifecycleState(phase = RecordingSessionPhase.RECORDING)),
        )
        var recenterTaps = 0
        showMap { recenterTaps++ }
        assertOppositeSides()
        compose.onNodeWithContentDescription(label(R.string.phone_guidance_expand_controls)).performClick()
        assertOppositeSides()
        assertGuidanceButtonsFit(R.string.common_action_pause)
        compose.onNodeWithTag("recenter").performClick()
        compose.runOnIdle { assertEquals(1, recenterTaps) }
        compose.onNodeWithContentDescription(label(R.string.phone_rec_expand_controls)).performClick()
        assertOppositeSides()
        compose.onNodeWithContentDescription(label(R.string.phone_guidance_expand_controls)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.phone_guidance_collapse_controls)).assertDoesNotExist()
        compose.runOnIdle { PhoneGuidanceSessionStore.publish(guidance.copy(paused = true)) }
        compose.onNodeWithContentDescription(label(R.string.phone_guidance_expand_controls)).performClick()
        assertOppositeSides()
        assertGuidanceButtonsFit(R.string.common_action_resume)
        compose.onNodeWithContentDescription(label(R.string.phone_rec_expand_controls)).assertIsDisplayed()
    }

    private fun assertOppositeSides() {
        assertClearOfRecenter()
        val guidance = compose.onNodeWithTag("phone-guidance-quick-control").fetchSemanticsNode().boundsInRoot
        val recenter = compose.onNodeWithTag("recenter").fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        assertTrue(guidance.left >= map.left)
        assertTrue(guidance.right < recenter.left)
        assertEquals(recenter.bottom, guidance.bottom, 1f)
    }

    private fun assertGuidanceButtonsFit(pauseOrResume: Int) {
        val guidance = compose.onNodeWithTag("phone-guidance-quick-control").fetchSemanticsNode().boundsInRoot
        listOf(pauseOrResume, R.string.phone_guidance_end).forEach { text ->
            val button =
                compose
                    .onNode(
                        hasText(label(text)) and hasClickAction() and
                            hasAnyAncestor(hasTestTag("phone-guidance-quick-control")),
                    ).fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(button.left >= guidance.left && button.right <= guidance.right)
        }
    }

    private fun guidanceFixture(): PhoneGuidanceUiState {
        val start = GeoPoint(46.0, 11.0)
        val session =
            buildTrailGuidanceSession(
                trackId = "forest-loop",
                trackTitle = "Forest loop",
                trackPoints = listOf(TrailPoint(start), TrailPoint(GeoPoint(46.0, 11.01))),
                startReached = true,
            )
        return PhoneGuidanceUiState(
            session = session,
            guidance =
                computeTrailGuidanceState(session, start).copy(
                    distanceToInstructionMeters = 350.0,
                    distanceRemainingMeters = 2000.0,
                    estimatedRemainingSeconds = 1200L,
                ),
            message = PhoneGuidanceMessage.ACTIVE,
        )
    }

    private fun showMap(onRecenter: () -> Unit) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    MapToolScaffold(
                        presentation = MapToolScaffoldState(MapToolPanelState()),
                        actions = noOpActions(),
                        panelContent = { _, _, _ -> },
                        mapContent = {
                            Box(Modifier.fillMaxSize().testTag("map")) {
                                PhoneMapModeButton(
                                    mode = PhoneMapMode(),
                                    onClick = onRecenter,
                                    onToggleOffline = {},
                                    modifier =
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = mapToolLauncherHeight + 8.dp)
                                            .testTag("recenter"),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun assertClearOfRecenter() {
        val recording = compose.onNodeWithTag("phone-recording-quick-control").fetchSemanticsNode().boundsInRoot
        val recenter = compose.onNodeWithTag("recenter").fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        assertTrue(recording.left > recenter.right)
        assertTrue(recording.right <= map.right)
        assertEquals(recenter.bottom, recording.bottom, 1f)
    }

    private fun label(resource: Int) = compose.activity.getString(resource)

    private fun noOpActions() =
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
        )
}
