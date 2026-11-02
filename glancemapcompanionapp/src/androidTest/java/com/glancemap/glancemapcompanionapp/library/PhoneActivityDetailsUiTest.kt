package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.phoneActivityMetrics
import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneActivityDetailsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bikeStatisticsKeepAllWatchMetricsAndRespectImperialUnits() {
        val context = compose.activity
        val stats = RecordedActivityStatistics(activityProfile = "BIKE", distanceMeters = 1_609.344, averageSpeedMps = 2.0, fastestSpeedMps = 0.0, averagePowerWatts = 0)
        val metrics = phoneActivityMetrics(context, stats, false)
        assertEquals(19, metrics.size)
        assertEquals(R.string.activity_total_calories, metrics[1].label)
        assertEquals(context.getString(R.string.phone_rec_distance_miles, 1.0), metrics.first().value)
        assertEquals("—", metrics.single { it.label == R.string.activity_average_hr }.value)
        assertEquals(context.getString(R.string.activity_unit_watts, 0), metrics.single { it.label == R.string.activity_average_power }.value)
        assertEquals("—", metrics.single { it.label == R.string.activity_best_pace }.value)
    }

    @Test
    fun openingAPreviousRecordingShowsDetailsInsteadOfStartingAPreview() {
        val state = mutableStateOf(libraryTestMapState(compose.activity))
        val route = libraryTestRoute().copy(isRecording = true)
        var details = 0
        var previews = 0
        compose.setContent {
            MaterialTheme {
                libraryItemRow(
                    PhoneLibraryRowState(PhoneLibraryItem.Gpx(route), enabled = true, onPreviewGpx = { previews++ }),
                    state.value,
                    libraryTestMapActions(state),
                    onDetails = { details++ },
                )
            }
        }
        compose.onNodeWithTag("library-item-gpx/${route.id}").performClick()
        compose.runOnIdle {
            assertEquals(1, details)
            assertEquals(0, previews)
        }
    }
}
