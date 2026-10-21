package com.glancemap.glancemapcompanionapp.map

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PhoneOfflineComparisonResponsivenessTest {
    @Test
    fun diagnosticsNeverBlockTheUiOnEitherComparisonRenderer() {
        PhoneOfflineMapRendererRole.entries.forEach(::assertResponsive)
    }

    private fun assertResponsive(role: PhoneOfflineMapRendererRole) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var holder: PhoneOfflineMapsforgeView
        lateinit var mapView: MapView
        instrumentation.runOnMainSync {
            AndroidGraphicFactory.createInstance(instrumentation.targetContext.applicationContext)
            holder =
                PhoneOfflineMapsforgeView(
                    instrumentation.targetContext,
                    transparentBackground = role == PhoneOfflineMapRendererRole.COMPARISON,
                    rendererRole = role,
                    callbacks = callbacks(),
                )
            mapView = holder.getChildAt(0) as MapView
        }
        val drawingStarted = CountDownLatch(1)
        val releaseDrawing = CountDownLatch(1)
        val uiReturned = CountDownLatch(1)
        val uiFailure = AtomicReference<Throwable>()
        val drawing =
            thread(name = "mapsforge-draw-regression") {
                // LayerManager holds this exact monitor throughout expensive background drawing.
                synchronized(mapView.layerManager.layers) {
                    drawingStarted.countDown()
                    releaseDrawing.await(10, TimeUnit.SECONDS)
                }
            }
        try {
            assertTrue(drawingStarted.await(2, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post {
                try {
                    holder.onWindowFocusChanged(true)
                } catch (error: Throwable) {
                    uiFailure.set(error)
                } finally {
                    uiReturned.countDown()
                }
            }
            assertTrue(
                "$role UI must return while Mapsforge's drawing lock is still held",
                uiReturned.await(1, TimeUnit.SECONDS),
            )
            assertNull(uiFailure.get())
        } finally {
            releaseDrawing.countDown()
            drawing.join(2_000)
            instrumentation.runOnMainSync { holder.dispose() }
        }
    }

    private fun callbacks() =
        PhoneOfflineMapsforgeCallbacks(
            onCameraChanged = {},
            onLiveMapPositionChanged = {},
            onViewportChanged = {},
            onPoiSelected = {},
            onMapTap = {},
            onMapLongPress = {},
            onTwoFingerTap = { _, _ -> },
            onUserPan = {},
            onUserRotation = {},
            onCameraCommandHandled = {},
            onMapError = {},
            onLocationFollowUnavailable = {},
        )
}
