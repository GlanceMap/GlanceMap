package com.glancemap.glancemapwearos

import android.content.res.Configuration
import android.text.Annotation
import android.text.Spanned
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class WatchTranslationResourceTest {
    @Test
    fun compiledEnglishResourcesPreserveArgumentsPluralsAndWhitespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val resources = context.createConfigurationContext(configuration).resources

        assertEquals(
            "Elevation 50% · 2/4",
            resources.getString(R.string.map_dem_download_progress, 50, 2, 4),
        )
        assertEquals(
            "1 file · 1.0 MB",
            resources.getQuantityString(R.plurals.settings_partial_files_summary, 1, 1, "1.0 MB"),
        )
        assertEquals(
            "3 files · 4.5 MB",
            resources.getQuantityString(R.plurals.settings_partial_files_summary, 3, 3, "4.5 MB"),
        )
        assertEquals(
            "REC: 3s on · 10s off",
            resources.getString(R.string.gps_profile_rec_summary, "3s", "10s"),
        )
        assertEquals(
            "Detailed terrain is not available for all of this map.\nUsing Standard terrain instead.",
            resources.getString(
                R.string.settings_is_not_available_for_all_of_this_map_using_instead,
                "Detailed terrain",
                "Standard terrain",
            ),
        )
        assertEquals(
            "Europe - 120 MB",
            resources.getString(R.string.download_area_size, "120 MB", "Europe"),
        )
        assertEquals(
            "DEM tile has invalid HGT size: 1 byte.",
            resources.getQuantityString(R.plurals.dem_dem_tile_has_invalid_hgt_size_bytes, 1, 1L),
        )
        assertEquals(
            "DEM tile has invalid HGT size: 2 bytes.",
            resources.getQuantityString(R.plurals.dem_dem_tile_has_invalid_hgt_size_bytes, 2, 2L),
        )
        assertEquals(
            "accuracy=good (3/3), noise=1 deg, response=fast, source match=100%",
            resources.getString(R.string.settings_accuracy_3_noise_deg_response_source_match, "good", "3", "1", "fast", "100"),
        )
        val styled = resources.getText(R.string.gpx_add_second_point_label) as Spanned
        assertEquals("Add 2nd point (B)", styled.toString())
        val superscript = styled.getSpans(0, styled.length, Annotation::class.java).single()
        assertEquals("superscript", superscript.key)
        assertEquals(5, styled.getSpanStart(superscript))
        assertEquals(7, styled.getSpanEnd(superscript))
    }
}
