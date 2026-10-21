package com.glancemap.glancemapwearos.presentation.features.download

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
class OamBundlePipelineResourceTest {
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
    fun largeDetailedDEMProgressIncludesAggregateTilePosition() {
        val progress =
            OamDownloadProgress("DOWNLOADING", "tile", 50L, 100L).withDemBatchContext(
                tileIndex = 123,
                tileCount = 506,
                tileId = "N04E038",
                sourceLabel = "Detailed",
                isLargeDetailedDownload = true,
                resources = resources,
            )

        assertEquals("Large detailed DEM 124/506 · N04E038", progress.detail)
        assertEquals(50L, progress.bytesDone)
        assertEquals(100L, progress.totalBytes)
    }
}
