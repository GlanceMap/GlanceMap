package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class NavigateLiveDistanceSupportResourceTest {
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
    fun liveDistanceLabelUsesMetersForShortMetricDistances() {
        assertEquals("275 m", formatLiveDistanceLabel(meters = 275.4, isMetric = true, resources = resources))
    }

    @Test
    fun liveDistanceLabelUsesKilometersForLongMetricDistances() {
        assertEquals(
            "1.5 km",
            normalizeDecimalSeparator(formatLiveDistanceLabel(meters = 1_530.0, isMetric = true, resources = resources)),
        )
    }

    @Test
    fun liveDistanceLabelUsesFeetForShortImperialDistances() {
        assertEquals("902 ft", formatLiveDistanceLabel(meters = 275.0, isMetric = false, resources = resources))
    }

    @Test
    fun liveDistanceLabelUsesMilesForLongImperialDistances() {
        assertEquals(
            "1.0 mi",
            normalizeDecimalSeparator(formatLiveDistanceLabel(meters = 1_609.344, isMetric = false, resources = resources)),
        )
    }

    private fun normalizeDecimalSeparator(value: String): String = value.replace(',', '.')
}
