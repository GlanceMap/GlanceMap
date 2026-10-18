package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PhoneMapPerspectiveUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ) = super.getSharedPreferences("perspective_test_$name", mode)
        }

    @Before
    @After
    fun clearTestPreferences() {
        context
            .getSharedPreferences("phone_map_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun quickDisplayTogglePersistsAndTemporarilyDisablesForOfflineSelections() {
        val preferences = PhoneMapSettingsPreferences(context)
        val offline = PhoneOfflineMap(File("fixture.map"))
        val state =
            mutableStateOf(
                MapToolsLayerState(
                    base = PhoneMapSource.Online,
                    baseOnlineSource = PhoneOnlineMapSource.OPEN_TOPO,
                    offlineMaps = emptyList(),
                    onlineSources = PhoneMapRendererCatalog.availableOnlineSources(),
                    comparison = PhoneMapComparisonState(),
                    hasElevationData = false,
                    mapSettings = preferences.load(),
                ),
            )
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    layerQuickDisplay(state.value) { settings ->
                        state.value = state.value.copy(mapSettings = preferences.save(settings))
                    }
                }
            }
        }
        val toggle = composeRule.onNodeWithContentDescription(context.getString(R.string.map_tools_maps_tilted_view))
        toggle
            .performScrollTo()
            .assertIsNotSelected()
            .assertIsEnabled()
            .performClick()
            .assertIsSelected()
        composeRule.runOnIdle {
            assertTrue(PhoneMapSettingsPreferences(context).load().tiltedViewEnabled)
            state.value = state.value.copy(base = PhoneMapSource.Offline(offline))
        }
        toggle.assertIsNotEnabled().assertIsNotSelected()
        composeRule.runOnIdle {
            state.value = state.value.copy(base = PhoneMapSource.Online)
        }
        toggle.assertIsEnabled().assertIsSelected()
        composeRule.runOnIdle {
            state.value =
                state.value.copy(
                    comparison = PhoneMapComparisonState(PhoneMapComparisonLayer.Offline(offline)),
                )
        }
        toggle.assertIsNotEnabled().assertIsNotSelected()
        composeRule.runOnIdle { state.value = state.value.copy(comparison = PhoneMapComparisonState()) }
        toggle
            .assertIsEnabled()
            .assertIsSelected()
            .performClick()
            .assertIsNotSelected()
        composeRule.runOnIdle { assertFalse(PhoneMapSettingsPreferences(context).load().tiltedViewEnabled) }
    }

    @Test
    fun nativeTiltPreservesPositionAndSurvivesComparisonUpdatesAndBoundsFits() {
        val loaded = AtomicReference<MapLibreMap?>()
        val view = AtomicReference<MapView?>()
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                ensureMapLibreConfigured(context)
                MapView(context).apply {
                    view.set(this)
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        map.setStyle(Style.Builder().fromJson(FIXTURE_STYLE)) { loaded.set(map) }
                    }
                }
            })
        }
        try {
            composeRule.waitUntil(30_000) { loaded.get() != null }
            composeRule.runOnIdle {
                val map = requireNotNull(loaded.get())
                val start = PhoneMapCameraSnapshot(46.0, 7.0, 12.0, 60f)
                map.moveCamera(start.toMapLibreCameraUpdate(map))
                map.applyPhoneMapTilt(45.0)
                assertEquals(45.0, map.cameraPosition.tilt, 0.01)
                assertEquals(60.0, map.cameraPosition.bearing, 0.01)
                assertEquals(12.0, map.cameraPosition.zoom, 0.01)
                assertEquals(46.0, requireNotNull(map.cameraPosition.target).latitude, 0.000001)
                assertEquals(7.0, requireNotNull(map.cameraPosition.target).longitude, 0.000001)

                val comparison = start.copy(latitude = 47.0, longitude = 8.0, zoom = 13.0, bearingDegrees = 90f)
                map.moveCamera(comparison.toMapLibreCameraUpdate(map))
                assertEquals(45.0, map.cameraPosition.tilt, 0.01)
                assertEquals(47.0, requireNotNull(map.cameraPosition.target).latitude, 0.000001)
                assertEquals(8.0, requireNotNull(map.cameraPosition.target).longitude, 0.000001)
                val bounds =
                    LatLngBounds
                        .Builder()
                        .include(LatLng(46.9, 7.9))
                        .include(LatLng(47.1, 8.1))
                        .build()
                val fitted = requireNotNull(CameraUpdateFactory.newLatLngBounds(bounds, 24).getCameraPosition(map))
                assertEquals(45.0, fitted.tilt, 0.01)
                map.applyPhoneMapTilt(0.0)
                assertEquals(0.0, map.cameraPosition.tilt, 0.01)
                assertEquals(47.0, requireNotNull(map.cameraPosition.target).latitude, 0.000001)
                assertEquals(8.0, requireNotNull(map.cameraPosition.target).longitude, 0.000001)
            }
        } finally {
            composeRule.runOnIdle {
                view.get()?.apply {
                    onPause()
                    onStop()
                    onDestroy()
                }
            }
        }
    }

    private companion object {
        const val FIXTURE_STYLE =
            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background"}]}"""
    }
}
