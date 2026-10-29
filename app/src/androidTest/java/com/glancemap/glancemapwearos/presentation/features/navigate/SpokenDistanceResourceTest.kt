package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SpokenDistanceResourceTest {
    private val resources =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun englishGuidancePreservesDistanceThresholdsRoundingAndDecimalDigits() {
        listOf(
            752.0 to "750 meters",
            999.0 to "1000 meters",
            1000.0 to "1.0 kilometers",
            1500.0 to "1.5 kilometers",
            2250.0 to "2.3 kilometers",
            8250.0 to "8.3 kilometers",
            8350.0 to "8.4 kilometers",
            9950.0 to "10.0 kilometers",
            10000.0 to "10 kilometers",
            10500.0 to "11 kilometers",
            21000.0 to "21 kilometers",
            22000.0 to "22 kilometers",
            25000.0 to "25 kilometers",
        ).forEach { (meters, expected) ->
            assertEquals(
                "Continue straight for $expected",
                spokenContinueStraightText(meters, isMetric = true, resources = resources),
            )
        }
        listOf(0.5, 1.0, 1.5, 2.0, 21.0, 22.0, 25.0).forEach { miles ->
            val meters = miles / 0.000621371
            assertEquals(
                "Continue straight for $miles miles",
                spokenContinueStraightText(meters, isMetric = false, resources = resources),
            )
            assertEquals("in $miles miles", spokenDistancePrefix(meters, isMetric = false, resources = resources))
        }
        assertEquals("now", spokenDistancePrefix(14.0, isMetric = true, resources = resources))
        assertEquals("in 15 meters", spokenDistancePrefix(15.0, isMetric = true, resources = resources))
    }

    @Test
    fun compiledPatternsSupportSingularPluralAndVisibleDecimals() {
        listOf(
            Triple(R.string.navigation_for_kilometers, "for", "kilometer"),
            Triple(R.string.navigation_for_miles, "for", "mile"),
            Triple(R.string.navigation_in_miles, "in", "mile"),
        ).forEach { (id, prefix, unit) ->
            listOf(1, 2, 21, 22, 25).forEach { quantity ->
                val suffix = if (quantity == 1) unit else "${unit}s"
                assertEquals(
                    "$prefix $quantity $suffix",
                    formatSpokenDistance(quantity.toDouble(), 0, resources.getString(id), Locale.ENGLISH),
                )
            }
            listOf(1.0, 1.5, 2.0, 21.0).forEach { quantity ->
                assertEquals(
                    "$prefix $quantity ${unit}s",
                    formatSpokenDistance(quantity, 1, resources.getString(id), Locale.ENGLISH),
                )
            }
        }
    }

    @Test
    fun pluralSelectionUsesTheDecimalQuantityWithoutTruncation() {
        val pattern = "one{one:#} few{few:#} many{many:#} other{other:#}"
        val locale = Locale.forLanguageTag("ru")
        mapOf(1 to "one", 2 to "few", 21 to "one", 22 to "few", 25 to "many").forEach { (quantity, category) ->
            assertEquals("$category:$quantity", formatSpokenDistance(quantity.toDouble(), 0, pattern, locale))
        }
        assertEquals("other:1,0", formatSpokenDistance(1.0, 1, pattern, locale))
        assertEquals("other:1,5", formatSpokenDistance(1.5, 1, pattern, locale))
        assertEquals("other:21,5", formatSpokenDistance(21.5, 1, pattern, locale))
    }
}
