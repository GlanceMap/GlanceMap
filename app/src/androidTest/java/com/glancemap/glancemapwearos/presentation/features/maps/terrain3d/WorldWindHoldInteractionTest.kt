package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import earth.worldwind.geom.AltitudeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class WorldWindHoldInteractionTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var window: WatchWorldWindWindow

    @Test
    fun graphicsContextIsPreservedWhenTheWatchPauses() {
        launchWindow()
        composeRule.runOnIdle { assertTrue(window.preserveEGLContextOnPause) }
    }

    @Test
    fun shortScreenOffPausesDrawingAndKeepsTheCameraOnWake() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.draws.get() > 0 }
        composeRule.runOnIdle {
            window.initialize(LatLong(45.0, 6.0), 45, 0f)
            window.zoomByStep(1)
            window.setPresetViewingAngle(80)
        }
        val range = window.lookAt.range
        val tilt = window.lookAt.tilt.inDegrees
        composeRule.runOnIdle { assertTrue(window.setScreenInteractive(false)) }
        val pausedDraws = window.draws.get()
        SystemClock.sleep(500)
        composeRule.runOnIdle {
            assertEquals(pausedDraws, window.draws.get())
            assertFalse(window.setScreenInteractive(false))
            assertTrue(window.setScreenInteractive(true))
            assertEquals(range, window.lookAt.range, 0.01)
            assertEquals(tilt, window.lookAt.tilt.inDegrees, 0.01)
        }
        composeRule.waitUntil(15_000) { window.draws.get() > pausedDraws }
    }

    @Test
    fun slightMovementStillStartsOrbitAndReleaseEndsIt() {
        launchWindow()
        val downTime = SystemClock.uptimeMillis()
        val slop = ViewConfiguration.get(window.context).scaledTouchSlop.toFloat()
        send(downTime, MotionEvent.ACTION_DOWN, 30f, 30f)
        send(downTime, MotionEvent.ACTION_MOVE, 30f + slop * 1.5f, 30f)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
        composeRule.runOnIdle {
            assertTrue(window.longPressOrbit)
            assertEquals(1L, window.holdMetrics.starts.get())
            assertEquals(1L, window.holdMetrics.activations.get())
            assertTrue(window.holdMetrics.activationDelayMaxMs.get() >= ViewConfiguration.getLongPressTimeout())
        }
        send(downTime, MotionEvent.ACTION_UP, 30f + slop * 1.5f, 30f)
        composeRule.runOnIdle {
            assertFalse(window.longPressOrbit)
            assertEquals(0L, window.holdMetrics.releaseCancellations.get())
        }
    }

    @Test
    fun touchStreamIsClaimedFromParentUntilReleaseSoRightwardOrbitIsNotIntercepted() {
        val requests = mutableListOf<Boolean>()
        composeRule.setContent {
            AndroidView(
                factory = { context ->
                    object : FrameLayout(context) {
                        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                            requests += disallowIntercept
                            super.requestDisallowInterceptTouchEvent(disallowIntercept)
                        }
                    }.also { parent ->
                        window = WatchWorldWindWindow(context, {}, {})
                        parent.addView(window)
                    }
                },
                modifier = Modifier.size(200.dp),
            )
        }
        composeRule.waitForIdle()
        val downTime = SystemClock.uptimeMillis()
        send(downTime, MotionEvent.ACTION_DOWN, 60f, 80f)
        composeRule.runOnIdle { assertEquals(listOf(true), requests) }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
        composeRule.runOnIdle { assertTrue(window.longPressOrbit) }
        send(downTime, MotionEvent.ACTION_MOVE, 140f, 80f)
        send(downTime, MotionEvent.ACTION_UP, 140f, 80f)
        composeRule.runOnIdle {
            assertEquals(listOf(true, false), requests)
            assertEquals(1L, window.holdMetrics.orbitEndReleases.get())
            assertEquals(0L, window.holdMetrics.orbitEndCancellations.get())
        }
    }

    @Test
    fun startCameraRestsOnTheDemSampleAndPanningKeepsThatHeight() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.engine.viewport.height > 0 }
        composeRule.runOnIdle {
            window.initialize(LatLong(45.0, 6.0), 45, 0f, groundElevation = 1_480.0)
            assertEquals(AltitudeMode.ABSOLUTE, window.lookAt.altitudeMode)
            assertEquals(1_480.0, window.lookAt.position.altitude, 0.01)
            assertTrue(window.engine.camera.position.altitude > 1_480.0)
        }
        val downTime = SystemClock.uptimeMillis()
        val distance = ViewConfiguration.get(window.context).scaledTouchSlop * 2f + 5f
        send(downTime, MotionEvent.ACTION_DOWN, 40f, 80f)
        send(downTime, MotionEvent.ACTION_MOVE, 40f + distance, 80f)
        composeRule.runOnIdle {
            assertEquals(AltitudeMode.ABSOLUTE, window.lookAt.altitudeMode)
            assertEquals(1_480.0, window.lookAt.position.altitude, 1.0)
        }
        send(downTime, MotionEvent.ACTION_CANCEL, 40f + distance, 80f)
    }

    @Test
    fun draggingBeyondToleranceDoesNotStartOrbit() {
        launchWindow()
        val downTime = SystemClock.uptimeMillis()
        val slop = ViewConfiguration.get(window.context).scaledTouchSlop.toFloat()
        send(downTime, MotionEvent.ACTION_DOWN, 30f, 30f)
        send(downTime, MotionEvent.ACTION_MOVE, 30f + slop * 3f, 30f)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
        composeRule.runOnIdle {
            assertFalse(window.longPressOrbit)
            assertEquals(1L, window.holdMetrics.movementCancellations.get())
            assertEquals(0L, window.holdMetrics.activations.get())
        }
        send(downTime, MotionEvent.ACTION_CANCEL, 30f + slop * 3f, 30f)
    }

    @Test
    fun panMovesFromCurrentCameraWithoutJumpingWhenTerrainIsUnavailable() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.engine.viewport.height > 0 }
        composeRule.runOnIdle { window.initialize(LatLong(45.0, 6.0), 60, 0f) }
        val downTime = SystemClock.uptimeMillis()
        send(downTime, MotionEvent.ACTION_DOWN, 50f, 80f)
        send(downTime, MotionEvent.ACTION_MOVE, 100f, 80f)
        send(downTime, MotionEvent.ACTION_MOVE, 150f, 80f)

        composeRule.runOnIdle {
            assertTrue(window.panCount.get() > 0)
            assertTrue(abs(window.lookAt.position.longitude.inDegrees - 6.0) > 0.00001)
            assertTrue(abs(window.lookAt.position.longitude.inDegrees - 6.0) < 0.1)
            assertTrue(abs(window.lookAt.position.latitude.inDegrees - 45.0) < 0.1)
            assertTrue(abs(window.lookAt.range - worldWindInitialRange()) < 1.0)
        }
        send(downTime, MotionEvent.ACTION_CANCEL, 150f, 80f)
    }

    @Test
    fun firstRecognizedPanMovementMovesTheCamera() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.engine.viewport.height > 0 }
        composeRule.runOnIdle { window.initialize(LatLong(45.0, 6.0), 60, 0f) }
        val downTime = SystemClock.uptimeMillis()
        val distance = 25f * window.resources.displayMetrics.density
        send(downTime, MotionEvent.ACTION_DOWN, 30f, 80f)
        send(downTime, MotionEvent.ACTION_MOVE, 30f + distance, 80f)
        composeRule.runOnIdle {
            assertTrue(window.panCount.get() > 0)
            assertTrue(abs(window.lookAt.position.longitude.inDegrees - 6.0) > 0.00001)
        }
        send(downTime, MotionEvent.ACTION_CANCEL, 30f + distance, 80f)
    }

    @Test
    fun movementBeyondHoldToleranceStartsPanning() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.engine.viewport.height > 0 }
        composeRule.runOnIdle { window.initialize(LatLong(45.0, 6.0), 60, 0f) }
        val downTime = SystemClock.uptimeMillis()
        val slop = ViewConfiguration.get(window.context).scaledTouchSlop.toFloat()
        val distance = 2f * slop + 1f
        send(downTime, MotionEvent.ACTION_DOWN, 30f, 80f)
        send(downTime, MotionEvent.ACTION_MOVE, 30f + distance, 80f)
        composeRule.runOnIdle {
            assertEquals(1L, window.panCount.get())
            assertFalse(window.longPressOrbit)
            assertTrue(abs(window.lookAt.position.longitude.inDegrees - 6.0) > 0.00001)
        }
        send(downTime, MotionEvent.ACTION_CANCEL, 30f + distance, 80f)
    }

    @Test
    fun panningAndLongPressOrbitCanAlternate() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.engine.viewport.height > 0 }
        composeRule.runOnIdle {
            window.initialize(LatLong(45.0, 6.0), 45, 0f)
            assertEquals(AltitudeMode.CLAMP_TO_GROUND, window.lookAt.altitudeMode)
        }
        val distance = ViewConfiguration.get(window.context).scaledTouchSlop * 2f + 5f
        var downTime = SystemClock.uptimeMillis()
        send(downTime, MotionEvent.ACTION_DOWN, 40f, 80f)
        send(downTime, MotionEvent.ACTION_MOVE, 40f + distance, 80f)
        send(downTime, MotionEvent.ACTION_UP, 40f + distance, 80f)
        composeRule.runOnIdle {
            assertEquals(1L, window.panCount.get())
            assertEquals(AltitudeMode.ABSOLUTE, window.lookAt.altitudeMode)
        }

        downTime = SystemClock.uptimeMillis()
        send(downTime, MotionEvent.ACTION_DOWN, 40f, 80f)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
        composeRule.runOnIdle { assertTrue(window.longPressOrbit) }
        val heading = window.lookAt.heading.inDegrees
        send(downTime, MotionEvent.ACTION_MOVE, 60f, 60f)
        composeRule.runOnIdle { assertTrue(abs(window.lookAt.heading.inDegrees - heading) > 0.1) }
        send(downTime, MotionEvent.ACTION_UP, 60f, 60f)

        val rangeAfterTilt = window.lookAt.range
        val tiltAfterTilt = window.lookAt.tilt.inDegrees
        downTime = SystemClock.uptimeMillis()
        send(downTime, MotionEvent.ACTION_DOWN, 40f, 80f)
        send(downTime, MotionEvent.ACTION_MOVE, 40f + distance, 80f)
        composeRule.runOnIdle {
            assertFalse(window.longPressOrbit)
            assertEquals(2L, window.panCount.get())
            assertEquals(rangeAfterTilt, window.lookAt.range, 1.0)
            assertEquals(tiltAfterTilt, window.lookAt.tilt.inDegrees, 0.1)
        }
        send(downTime, MotionEvent.ACTION_CANCEL, 40f + distance, 80f)
    }

    @Test
    fun movementAfterHoldTimeoutTiltsEvenIfHoldCallbackIsDelayed() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.engine.viewport.height > 0 }
        composeRule.runOnIdle { window.initialize(LatLong(45.0, 6.0), 45, 0f) }
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        val downTime = SystemClock.uptimeMillis() - timeout - 1
        val distance = ViewConfiguration.get(window.context).scaledTouchSlop * 2f + 5f
        send(downTime, MotionEvent.ACTION_DOWN, 40f, 100f, eventTime = downTime)
        send(downTime, MotionEvent.ACTION_MOVE, 40f, 100f - distance)
        composeRule.runOnIdle {
            assertTrue(window.longPressOrbit)
            assertEquals(0L, window.panCount.get())
            assertTrue(window.lookAt.tilt.inDegrees > 45.0)
        }
        send(downTime, MotionEvent.ACTION_UP, 40f, 100f - distance)
        val nextDown = SystemClock.uptimeMillis()
        send(nextDown, MotionEvent.ACTION_DOWN, 40f, 80f)
        send(nextDown, MotionEvent.ACTION_MOVE, 40f + distance, 80f)
        composeRule.runOnIdle {
            assertFalse(window.longPressOrbit)
            assertEquals(1L, window.panCount.get())
        }
        send(nextDown, MotionEvent.ACTION_CANCEL, 40f + distance, 80f)
    }

    @Test
    fun batchedMovementBeforeHoldTimeoutStillStartsPan() {
        launchWindow()
        composeRule.waitUntil(15_000) { window.engine.viewport.width > 0 && window.engine.viewport.height > 0 }
        composeRule.runOnIdle { window.initialize(LatLong(45.0, 6.0), 45, 0f) }
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        val downTime = SystemClock.uptimeMillis() - timeout - 50
        val distance = ViewConfiguration.get(window.context).scaledTouchSlop * 2f + 5f
        send(downTime, MotionEvent.ACTION_DOWN, 40f, 80f, eventTime = downTime)
        composeRule.runOnIdle {
            val move =
                MotionEvent.obtain(
                    downTime,
                    downTime + timeout - 10,
                    MotionEvent.ACTION_MOVE,
                    40f + distance,
                    80f,
                    0,
                )
            try {
                move.addBatch(downTime + timeout + 10, 40f + distance + 1, 80f, 1f, 1f, 0)
                window.onTouchEvent(move)
            } finally {
                move.recycle()
            }
            assertFalse(window.longPressOrbit)
            assertEquals(1L, window.panCount.get())
        }
        send(downTime, MotionEvent.ACTION_CANCEL, 40f + distance, 80f)
    }

    private fun launchWindow() {
        composeRule.setContent {
            AndroidView(
                factory = { context -> WatchWorldWindWindow(context, {}, {}).also { window = it } },
                modifier = Modifier.size(200.dp),
            )
        }
        composeRule.waitForIdle()
    }

    private fun send(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long = SystemClock.uptimeMillis(),
    ) {
        composeRule.runOnIdle {
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            try {
                window.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
