package com.glancemap.glancemapcompanionapp.map

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TwoLevelTileCache
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PhoneMapsforgeTileCacheTest {
    @Test
    fun factoryUsesTheBudgetedCapacityWhenExternalStorageIsAvailableOrUnavailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "phone-mapsforge-cache-").toFile()

        try {
            instrumentation.runOnMainSync {
                AndroidGraphicFactory.createInstance(context.applicationContext)
                val mapView = MapView(context)
                try {
                    mapView.model.displayModel.setFixedTileSize(768)
                    val tileSize = mapView.model.displayModel.tileSize
                    val bytesPerPixel =
                        AndroidGraphicFactory.getBytesPerPixel(
                            AndroidGraphicFactory.INSTANCE.nonTransparentBitmapConfig,
                        )
                    val config =
                        phoneMapsforgeTileCacheConfig(
                            tileSize,
                            bytesPerPixel,
                            context.getSystemService(ActivityManager::class.java).memoryClass,
                            Runtime.getRuntime().maxMemory(),
                        )

                    listOf(directory, null).forEach { cacheDirectory ->
                        val cacheContext =
                            object : ContextWrapper(context) {
                                override fun getApplicationContext(): Context = this

                                override fun getExternalCacheDir(): File? = cacheDirectory

                                override fun getCacheDir(): File = File(directory, "internal").apply { mkdirs() }
                            }
                        val renderer =
                            PhoneMapsforgeRenderer(
                                context = cacheContext,
                                mapView = mapView,
                                role = PhoneOfflineMapRendererRole.BASE,
                                onMapBoundsChanged = {},
                                onRendererFailure = {},
                                onRuntimeChanged = {},
                            )
                        try {
                            val cache = renderer.createTileCache("budget-test")
                            try {
                                assertEquals(config.firstLevelTiles, cache.capacityFirstLevel)
                                assertTrue(
                                    cache.capacityFirstLevel * tileSize.toLong() * tileSize * bytesPerPixel <=
                                        config.memoryBudgetBytes,
                                )
                                assertTrue(cache is TwoLevelTileCache)
                            } finally {
                                cache.destroy()
                            }
                        } finally {
                            renderer.destroy()
                        }
                    }
                } finally {
                    mapView.destroyAll()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
