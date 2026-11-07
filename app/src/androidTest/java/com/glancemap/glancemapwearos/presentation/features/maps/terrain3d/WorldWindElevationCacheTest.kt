package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.core.maps.DemSignatureStore
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.presentation.features.maps.TerrainSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.BoundingBox
import java.io.File
import java.nio.ShortBuffer

@RunWith(AndroidJUnit4::class)
class WorldWindElevationCacheTest {
    @Test
    fun coverageClearReusesTheCompletedGridWithoutReloadingDem() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = File.createTempFile("worldwind-grid-", "", context.cacheDir)
            check(root.delete() && root.mkdir())
            val prefsName = "worldwind-grid-test-${root.name}"
            val isolated =
                object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = this

                    override fun getExternalFilesDir(type: String?): File = File(root, type ?: "files")

                    override fun getSharedPreferences(
                        name: String?,
                        mode: Int,
                    ): SharedPreferences = context.getSharedPreferences(prefsName, mode)
                }
            val demDir = File(root, "dem3").apply { mkdirs() }
            val demFile = File(demDir, "N45E006.hgt")
            demFile.writeBytes(ByteArray(8))
            val source =
                TerrainSource(
                    mapFile = File(root, "unused.map"),
                    themeFile = null,
                    externalTheme = null,
                    mapsforgeThemeName = null,
                    bundledThemeId = "",
                    demSource = DemSource.MAPSFORGE_DEM3,
                    textScale = 1f,
                    hillShadingEnabled = false,
                )
            val elevation = WorldWindElevation(isolated, source, BoundingBox(45.1, 6.1, 45.9, 6.9))
            try {
                val matrix =
                    elevation.coverage.tileMatrixSet.entries
                        .first()

                suspend fun fetch(): ShortArray {
                    val source = elevation.coverage.elevationSourceFactory.createElevationSource(matrix, 0, 0)
                    val buffer = source.asElevationDataFactory().fetchElevationData() as ShortBuffer
                    return ShortArray(buffer.remaining()).also { buffer.get(it) }
                }
                val first = fetch()
                assertEquals(1L, elevation.cacheStats.loads.get())
                elevation.coverage.clear()
                val second = fetch()
                assertArrayEquals(first, second)
                assertEquals(1L, elevation.gridCache.hits.get())
                assertEquals(1L, elevation.cacheStats.loads.get())
                demFile.writeBytes(byteArrayOf(0, 100, 0, 100, 0, 100, 0, 100))
                DemSignatureStore.markDirty(isolated)
                val refreshed = fetch()
                assertEquals(100.toShort(), refreshed.first())
                assertEquals(2L, elevation.cacheStats.loads.get())
            } finally {
                elevation.close()
                context.deleteSharedPreferences(prefsName)
                root.deleteRecursively()
            }
        }
}
