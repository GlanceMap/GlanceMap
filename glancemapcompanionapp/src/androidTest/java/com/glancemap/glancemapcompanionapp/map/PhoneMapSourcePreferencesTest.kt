package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneMapSourcePreferencesTest {
    @Test
    fun onlineProviderSurvivesAnOfflineRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = context.getSharedPreferences("phone_map_source", Context.MODE_PRIVATE)
        val preferences = PhoneMapSourcePreferences(context)
        storage.edit().clear().commit()

        try {
            preferences.saveOnline(PhoneOnlineMapSource.CYCLOSM)
            preferences.saveOffline(PhoneOfflineMap(File("placeholder-offline.map")))

            assertEquals(PhoneMapSourcePreferenceMode.OFFLINE, preferences.load().mode)
            assertEquals(PhoneOnlineMapSource.CYCLOSM, preferences.load().onlineSource)
            assertEquals(
                PhoneMapSourcePreferenceMode.ONLINE,
                preferences.restoreOnline().mode,
            )
            assertEquals(PhoneOnlineMapSource.CYCLOSM, preferences.load().onlineSource)
        } finally {
            storage.edit().clear().commit()
        }
    }
}
