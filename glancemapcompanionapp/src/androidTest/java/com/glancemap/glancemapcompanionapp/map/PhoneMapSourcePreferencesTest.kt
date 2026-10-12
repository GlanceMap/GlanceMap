package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneMapSourcePreferencesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun actualMapStartupExplainsTheFallbackWithoutOverwritingTheOfflinePreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = context.getSharedPreferences("phone_map_source", Context.MODE_PRIVATE)
        val preferences = PhoneMapSourcePreferences(context)
        val previous = preferences.load()
        try {
            val missingMap =
                PhoneOfflineMap(
                    File(context.cacheDir, "missing-startup-${System.nanoTime()}.map"),
                    contentRevision = "fixture",
                )
            preferences.saveOnline(PhoneOnlineMapSource.OPEN_TOPO)
            val saved = preferences.saveOffline(missingMap)
            composeRule.setContent {
                CompanionMapScreen(
                    gpxSources = emptyList(),
                    gpxSourcesLoading = false,
                    initiallyEnabledGpxId = null,
                    pois = emptyList(),
                    poiSources = emptyList(),
                    onPoiViewportChanged = {},
                    onPoiVisibilityChanged = {},
                    onPoiSourceVisibilityChanged = { _, _ -> },
                    onRenamePoiSource = { _, name -> name },
                    onDeletePoiSource = {},
                    onPoiDataChanged = {},
                    onRenameRoute = { _, _ -> },
                    onDeleteRoute = {},
                    onRouteSaved = {},
                    onOpenMenu = {},
                )
            }
            val message = context.getString(R.string.map_offline_preferred_map_missing)
            composeRule.waitUntil(10_000L) {
                composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(message).assertIsDisplayed()
            assertEquals(saved, preferences.load())
        } finally {
            storage
                .edit()
                .putBoolean("offline", previous.mode == PhoneMapSourcePreferenceMode.OFFLINE)
                .putString("offline_map_name", previous.offlineMapName)
                .putString("online_source", previous.onlineSource.name)
                .commit()
        }
    }

    @Test
    fun missingPreferredOfflineMapKeepsItsPreferenceAndCanBeRecoveredOnTheNextDiscovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = context.getSharedPreferences("phone_map_source", Context.MODE_PRIVATE)
        val preferences = PhoneMapSourcePreferences(context)
        storage.edit().clear().commit()
        try {
            val map = PhoneOfflineMap(File(context.cacheDir, "missing-fixture.map"), contentRevision = "fixture")
            preferences.saveOnline(PhoneOnlineMapSource.CYCLOSM)
            val saved = preferences.saveOffline(map)
            val missing = resolvePhoneMapOfflineStartupSelection(preferences.load(), PhoneMapSource.Online, emptyList())
            assertTrue(missing.missingMap)
            assertEquals(saved, preferences.load())
            val recovered =
                resolvePhoneMapOfflineStartupSelection(preferences.load(), PhoneMapSource.Online, listOf(map))
            assertFalse(recovered.missingMap)
            assertEquals(map, recovered.map)
            assertEquals(PhoneOnlineMapSource.CYCLOSM, preferences.load().onlineSource)
        } finally {
            storage.edit().clear().commit()
        }
    }

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
