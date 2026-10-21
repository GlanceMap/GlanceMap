package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

@RunWith(AndroidJUnit4::class)
class PhoneOfflineCompassDisplayFrameTest {
    @Test
    fun markerTurnsOnTheDisplayFrameWhileBackgroundLayersArePaused() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var view: PhoneOfflineMapsforgeMapView
        instrumentation.runOnMainSync {
            AndroidGraphicFactory.createInstance(context.applicationContext as Application)
            view = PhoneOfflineMapsforgeMapView(context, transparentBackground = false, onAndroidDrawObserved = {})
            view.layerManager.pause()
        }
        view.layerManager.awaitPausing()
        instrumentation.runOnMainSync {
            val origin = LatLong(0.0, 0.0)
            val marker =
                PhoneOfflineLocationMarker(
                    origin,
                    PhoneMapMarkerStyle.TRIANGLE,
                    context,
                    view.model.mapViewPosition,
                    displayFrameOwner = view,
                )
            val frame = Bitmap.createBitmap(320, 440, Bitmap.Config.ARGB_8888)
            try {
                view.layout(0, 0, frame.width, frame.height)
                view.model.mapViewPosition.setMapPosition(MapPosition(origin, 14.0, Rotation.NULL_ROTATION), false)
                view.model.frameBufferModel.setMapPosition(view.model.mapViewPosition.mapPosition)
                view.layerManager.layers.add(marker)
                assertSame(marker, view.locationMarker)
                val canvas = Canvas(frame)
                marker.heading = 0f
                view.draw(canvas)
                val before = frame.copy(Bitmap.Config.ARGB_8888, false)
                val firstDraws = marker.drawCalls
                assertEquals(1, firstDraws)
                assertEquals("bitmap_drawn", marker.lastDrawResult)

                marker.heading = 90f
                view.draw(canvas)
                assertEquals(firstDraws + 1, marker.drawCalls)
                assertFalse("heading changes the image without any background layer frame", frame.sameAs(before))
                before.recycle()

                view.layerManager.layers.remove(marker)
                assertNull(view.locationMarker)
                view.draw(canvas)
                assertEquals(firstDraws + 1, marker.drawCalls)
            } finally {
                marker.onDestroy()
                view.destroyAll()
                frame.recycle()
            }
        }
    }
}
