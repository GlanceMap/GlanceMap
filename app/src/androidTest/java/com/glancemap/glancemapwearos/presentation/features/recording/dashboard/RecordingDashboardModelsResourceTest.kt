@file:Suppress("LargeClass", "LongMethod")

package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RecordingDashboardModelsResourceTest {
    private lateinit var previousLocale: Locale

    @Before
    fun useEnglishFormattingLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreFormattingLocale() {
        Locale.setDefault(previousLocale)
    }

    private val resources =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun metricPickerOptionsAreAlphabetical() {
        val labels =
            recordingMetricPickerOptionsForProfile(SettingsRepository.ACTIVITY_PROFILE_HIKE, resources = resources)
                .map { it.second }
        assertEquals(labels.sortedBy { it.lowercase() }, labels)
        assertTrue(labels.contains("Cadence (Avg)"))
        assertTrue(labels.contains("Cadence (Max)"))
        assertTrue(labels.contains("Heart rate (Max)"))
        assertTrue(labels.contains("Power (Avg)"))
        assertTrue(labels.contains("Power (Max)"))
        assertTrue(labels.contains("Speed (Max)"))
        assertTrue(labels.contains("Pace (Max)"))
    }

    @Test
    fun bikeMetricPickerExcludesStepsAndPace() {
        val options = recordingMetricPickerOptionsForProfile(SettingsRepository.ACTIVITY_PROFILE_BIKE, resources = resources)
        val metricIds = options.map { it.first }
        val labels = options.map { it.second }

        assertEquals(labels.sortedBy { it.lowercase() }, labels)
        assertTrue(SettingsRepository.RECORDING_METRIC_CADENCE in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_POWER in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_STEPS !in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_CURRENT_PACE !in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_AVERAGE_PACE !in metricIds)
        assertTrue(SettingsRepository.RECORDING_METRIC_MAX_PACE !in metricIds)
    }

    private companion object {
        private const val ONE_HOUR_WALK_SEGMENT_COUNT = 6
        private const val ONE_HOUR_WALK_LONGITUDE_DELTA = 0.045319
        private const val ONE_HOUR_BIKE_LONGITUDE_DELTA = 0.161697
    }
}
