package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRecordingSettingsSectionsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val originalRecording = PhoneRecordingSessionStore.state.value
    private val originalSettings = PhoneRecordingSettingsState.state.value

    @After
    fun restoreState() {
        PhoneRecordingSessionStore.publish(originalRecording)
        PhoneRecordingSettingsState.publish(originalSettings)
    }

    @Test
    fun recordingSettingsStartCollapsedAndExpandBySection() {
        PhoneRecordingSessionStore.publish(PhoneRecordingUiState())
        compose.setContent {
            MaterialTheme {
                PhoneRecordingPanel(
                    general =
                        MapToolsGeneralState(
                            sensorCapabilities =
                                PhoneSensorCapabilities(
                                    gpsAvailable = true,
                                    compassFeatureAvailable = false,
                                    headingSensorAvailable = false,
                                    rotationVectorAvailable = false,
                                    accelerometerAvailable = false,
                                    magnetometerAvailable = false,
                                    gyroscopeAvailable = false,
                                    barometerAvailable = true,
                                    stepDetectorAvailable = false,
                                    stepCounterAvailable = true,
                                ),
                        ),
                    onGeneralSettingsChanged = {},
                )
            }
        }

        compose.onNodeWithText(label(R.string.phone_rec_settings_activity)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.phone_rec_settings_gps_track)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.phone_rec_settings_elevation_sensors)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.phone_rec_settings_feedback)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.phone_rec_weights_title)).assertDoesNotExist()
        compose.onNode(hasText("GPS preset:", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Elevation:", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Progress vibration:", substring = true)).assertDoesNotExist()

        compose.onNodeWithText(label(R.string.phone_rec_settings_activity)).performClick()
        compose.onNodeWithText(label(R.string.phone_rec_weights_title)).assertIsDisplayed()

        compose.onNodeWithText(label(R.string.phone_rec_settings_gps_track)).performClick()
        compose.onNode(hasText("Screen on:", substring = true)).assertIsDisplayed()

        compose.onNodeWithText(label(R.string.phone_rec_settings_elevation_sensors)).performClick()
        compose.onNode(hasText("Sensors:", substring = true)).assertIsDisplayed()

        compose.onNodeWithText(label(R.string.phone_rec_settings_feedback)).performClick()
        compose.onNode(hasText("Progress vibration:", substring = true)).assertIsDisplayed()
    }

    private fun label(resource: Int) = compose.activity.getString(resource)
}
