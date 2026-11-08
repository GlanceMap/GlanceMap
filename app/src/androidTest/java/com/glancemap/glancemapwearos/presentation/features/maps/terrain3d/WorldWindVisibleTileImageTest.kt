package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import earth.worldwind.layer.mercator.MercatorTileFactory
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.render.image.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WorldWindVisibleTileImageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun screenOffCancelsPendingImageryAndWakeRetriesIt() {
        val visible = AtomicReference<WorldWindVisibleTiles?>(null)
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val factory =
            object : MercatorTileFactory {
                override val contentType = "test"

                override fun getImageSource(
                    x: Int,
                    y: Int,
                    z: Int,
                ): ImageSource =
                    ImageSource.fromImageFactory(
                        object : ImageSource.ImageFactory {
                            override suspend fun createBitmap(): Bitmap {
                                started.incrementAndGet()
                                try {
                                    gate.await()
                                } catch (error: CancellationException) {
                                    cancelled.incrementAndGet()
                                    throw error
                                }
                                return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                            }
                        },
                    )
            }
        lateinit var window: WatchWorldWindWindow
        composeRule.setContent {
            AndroidView(
                factory = { context ->
                    WatchWorldWindWindow(context, {}, {}).also {
                        window = it
                        it.engine.layers.clearLayers()
                        val image = MercatorTiledImageLayer.buildTiledSurfaceImage(factory, maxZoom = 8, minZoom = 0)
                        it.engine.layers.addLayer(MercatorTiledImageLayer("test", WorldWindVisibleTileImage(image, visible)))
                        it.initialize(LatLong(45.0, 6.0), 45, 0f)
                    }
                },
                modifier = Modifier.size(200.dp),
            )
        }
        composeRule.waitUntil(30_000) { started.get() > 0 }
        composeRule.runOnIdle { window.setScreenInteractive(false) }
        composeRule.waitUntil(5_000) { cancelled.get() > 0 }
        gate.complete(Unit)
        composeRule.runOnIdle {
            visible.set(null)
            window.setScreenInteractive(true)
        }
        composeRule.waitUntil(30_000) { visible.get()?.let { it.total > 0 && it.ready == it.total } == true }
    }

    @Test
    fun failedVisibleTilesAreReported() {
        val visible = AtomicReference<WorldWindVisibleTiles?>(null)
        val shouldFail = AtomicBoolean(true)
        val factory =
            object : MercatorTileFactory {
                override val contentType = "test"

                override fun getImageSource(
                    x: Int,
                    y: Int,
                    z: Int,
                ): ImageSource {
                    val fail = shouldFail.get()
                    return ImageSource.fromImageFactory(
                        object : ImageSource.ImageFactory {
                            override suspend fun createBitmap(): Bitmap? = if (fail) null else Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                        },
                    )
                }
            }
        lateinit var window: WatchWorldWindWindow
        composeRule.setContent {
            AndroidView(
                factory = { context ->
                    WatchWorldWindWindow(context, {}, {}).also {
                        window = it
                        it.engine.layers.clearLayers()
                        val image = MercatorTiledImageLayer.buildTiledSurfaceImage(factory, maxZoom = 8, minZoom = 0)
                        it.engine.layers.addLayer(MercatorTiledImageLayer("test", WorldWindVisibleTileImage(image, visible)))
                        it.initialize(LatLong(45.0, 6.0), 45, 0f)
                    }
                },
                modifier = Modifier.size(200.dp),
            )
        }
        composeRule.waitUntil(30_000) { (visible.get()?.failed ?: 0) > 0 }
        shouldFail.set(false)
        composeRule.runOnIdle { window.initialize(LatLong(-20.0, 90.0), 45, 0f) }
        composeRule.waitUntil(30_000) {
            visible.get()?.let { it.total > 0 && it.failed == 0 && it.ready == it.total } == true
        }
    }

    @Test
    fun visibleProgressTracksLoadingAndANewCameraView() {
        val visible = AtomicReference<WorldWindVisibleTiles?>(null)
        val gate = AtomicReference(CompletableDeferred<Unit>())
        val decodedImages = AtomicInteger()
        val factory =
            object : MercatorTileFactory {
                override val contentType = "test"

                override fun getImageSource(
                    x: Int,
                    y: Int,
                    z: Int,
                ): ImageSource {
                    val currentGate = gate.get()
                    return ImageSource.fromImageFactory(
                        object : ImageSource.ImageFactory {
                            override suspend fun createBitmap(): Bitmap {
                                currentGate.await()
                                decodedImages.incrementAndGet()
                                return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                            }
                        },
                    )
                }
            }
        lateinit var window: WatchWorldWindWindow
        composeRule.setContent {
            AndroidView(
                factory = { context ->
                    WatchWorldWindWindow(context, {}, {}).also {
                        window = it
                        it.engine.layers.clearLayers()
                        val image = MercatorTiledImageLayer.buildTiledSurfaceImage(factory, maxZoom = 8, minZoom = 0)
                        it.engine.layers.addLayer(MercatorTiledImageLayer("test", WorldWindVisibleTileImage(image, visible)))
                        it.initialize(LatLong(45.0, 6.0), 45, 0f)
                    }
                },
                modifier = Modifier.size(200.dp),
            )
        }
        composeRule.waitUntil(30_000) { visible.get()?.let { it.total > 0 && it.ready < it.total } == true }
        gate.get().complete(Unit)
        composeRule.waitUntil(30_000) { visible.get()?.let { it.total > 0 && it.ready == it.total } == true }

        SystemClock.sleep(1_000)
        val beforePause = decodedImages.get()
        composeRule.runOnIdle {
            window.setScreenInteractive(false)
            visible.set(null)
            window.setScreenInteractive(true)
        }
        composeRule.waitUntil(30_000) { visible.get()?.let { it.total > 0 && it.ready == it.total } == true }
        assertEquals(beforePause, decodedImages.get())

        gate.set(CompletableDeferred())
        composeRule.runOnIdle { window.initialize(LatLong(-20.0, 90.0), 45, 0f) }
        composeRule.waitUntil(30_000) { visible.get()?.let { it.total > 0 && it.ready < it.total } == true }
        assertTrue(visible.get()!!.ready < visible.get()!!.total)
        gate.get().complete(Unit)
        composeRule.waitUntil(30_000) { visible.get()?.let { it.total > 0 && it.ready == it.total } == true }
    }
}
