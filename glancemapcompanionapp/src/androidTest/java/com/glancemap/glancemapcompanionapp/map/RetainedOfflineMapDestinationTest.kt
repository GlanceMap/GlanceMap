package com.glancemap.glancemapcompanionapp.map

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.filepicker.CompanionHomeArea
import com.glancemap.glancemapcompanionapp.filepicker.RetainedMapDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RetainedOfflineMapDestinationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun offlineMapKeepsItsNativeViewAndDefersCameraUpdatesUntilThePageReturns() {
        val area = mutableStateOf(CompanionHomeArea.MAP)
        val attempts = AtomicInteger()
        val first = PhoneMapCameraSnapshot(47.0, 11.0, 12.0)
        val latest = first.copy(latitude = 48.0, longitude = 12.0)
        val state =
            mutableStateOf(
                PhoneOfflineMapSurfaceState(
                    map = PhoneOfflineMap(File(composeRule.activity.cacheDir, "missing-retention-fixture.map")),
                    themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
                    initialCamera = first,
                    cameraOverride = first,
                    gpxOverlays = emptyList(),
                    pois = emptyList(),
                    mapMode = PhoneMapMode(follow = PhoneMapFollowMode.FREE),
                    cameraCommand = null,
                ),
            )
        composeRule.runOnUiThread { AndroidGraphicFactory.createInstance(composeRule.activity.application) }
        composeRule.setContent {
            MaterialTheme {
                RetainedMapDestination(
                    area = area.value,
                    mapContent = { visible ->
                        offlineMapSurface(state.value, callbacks(attempts), isVisible = visible)
                    },
                ) {
                    if (area.value != CompanionHomeArea.MAP) Text(area.value.name)
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            attempts.get() > 0 &&
                findMap(composeRule.activity.window.decorView)
                    ?.model
                    ?.mapViewPosition
                    ?.center
                    ?.latitude == first.latitude
        }
        composeRule.waitForIdle()
        val initial = requireNotNull(findMap(composeRule.activity.window.decorView))

        composeRule.runOnIdle { area.value = CompanionHomeArea.MISSION_PLAN }
        composeRule.waitForIdle()
        val attemptsBeforeHiddenUpdate = attempts.get()
        composeRule.runOnIdle { state.value = state.value.copy(cameraOverride = latest) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(first.latitude, initial.model.mapViewPosition.center.latitude, 0.0)
            assertEquals(attemptsBeforeHiddenUpdate, attempts.get())
            area.value = CompanionHomeArea.MAP
        }
        composeRule.waitUntil(
            timeoutMillis = 5_000,
        ) { initial.model.mapViewPosition.center.latitude == latest.latitude }
        composeRule.runOnIdle {
            assertSame(initial, findMap(composeRule.activity.window.decorView))
            assertEquals(latest.longitude, initial.model.mapViewPosition.center.longitude, 0.0)
        }
    }

    private fun findMap(view: View): MapView? =
        when (view) {
            is MapView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findMap(view.getChildAt(it)) }
            else -> null
        }

    private fun callbacks(attempts: AtomicInteger): PhoneOfflineMapsforgeCallbacks =
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
            onMapError = { attempts.incrementAndGet() },
            onLocationFollowUnavailable = {},
        )
}
