package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.location.LocationComponentOptions
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

@RunWith(AndroidJUnit4::class)
class PhoneLocationMarkerAppearanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun compassOptionsUseOneImmediateMarkerAndKeepThePlainMarkerWhenHeadingIsUnavailable() {
        var options = LocationComponentOptions.builder(context).build()
        PhoneMapMarkerStyle.entries.forEach { style ->
            val settings = PhoneMapSettings(markerStyle = style, gpsAccuracyCircleEnabled = true)
            options = options.withPhoneLocationMarker(settings, compassRenderable = true)
            assertFalse(options.compassAnimationEnabled())
            assertEquals(Color.TRANSPARENT, requireNotNull(options.foregroundTintColor()))
            assertEquals(Color.TRANSPARENT, requireNotNull(options.backgroundTintColor()))
            assertEquals(phoneLocationMarkerDrawable(style, compassRenderable = true), options.bearingDrawable())
            assertEquals(0.25f, options.accuracyAlpha(), 0f)

            options = options.withPhoneLocationMarker(settings, compassRenderable = false)
            assertEquals(null, options.foregroundTintColor())
            assertEquals(phoneLocationMarkerDrawable(style, compassRenderable = false), options.foregroundDrawable())
            assertFalse(options.compassAnimationEnabled())
        }
    }

    @Test
    fun coneIsBehindTheBlueDotAndOfflineHeadingChangesReuseTheSameBitmap() {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, R.drawable.map_location_marker_dot_compass))
        val bitmap = drawable.toBitmap()
        val center = bitmap.width / 2
        assertEquals(Color.rgb(0, 122, 255), bitmap.getPixel(center, center))
        val cone = bitmap.getPixel(center, bitmap.height / 5)
        assertTrue(Color.alpha(cone) > 0)
        assertTrue(Color.green(cone) > Color.blue(cone))
        assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width / 10, bitmap.height / 5)))

        AndroidGraphicFactory.createInstance(context.applicationContext as Application)
        val marker = PhoneOfflineLocationMarker(LatLong(46.0, 7.0), PhoneMapMarkerStyle.DOT, context)
        try {
            marker.updateCompassAppearance(compassRenderable = true)
            val initial = marker.bitmap
            val initialWidth = initial.width
            listOf(359f, 0f, 1f, 90f, 180f, 270f).forEach { heading ->
                marker.heading = heading
                marker.updateCompassAppearance(compassRenderable = true)
                assertSame(initial, marker.bitmap)
                assertEquals(heading, marker.heading, 0f)
            }
            assertEquals(-initial.width / 2, marker.horizontalOffset)
            assertEquals(-initial.height / 2, marker.verticalOffset)
            marker.updateCompassAppearance(compassRenderable = false)
            assertTrue(marker.bitmap.width < initialWidth)
        } finally {
            marker.onDestroy()
        }
    }
}
