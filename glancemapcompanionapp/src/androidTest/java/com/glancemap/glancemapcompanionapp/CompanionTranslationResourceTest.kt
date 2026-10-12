package com.glancemap.glancemapcompanionapp

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CompanionTranslationResourceTest {
    @Test
    fun compiledResourcesPreserveSentenceFormattingAndPluralSpacing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val resources = context.createConfigurationContext(configuration).resources

        assertEquals(
            "First 30 min  •  1.5 km  •  +120 m climb",
            resources.getString(R.string.route_first_thirty_minutes_summary, "1.5 km", 120),
        )
        assertEquals(" • 1 saved snapshot", resources.getQuantityString(R.plurals.weather_saved_snapshots, 1, 1))
        assertEquals(" • 2 saved snapshots", resources.getQuantityString(R.plurals.weather_saved_snapshots, 2, 2))
        assertEquals(
            "Position sent; replayed 2 stored GPS points",
            resources.getQuantityString(R.plurals.tracking_replayed_gps_points, 2, "Position sent", 2),
        )
        assertEquals(
            "Received from watch\ntour.gpx",
            resources.getString(R.string.gpx_notification_expanded, "Received from watch", "tour.gpx"),
        )
    }
}
