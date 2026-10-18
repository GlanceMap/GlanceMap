package com.glancemap.glancemapcompanionapp.map

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhoneOnlineCenteredGesturesTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun centeredPinchAndRotationUseTheCameraAnchor() = exercise(compass = false, texture = false)

    @Test fun compassComparisonAllowsZoomAndRejectsFingerRotationAtTheLowerAnchor() = exercise(compass = true, texture = true)

    @Test fun offlineBrowsingAllowsPinchAndRotationWithMapLibre() = exercise(compass = false, texture = false, offline = true)

    private fun exercise(
        compass: Boolean,
        texture: Boolean,
        offline: Boolean = false,
    ) {
        val activity = composeRule.activity
        val mode =
            if (offline) {
                PhoneMapMode().toggleOfflineMode()
            } else {
                PhoneMapMode(orientation = if (compass) PhoneMapOrientation.HEADING_UP else PhoneMapOrientation.NORTH_UP)
            }
        val visible = mutableStateOf(true)
        val readyMap = AtomicReference<MapLibreMap>()
        val viewRef = AtomicReference<MapView>()
        lateinit var gesture: MapGestureSequence
        composeRule.runOnUiThread { MapLibre.getInstance(activity) }
        try {
            composeRule.setContent {
                if (visible.value) {
                    AndroidView(factory = { context ->
                        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(texture)).apply {
                            viewRef.set(this)
                            onCreate(Bundle())
                            onStart()
                            onResume()
                            getMapAsync { map ->
                                val gestures = PhoneOnlineCenteredGestures(this, { map }, { mode })
                                setOnTouchListener { _, event ->
                                    gestures.onTouch(event)
                                    false
                                }
                                map.uiSettings.setDisableRotateWhenScaling(false)
                                map.setStyle(Style.Builder().fromJson(EMPTY_STYLE)) { readyMap.set(map) }
                            }
                        }
                    })
                }
            }
            composeRule.waitUntil(10_000) { readyMap.get() != null && (viewRef.get()?.height ?: 0) > 0 }
            composeRule.runOnIdle {
                val map = readyMap.get()
                val view = viewRef.get()
                val top = if (texture) (view.height * 0.64f).toInt() else 0
                map.setPadding(0, top, 0, 0)
                val gps = LatLng(46.0, 7.0)
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition
                            .Builder()
                            .target(gps)
                            .zoom(12.0)
                            .bearing(0.0)
                            .build(),
                    ),
                )
                gesture =
                    MapGestureSequence(view, centerX = 0.3f, centerY = 0.3f) {
                        if (mode.follow == PhoneMapFollowMode.FOLLOW_LOCATION) {
                            val point = map.projection.toScreenLocation(gps)
                            assertEquals("GPS X stays fixed throughout the gesture", view.width / 2f, point.x, 1f)
                            assertEquals("GPS Y stays fixed throughout the gesture", (view.height + top) / 2f, point.y, 1f)
                        }
                    }
                gesture.start()
                gesture.pinch(1.4f)
            }
            composeRule.waitUntil(10_000) { readyMap.get().cameraPosition.zoom > 12.0 }
            composeRule.runOnIdle { gesture.rotate() }
            if (!compass) {
                composeRule.waitUntil(10_000) {
                    abs(shortestPhoneHeadingDelta(readyMap.get().cameraPosition.bearing.toFloat(), 0f)) > 5f
                }
            }
            composeRule.runOnIdle {
                val map = readyMap.get()
                assertTrue("Pinch must change zoom", map.cameraPosition.zoom > 12.0)
                if (compass) {
                    assertEquals("Compass must keep ownership of bearing", 0.0, map.cameraPosition.bearing, 0.01)
                } else {
                    assertTrue("Manual rotation must work", abs(shortestPhoneHeadingDelta(map.cameraPosition.bearing.toFloat(), 0f)) > 5f)
                }
                gesture.end()
            }
        } finally {
            composeRule.runOnIdle { visible.value = false }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                viewRef.get()?.apply {
                    onPause()
                    onStop()
                    onDestroy()
                }
            }
        }
    }

    private companion object {
        const val EMPTY_STYLE = "{\"version\":8,\"sources\":{},\"layers\":[]}"
    }
}
