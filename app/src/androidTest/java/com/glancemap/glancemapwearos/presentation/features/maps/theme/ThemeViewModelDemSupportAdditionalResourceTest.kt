package com.glancemap.glancemapwearos.presentation.features.maps.theme

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
class ThemeViewModelDemSupportAdditionalResourceTest {
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
    fun demSummaryReportsUpstreamUnavailableTilesAsCompleteWithPartialCoverage() {
        assertEquals(
            "DEM download complete: 4 upstream tiles unavailable.",
            buildDemSummaryMessage(
                downloaded = 48,
                skipped = 2,
                missing = 4,
                failed = 0,
                remaining = 0,
                resources = resources,
            ),
        )
    }

    @Test
    fun demSummaryReportsCompleteSelectedSourceDownloadAsSuccessful() {
        assertEquals(
            "DEM download successful.",
            buildDemSummaryMessage(
                downloaded = 48,
                skipped = 2,
                missing = 0,
                failed = 0,
                remaining = 0,
                resources = resources,
            ),
        )
    }

    @Test
    fun demSummaryReportsFailedTilesAsIncomplete() {
        assertEquals(
            "DEM download incomplete: 1 failed.",
            buildDemSummaryMessage(
                downloaded = 1,
                skipped = 0,
                missing = 0,
                failed = 1,
                remaining = 0,
                resources = resources,
            ),
        )
    }
}
