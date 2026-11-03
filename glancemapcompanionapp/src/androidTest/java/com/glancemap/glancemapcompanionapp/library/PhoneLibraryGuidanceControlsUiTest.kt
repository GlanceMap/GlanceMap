package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceSessionStore
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceUiState
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.buildTrailGuidanceSession
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLibraryGuidanceControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val originalGuidance = PhoneGuidanceSessionStore.state.value

    @After
    fun restoreGuidance() = PhoneGuidanceSessionStore.publish(originalGuidance)

    @Test
    fun guidedGpxReplacesStartWithPauseAndStopOfTheSameHeight() {
        val item = PhoneLibraryItem.Gpx(libraryTestRoute())
        PhoneGuidanceSessionStore.publish(PhoneGuidanceUiState())
        val state = mutableStateOf(libraryTestMapState(compose.activity))
        compose.setContent {
            MaterialTheme {
                libraryItemRow(
                    PhoneLibraryRowState(item, enabled = true, guidanceEnabled = true),
                    state.value,
                    libraryTestMapActions(state),
                ) {}
            }
        }
        val startHeight = heightOf("library-guidance-${item.key}")

        compose.runOnIdle { PhoneGuidanceSessionStore.publish(guidance(item.key, paused = false)) }
        compose.onNodeWithTag("library-guidance-${item.key}").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.common_action_pause)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.phone_guidance_stop)).assertIsDisplayed()
        listOf("library-guidance-pause", "library-guidance-stop").forEach { tag ->
            assertEquals(startHeight, heightOf(tag), 1f)
        }

        compose.runOnIdle { PhoneGuidanceSessionStore.publish(guidance(item.key, paused = true)) }
        compose.onNodeWithContentDescription(label(R.string.common_action_resume)).assertIsDisplayed()

        compose.runOnIdle { PhoneGuidanceSessionStore.publish(guidance("other-route", paused = false)) }
        compose.onNodeWithTag("library-guidance-${item.key}").assertIsDisplayed()
        compose.onNodeWithTag("library-guidance-stop").assertDoesNotExist()
    }

    private fun guidance(
        trackId: String,
        paused: Boolean,
    ) = PhoneGuidanceUiState(
        session =
            buildTrailGuidanceSession(
                trackId = trackId,
                trackTitle = "Forest loop",
                trackPoints = listOf(TrailPoint(GeoPoint(46.0, 11.0)), TrailPoint(GeoPoint(46.0, 11.01))),
                startReached = true,
            ),
        paused = paused,
    )

    private fun heightOf(tag: String): Float {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.boundsInRoot.height
    }

    private fun label(id: Int) = compose.activity.getString(id)
}
