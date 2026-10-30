package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.trailcore.oam.OamDownloadCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneMapDataOperationPersistenceTest {
    private val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ) = super.getSharedPreferences("map_data_test_$name", mode)
        }

    @Test
    fun localAndCatalogMapSelectionsSurvivePauseAndReload() {
        val area = OamDownloadCatalog.areas.first()
        val localMap = PhoneOfflineMap(File("/maps/Custom Alps.map"))
        val knownMap = PhoneOfflineMap(File("/maps/known.map"))
        val bundle = PhoneInstalledBundle(area.id, area.region, knownMap.displayName, "", installedAtMillis = 1)
        val selections =
            listOf(
                phoneMapDataSelection(localMap, null, true, false, PhoneOfflineDemSource.DETAILED),
                phoneMapDataSelection(knownMap, bundle, false, true, PhoneOfflineDemSource.STANDARD),
            )
        val operation =
            PhoneOfflineBundleOperation(
                selections = selections,
                nextSelectionIndex = 1,
                status = PhoneOfflineBundleOperationStatus.PAUSED,
            )
        val store = PhoneOfflineBundleOperationStore(context)
        try {
            store.save(operation)
            assertEquals(operation, PhoneOfflineBundleOperationStore(context).load())
        } finally {
            store.clear()
        }
    }
}
