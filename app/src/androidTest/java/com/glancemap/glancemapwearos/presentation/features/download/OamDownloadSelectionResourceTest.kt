package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OamDownloadSelectionResourceTest {
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
    fun refugesInfoIsOffByDefault() {
        val selection = OamDownloadSelection()

        assertFalse(selection.includeRefugesInfo)
        assertEquals("Map + POI + Routing + Standard elevation", selection.label(resources = resources))
    }

    @Test
    fun refugesInfoOnlyCanDownload() {
        val selection =
            OamDownloadSelection(
                includeMap = false,
                includePoi = false,
                includeRouting = false,
                includeDem = false,
                includeRefugesInfo = true,
            )

        assertTrue(selection.canDownload)
        assertEquals("Refuges.info", selection.label(resources = resources))
    }
}
