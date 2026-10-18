package com.glancemap.glancemapcompanionapp.map

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PhoneMapCombinedGesturesTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun offlinePinchThenSlowRotationThenZoomOutWorksWithoutLiftingFingers() = exerciseGesture(false)

    @Test fun offlineRotationThenPinchWorksWithoutLiftingFingers() = exerciseGesture(true)

    @Test fun offlineCenteredPinchAndRotationKeepTheGpsCenter() = exerciseGesture(false, PhoneMapMode(), PhoneMapMarkerAnchor.CENTER)

    @Test fun offlineLowerAnchorPinchAndRotationKeepTheGpsCenter() = exerciseGesture(false, PhoneMapMode(), PhoneMapMarkerAnchor.LOWER)

    @Test fun offlineCompassPinchKeepsCenterAndDisablesFingerRotation() = exerciseGesture(false, PhoneMapMode(orientation = PhoneMapOrientation.HEADING_UP))

    @Test fun offlineBrowsingCanRotateDuringPinchAndResetNorthUpInPlace() = exerciseGesture(false, PhoneMapMode().toggleOfflineMode())

    @Test fun offlineBrowsingCanPinchAfterRotationAndResetNorthUpInPlace() = exerciseGesture(true, PhoneMapMode().toggleOfflineMode())

    private fun exerciseGesture(
        rotateFirst: Boolean,
        mode: PhoneMapMode = PhoneMapMode(follow = PhoneMapFollowMode.FREE),
        anchor: PhoneMapMarkerAnchor = PhoneMapMarkerAnchor.CENTER,
    ) {
        val activity = composeRule.activity
        val fixture = Files.createTempFile(activity.cacheDir.toPath(), "gesture-map-", ".map").toFile()
        // Camera gestures do not require a valid tile file.
        fixture.writeText("invalid map fixture")
        val rotations = CopyOnWriteArrayList<Float>()
        val measurements = AtomicInteger()
        val pans = AtomicInteger()
        val visible = mutableStateOf(true)
        val currentMode = mutableStateOf(mode)
        val callbacks =
            callbacks(rotations, measurements).copy(
                onUserPan = { pans.incrementAndGet() },
                onUserRotation = { bearing ->
                    rotations += bearing
                    currentMode.value = currentMode.value.withManualBearing(bearing)
                },
            )
        try {
            composeRule.runOnUiThread { AndroidGraphicFactory.createInstance(activity.application) }
            composeRule.setContent {
                if (visible.value) {
                    Box {
                        offlineMapSurface(
                            PhoneOfflineMapSurfaceState(
                                map = PhoneOfflineMap(fixture),
                                themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
                                initialCamera = defaultPhoneMapCamera,
                                measurementEnabled = true,
                                gpxOverlays = emptyList(),
                                pois = emptyList(),
                                mapMode = currentMode.value,
                                mapSettings = PhoneMapSettings(markerAnchor = anchor),
                                cameraCommand = null,
                            ),
                            callbacks,
                        )
                        if (mode.isOffline) {
                            PhoneMapModeButton(
                                mode = currentMode.value,
                                onClick = { currentMode.value = currentMode.value.toggleOrientation() },
                                onToggleOffline = { currentMode.value = currentMode.value.toggleOfflineMode() },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
            composeRule.waitUntil(5_000) { findMap(activity.window.decorView)?.width?.let { it > 0 } == true }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                val map = requireNotNull(findMap(activity.window.decorView))
                map.model.mapViewPosition.setZoom(12.0, false)
                val center = map.model.mapViewPosition.center

                fun centerOnScreen(): PhoneMapScreenPoint =
                    phoneMapsforgeScreenPointForMapPoint(
                        point = map.mapViewProjection.toPixels(center),
                        pivot = Point(map.width / 2.0, map.height / 2.0),
                        rotationDegrees = map.mapRotation.degrees.toDouble(),
                        offsetX = map.offsetX.toDouble(),
                        offsetY = map.offsetY.toDouble(),
                    )
                val screenCenter = centerOnScreen()
                val gesture =
                    MapGestureSequence(map, centerX = 0.35f, centerY = 0.3f) {
                        if (mode.follow == PhoneMapFollowMode.FOLLOW_LOCATION) {
                            assertEquals("The center must stay fixed during every gesture event", center, map.model.mapViewPosition.center)
                            val screen = centerOnScreen()
                            assertEquals("The anchor X must stay fixed", screenCenter.x, screen.x, 1f)
                            assertEquals("The anchor Y must stay fixed", screenCenter.y, screen.y, 1f)
                        }
                    }
                gesture.start()
                if (rotateFirst) gesture.rotate()
                gesture.pinch(1.4f)
                val zoomedIn = map.model.mapViewPosition.zoom
                assertTrue("Native pinch zoom must remain active", zoomedIn > 12.0)
                if (!rotateFirst) gesture.rotate()
                if (mode.allowsManualRotation) {
                    assertTrue("Small rotation must work during a pinch", map.mapRotation.degrees > 5f)
                } else {
                    assertEquals("Compass mode must reject finger rotation", 0f, map.mapRotation.degrees, 0.01f)
                }
                gesture.pinch(0.8f)
                assertTrue(
                    "Pinch zoom out must remain active after rotation",
                    map.model.mapViewPosition.zoom < zoomedIn,
                )
                gesture.end()
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                if (mode.allowsManualRotation) {
                    assertTrue(
                        "Slow rotations must reach the follow-mode callback",
                        rotations.any { kotlin.math.abs(shortestPhoneHeadingDelta(it, 0f)) > 5f },
                    )
                } else {
                    assertTrue("Compass rotation must not publish a manual bearing", rotations.isEmpty())
                }
                if (mode.follow == PhoneMapFollowMode.FOLLOW_LOCATION) {
                    assertEquals("A pinch must not detach follow mode", 0, pans.get())
                }
                assertEquals("Measurement must not steal a pinch or twist", 0, measurements.get())
            }
            if (mode.isOffline) {
                val position = composeRule.runOnIdle { requireNotNull(findMap(activity.window.decorView)).model.mapViewPosition.mapPosition }
                val description = activity.getString(R.string.map_mode_content_description, activity.getString(R.string.map_mode_offline))
                composeRule.onNodeWithContentDescription(description).performClick()
                composeRule.waitUntil(5_000) { findMap(activity.window.decorView)?.mapRotation?.degrees == 0f }
                composeRule.runOnIdle {
                    val map = requireNotNull(findMap(activity.window.decorView))
                    assertEquals("Center button must restore north up", 0f, map.mapRotation.degrees, 0.01f)
                    assertEquals("Reset must preserve the viewport", position.latLong, map.model.mapViewPosition.center)
                    assertEquals("Reset must preserve zoom", position.zoom, map.model.mapViewPosition.zoom, 0.001)
                    assertEquals(PhoneMapMode().toggleOfflineMode(), currentMode.value)
                }
            }
        } finally {
            composeRule.runOnIdle { visible.value = false }
            composeRule.waitForIdle()
            fixture.delete()
        }
    }

    private fun callbacks(
        rotations: MutableList<Float>,
        measurements: AtomicInteger,
    ) = PhoneOfflineMapsforgeCallbacks(
        onCameraChanged = {},
        onLiveMapPositionChanged = {},
        onViewportChanged = {},
        onPoiSelected = {},
        onMapTap = {},
        onMapLongPress = {},
        onTwoFingerTap = { _, _ -> measurements.incrementAndGet() },
        onUserPan = {},
        onUserRotation = { rotations += it },
        onCameraCommandHandled = {},
        onMapError = {},
        onLocationFollowUnavailable = {},
    )

    private fun findMap(view: View): MapView? =
        when (view) {
            is MapView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findMap(view.getChildAt(it)) }
            else -> null
        }
}

internal class MapGestureSequence(
    private val map: View,
    private val centerX: Float = 0.5f,
    private val centerY: Float = 0.5f,
    private val afterEvent: () -> Unit = {},
) {
    private val downTime = SystemClock.uptimeMillis()
    private var eventTime = downTime
    private var radius = map.width * 0.2f
    private var angle = 0f

    fun start() {
        dispatch(MotionEvent.ACTION_DOWN, 1)
        dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
    }

    fun rotate() {
        repeat(48) {
            angle += 0.25f
            dispatch(MotionEvent.ACTION_MOVE, 2)
        }
    }

    fun pinch(scale: Float) {
        val target = map.width * 0.2f * scale
        val step = (target - radius) / 20
        repeat(20) {
            radius += step
            dispatch(MotionEvent.ACTION_MOVE, 2)
        }
    }

    fun end() {
        dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        dispatch(MotionEvent.ACTION_MOVE, 1)
        dispatch(MotionEvent.ACTION_UP, 1)
    }

    private fun dispatch(
        action: Int,
        count: Int,
    ) {
        eventTime += 16
        val radians = Math.toRadians(angle.toDouble())
        val properties =
            Array(count) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coordinates =
            Array(count) { index ->
                val direction = if (index == 0) -1 else 1
                MotionEvent.PointerCoords().apply {
                    x = map.width * centerX + direction * radius * cos(radians).toFloat()
                    y = map.height * centerY + direction * radius * sin(radians).toFloat()
                    pressure = 1f
                    size = 1f
                }
            }
        val event =
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                count,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        try {
            map.dispatchTouchEvent(event)
            afterEvent()
        } finally {
            event.recycle()
        }
    }
}
