package com.glancemap.glancemapcompanionapp.map

import android.app.Dialog
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PhoneOfflineMapsforgeWindowFocusTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingRendererWorkRetriesWhenDialogReturnsWindowFocus() {
        val activity = composeRule.activity
        val decorView = activity.window.decorView
        val directory = Files.createTempDirectory(activity.cacheDir.toPath(), "phone-mapsforge-focus-").toFile()
        val firstMap = File(directory, "first.map").apply { writeText("invalid test map") }
        val secondMap = File(directory, "second.map").apply { writeText("invalid test map") }
        val attempts = AtomicInteger()
        val state = mutableStateOf(surfaceState(PhoneOfflineMap(firstMap)))
        var dialog: Dialog? = null

        try {
            composeRule.runOnUiThread {
                AndroidGraphicFactory.createInstance(activity.application)
            }
            composeRule.setContent {
                offlineMapSurface(state.value, callbacks(attempts))
            }
            // Invalid fixtures expose renderer attempts through the normal error callback.
            composeRule.waitUntil(timeoutMillis = 5_000) {
                attempts.get() > 0 && decorView.hasWindowFocus()
            }
            composeRule.waitForIdle()
            val initialAttempts = attempts.get()

            composeRule.runOnIdle {
                dialog =
                    Dialog(activity).apply {
                        setContentView(View(activity))
                        show()
                    }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                !decorView.hasWindowFocus()
            }

            composeRule.runOnIdle {
                assertFalse(decorView.hasWindowFocus())
                state.value = state.value.copy(map = PhoneOfflineMap(secondMap))
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(initialAttempts, attempts.get())
                requireNotNull(dialog).dismiss()
            }

            composeRule.waitUntil(timeoutMillis = 5_000) { attempts.get() > initialAttempts }
            composeRule.runOnIdle {
                assertTrue(decorView.hasWindowFocus())
            }
        } finally {
            composeRule.runOnUiThread { dialog?.dismiss() }
            directory.deleteRecursively()
        }
    }

    private fun surfaceState(map: PhoneOfflineMap): PhoneOfflineMapSurfaceState =
        PhoneOfflineMapSurfaceState(
            map = map,
            themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
            initialCamera = defaultPhoneMapCamera,
            gpxOverlays = emptyList(),
            pois = emptyList(),
            mapMode = PhoneMapMode(),
            cameraCommand = null,
        )

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
