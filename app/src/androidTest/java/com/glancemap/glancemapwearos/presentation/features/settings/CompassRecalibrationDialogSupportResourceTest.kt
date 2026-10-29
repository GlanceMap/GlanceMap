package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.presentation.features.navigate.CompassMarkerQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CompassRecalibrationDialogSupportResourceTest {
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
    fun resultButtonLabelSwitchesBetweenDoneAndRetry() {
        assertEquals("Done", recalibrationResultButtonLabel(succeeded = true, resources = resources))
        assertEquals("Try Again", recalibrationResultButtonLabel(succeeded = false, resources = resources))
    }

    @Test
    fun unsupportedBodyTextExplainsThatResetIsCustomOnly() {
        val body =
            compassRecalibrationBodyText(
                state =
                    CompassRecalibrationBodyState(
                        phase = CalibrationPhase.UNSUPPORTED,
                        providerType = CompassProviderType.GOOGLE_FUSED,
                        hasAnyHeadingSource = true,
                        initialQuality = CompassMarkerQuality.LOW,
                        resultQuality = CompassMarkerQuality.LOW,
                        qualityImproved = false,
                    ),
                resources = resources,
            )

        assertTrue(body.contains("Custom sensors"))
    }
}
