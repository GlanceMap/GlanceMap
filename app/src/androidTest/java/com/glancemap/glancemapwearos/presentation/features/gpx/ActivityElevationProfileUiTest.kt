package com.glancemap.glancemapwearos.presentation.features.gpx

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ActivityElevationProfileUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activityDetailsCanScrollToTheInlineProfileOnASmallRoundWatch() {
        val file = GpxFileState("Morning walk", "activity.gpx", null, 3_200.0, 30.0, estimatedDurationSec = null)
        composeRule.setContent {
            withWearDeviceConfig(SmallRoundWatchMaxText) {
                GlanceMapTheme {
                    ActivityDetailsDialog(file, true, Color.Magenta, loadElevationProfile = { profile }, onDismiss = {})
                }
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Elevation profile"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ACTIVITY_ELEVATION_CHART_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Grey lines: gaps").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun switchingActivitiesCancelsTheOldLoadAndUsesImperialUnits() {
        var path by mutableStateOf("first.gpx")
        val first = CompletableDeferred<ActivityElevationProfile?>()
        val second = CompletableDeferred<ActivityElevationProfile?>()
        composeRule.setContent {
            GlanceMapTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ActivityElevationProfileSection(path, false, Color.Magenta) {
                        if (it == "first.gpx") first.await() else second.await()
                    }
                }
            }
        }
        composeRule.onNodeWithText("Loading elevation profile…").assertIsDisplayed()
        composeRule.runOnIdle {
            path = "second.gpx"
            second.complete(profile)
        }
        composeRule.onNodeWithTag(ACTIVITY_ELEVATION_CHART_TAG).assertExists()
        composeRule.onNodeWithText("ft").assertTextEquals("ft")
        composeRule.onNodeWithText("0 mi").assertExists()
        composeRule.runOnIdle { first.complete(ActivityElevationProfile(emptyList(), 0.0)) }
        composeRule.onNodeWithTag(ACTIVITY_ELEVATION_CHART_TAG).assertExists()
        composeRule.onNodeWithText("No elevation data").assertDoesNotExist()
    }

    @Test
    fun recordingWithoutElevationsShowsTheEmptyState() {
        composeRule.setContent {
            GlanceMapTheme {
                ActivityElevationProfileSection("flat.gpx", true, Color.Magenta) {
                    ActivityElevationProfile(emptyList(), 0.0)
                }
            }
        }
        composeRule.onNodeWithText("No elevation data").assertIsDisplayed()
        composeRule.onNodeWithTag(ACTIVITY_ELEVATION_CHART_TAG).assertDoesNotExist()
    }

    @Test
    fun unreadableRecordingShowsAnErrorWithoutClosingDetails() {
        composeRule.setContent {
            GlanceMapTheme {
                ActivityElevationProfileSection("unreadable.gpx", true, Color.Magenta) {
                    throw IOException("unavailable")
                }
            }
        }
        composeRule.onNodeWithText("Unable to load elevation profile").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation profile").assertIsDisplayed()
    }

    private val profile =
        ActivityElevationProfile(
            samples =
                listOf(
                    ElevationSample(0.0, 100.0, 0.0, 0.0, null),
                    ElevationSample(1_000.0, 120.0, 20.0, 0.0, null),
                    ElevationSample(1_000.0, 130.0, 20.0, 0.0, null, gapBefore = true),
                    ElevationSample(3_200.0, 110.0, 20.0, 20.0, null),
                ),
            totalDistance = 3_200.0,
        )
}
