package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.model.MapViewPosition
import kotlin.math.roundToInt

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
    fun onlineAndOfflineMarkersUseTheSameFixedSizeWithAndWithoutTheCone() {
        AndroidGraphicFactory.createInstance(context.applicationContext as Application)
        PhoneMapMarkerStyle.entries.forEach { style ->
            val marker = PhoneOfflineLocationMarker(LatLong(46.0, 7.0), style, context)
            try {
                listOf(false, true).forEach { compassRenderable ->
                    val options =
                        LocationComponentOptions.builder(context).build().withPhoneLocationMarker(
                            PhoneMapSettings(markerStyle = style),
                            compassRenderable,
                        )
                    assertEquals(0.85f, options.minZoomIconScale(), 0f)
                    assertEquals(options.minZoomIconScale(), options.maxZoomIconScale(), 0f)
                    val drawable =
                        requireNotNull(
                            ContextCompat.getDrawable(context, phoneLocationMarkerDrawable(style, compassRenderable)),
                        )
                    marker.updateCompassAppearance(compassRenderable)
                    assertEquals(
                        (drawable.intrinsicWidth * options.minZoomIconScale()).roundToInt(),
                        marker.bitmap.width,
                    )
                    assertEquals(
                        (drawable.intrinsicHeight * options.minZoomIconScale()).roundToInt(),
                        marker.bitmap.height,
                    )
                    assertEquals(-marker.bitmap.width / 2, marker.horizontalOffset)
                    assertEquals(-marker.bitmap.height / 2, marker.verticalOffset)
                }
            } finally {
                marker.onDestroy()
            }
        }
    }

    @Test
    fun offlineDrawCancelsFramebufferScalingAndKeepsTheMarkerCentered() {
        AndroidGraphicFactory.createInstance(context.applicationContext as Application)
        val displayModel = DisplayModel()
        val position = MapViewPosition(displayModel)
        val marker =
            PhoneOfflineLocationMarker(LatLong(0.0, 0.0), PhoneMapMarkerStyle.DOT, context, position)
        marker.setDisplayModel(displayModel)
        val frameBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = AndroidGraphicFactory.createGraphicContext(android.graphics.Canvas(frameBitmap))
        try {
            listOf(false, true).forEach { compassRenderable ->
                marker.updateCompassAppearance(compassRenderable)
                val referenceBounds = alphaBounds(AndroidGraphicFactory.getBitmap(marker.bitmap))
                listOf(14.0, 14.25, 14.75, 14.99, 15.0).forEach { zoom ->
                    position.setZoom(zoom, false)
                    val zoomLevel = position.zoomLevel
                    val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
                    frameBitmap.eraseColor(Color.TRANSPARENT)
                    marker.draw(
                        BoundingBox(-1.0, -1.0, 1.0, 1.0),
                        zoomLevel,
                        canvas,
                        Point(mapSize / 2.0 - 160.0, mapSize / 2.0 - 160.0),
                        Rotation.NULL_ROTATION,
                    )
                    val bounds = alphaBounds(frameBitmap)
                    val framebufferScale =
                        (position.scaleFactor / MercatorProjection.zoomLevelToScaleFactor(zoomLevel)).toFloat()
                    assertEquals(referenceBounds.width().toFloat(), bounds.width() * framebufferScale, 4f)
                    assertEquals(referenceBounds.height().toFloat(), bounds.height() * framebufferScale, 4f)
                    assertEquals(Color.rgb(0, 122, 255), frameBitmap.getPixel(160, 160))
                }
            }
        } finally {
            canvas.destroy()
            frameBitmap.recycle()
            marker.onDestroy()
            position.destroy()
        }
    }

    private fun alphaBounds(bitmap: Bitmap): Rect {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) > 0) {
                val x = index % bitmap.width
                val y = index / bitmap.width
                bounds.left = minOf(bounds.left, x)
                bounds.top = minOf(bounds.top, y)
                bounds.right = maxOf(bounds.right, x + 1)
                bounds.bottom = maxOf(bounds.bottom, y + 1)
            }
        }
        return bounds
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
