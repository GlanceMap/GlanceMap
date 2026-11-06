package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemCacheStats
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReliefDemCacheTelemetryTest {
    @Test
    fun repositoryReportsRetainedBytesAfterEvictionAndClear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File.createTempFile("dem-telemetry-", "", context.cacheDir)
        check(root.delete() && root.mkdir())
        try {
            File(root, "N45E006.hgt").writeBytes(ByteArray(8))
            File(root, "N45E007.hgt").writeBytes(ByteArray(18))
            val stats = ReliefDemCacheStats()
            val repository = ReliefDemRepository(listOf(root), "Terrain3DTest", 2, stats, 8L)

            assertNotNull(repository.loadDemTileFor(45.5, 6.5))
            assertEquals(8L, stats.retainedBytes.get())
            assertNotNull(repository.loadDemTileFor(45.5, 7.5))
            assertEquals(18L, stats.retainedBytes.get())
            assertEquals(18L, stats.peakRetainedBytes.get())
            repository.clear()
            assertEquals(0L, stats.retainedBytes.get())
            assertEquals(18L, stats.peakRetainedBytes.get())
        } finally {
            root.deleteRecursively()
        }
    }
}
