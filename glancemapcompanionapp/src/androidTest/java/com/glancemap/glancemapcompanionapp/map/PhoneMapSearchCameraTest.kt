package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PhoneMapSearchCameraTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun offlineSearchCentersOnceAndLaterUpdatesDoNotRestoreLocationFollow() {
        val activity = composeRule.activity
        val directory = Files.createTempDirectory(activity.cacheDir.toPath(), "phone-search-camera-").toFile()
        // This tests native camera commands independently of tile-file validity.
        val map = File(directory, "fixture.map").apply { writeText("invalid fixture") }
        val target = PhoneMapCoordinate(48.85, 2.35)
        val handled = CopyOnWriteArrayList<Long>()
        val camera = AtomicReference(defaultPhoneMapCamera)
        val visible = mutableStateOf(true)
        val state =
            mutableStateOf(
                PhoneOfflineMapSurfaceState(
                    map = PhoneOfflineMap(map),
                    themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
                    initialCamera = defaultPhoneMapCamera,
                    gpxOverlays = emptyList(),
                    pois = emptyList(),
                    mapMode = PhoneMapMode(follow = PhoneMapFollowMode.FREE),
                    cameraCommand = PhoneMapCameraCommand(1L, target = target),
                ),
            )
        try {
            composeRule.runOnUiThread { AndroidGraphicFactory.createInstance(activity.application) }
            composeRule.setContent {
                if (visible.value) {
                    offlineMapSurface(state.value, callbacks(camera, handled))
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { 1L in handled }
            composeRule.runOnIdle {
                assertEquals(target.latitude, camera.get().latitude, 0.00001)
                assertEquals(target.longitude, camera.get().longitude, 0.00001)
                assertTrue(camera.get().zoom > 0.0)
                state.value =
                    state.value.copy(
                        initialCamera = camera.get(),
                        location = PhoneMapLocation(51.0, 0.0, 10f, 1L),
                        hasLocationPermission = true,
                    )
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(listOf(1L), handled.toList())
                assertEquals(target.latitude, camera.get().latitude, 0.00001)
                assertEquals(target.longitude, camera.get().longitude, 0.00001)
                state.value = state.value.copy(cameraCommand = PhoneMapCameraCommand(2L, zoomDelta = 1))
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { 2L in handled }
            composeRule.runOnIdle {
                assertEquals(listOf(1L, 2L), handled.toList())
                assertEquals(target.latitude, camera.get().latitude, 0.00001)
            }
        } finally {
            composeRule.runOnIdle { visible.value = false }
            composeRule.waitForIdle()
            directory.deleteRecursively()
        }
    }

    private fun callbacks(
        camera: AtomicReference<PhoneMapCameraSnapshot>,
        handled: MutableList<Long>,
    ): PhoneOfflineMapsforgeCallbacks =
        PhoneOfflineMapsforgeCallbacks(
            onCameraChanged = camera::set,
            onLiveMapPositionChanged = {},
            onViewportChanged = {},
            onPoiSelected = {},
            onMapTap = {},
            onMapLongPress = {},
            onTwoFingerTap = { _, _ -> },
            onUserPan = {},
            onUserRotation = {},
            onCameraCommandHandled = { handled += it },
            onMapError = {},
            onLocationFollowUnavailable = {},
        )
}
