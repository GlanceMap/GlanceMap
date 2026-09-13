package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneOfflineBundlePersistenceTest {
    @Test
    fun expectedAndDownloadedDemCoverageSurviveReloadSeparately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("phone_oam_bundles", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val bundle =
            PhoneInstalledBundle(
                areaId = "dem-coverage-test",
                areaLabel = "DEM coverage test",
                mapFileName = "Area.map",
                poiFileName = "Area.poi",
                demTileIds = listOf("N45E006", "N45E007"),
                downloadedDemTileIds = listOf("N45E006"),
                unavailableDemTileIds = listOf("N45E007"),
                installedAtMillis = 1L,
            )

        try {
            PhoneOfflineBundleStore(context).upsert(bundle)

            assertEquals(bundle, PhoneOfflineBundleStore(context).find(bundle.areaId))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
