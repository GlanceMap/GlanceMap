package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RecordingStartWarningResourceTest {
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
    fun linkedExternalSourcesAreReportedAsNotConnectedYet() {
        val warning =
            resolveRecordingStartWarning(
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                speedSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                externalHeartRateAddress = "AA:BB:CC:DD:EE:01",
                externalRunPodAddress = "AA:BB:CC:DD:EE:02",
            )

        requireNotNull(warning)
        assertTrue(warning.unlinkedDevices.isEmpty())
        assertEquals(listOf(R.string.recording_heart_rate_strap, R.string.recording_external_sensor), warning.disconnectedDevices)
        assertTrue(warning.message(resources).contains("Recording will try to connect"))
    }

    @Test
    fun selectedExternalSourceWithoutALinkedDeviceIsExplicit() {
        val warning =
            resolveRecordingStartWarning(
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                speedSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                externalHeartRateAddress = null,
                externalRunPodAddress = null,
            )

        requireNotNull(warning)
        assertEquals(listOf(R.string.recording_heart_rate_strap), warning.unlinkedDevices)
        assertTrue(warning.disconnectedDevices.isEmpty())
        assertTrue(warning.message(resources).contains("No linked device"))
    }
}
