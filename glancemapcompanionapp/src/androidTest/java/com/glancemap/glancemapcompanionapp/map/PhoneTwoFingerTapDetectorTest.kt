package com.glancemap.glancemapcompanionapp.map

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneTwoFingerTapDetectorTest {
    @Test
    fun handleDragOwnsTheStreamAndCancelIsReported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var cancelled = false
        var moved = 0
        var starts = 0
        val detector =
            PhoneTwoFingerTapDetector(
                context = context,
                onTwoFingerTap = { _, _, _, _ -> },
                measurementHandleAt = { x, _ -> 0.takeIf { x == 10f } },
                onMeasurementPointMove = { _, _, _ -> moved += 1 },
                onMeasurementGestureStart = { starts += 1 },
                onMeasurementPointDragEnd = { wasCancelled -> cancelled = wasCancelled },
            )

        singleEvent(MotionEvent.ACTION_DOWN, 10f, 10f).consume { event ->
            assertTrue(detector.onTouchEvent(event))
        }
        singleEvent(MotionEvent.ACTION_MOVE, 30f, 30f).consume { event ->
            assertTrue(detector.onTouchEvent(event))
        }
        singleEvent(MotionEvent.ACTION_CANCEL, 30f, 30f).consume { event ->
            assertTrue(detector.onTouchEvent(event))
        }

        assertEquals(1, starts)
        assertEquals(1, moved)
        assertTrue(cancelled)
    }

    @Test
    fun ordinarySinglePointerGestureRemainsWithTheNativeMap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector =
            PhoneTwoFingerTapDetector(
                context = context,
                onTwoFingerTap = { _, _, _, _ -> },
                measurementHandleAt = { _, _ -> null },
            )

        singleEvent(MotionEvent.ACTION_DOWN, 10f, 10f).consume { event ->
            assertFalse(detector.onTouchEvent(event))
        }
        singleEvent(MotionEvent.ACTION_MOVE, 30f, 30f).consume { event ->
            assertFalse(detector.onTouchEvent(event))
        }
        singleEvent(MotionEvent.ACTION_UP, 30f, 30f).consume { event ->
            assertFalse(detector.onTouchEvent(event))
        }
    }

    @Test
    fun recognizedTwoFingerMeasurementOwnsPointerChangesAfterNativeCancel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var starts = 0
        var measurements = 0
        val detector =
            PhoneTwoFingerTapDetector(
                context = context,
                onTwoFingerTap = { _, _, _, _ -> measurements += 1 },
                onTwoFingerMove = { _, _, _, _ -> measurements += 1 },
                onMeasurementGestureStart = { starts += 1 },
            )

        singleEvent(MotionEvent.ACTION_DOWN, 10f, 10f).consume { event ->
            assertFalse(detector.onTouchEvent(event))
        }
        multiEvent(MotionEvent.ACTION_POINTER_DOWN, 10f, 10f, 20f, 20f, pointerActionIndex = 1).consume { event ->
            assertFalse(detector.onTouchEvent(event))
        }
        SystemClock.sleep(220L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(1, starts)
        assertEquals(1, measurements)
        multiEvent(MotionEvent.ACTION_MOVE, 12f, 12f, 24f, 24f).consume { event ->
            assertTrue(detector.onTouchEvent(event))
        }
        multiEvent(MotionEvent.ACTION_POINTER_UP, 12f, 12f, 24f, 24f, pointerActionIndex = 1).consume { event ->
            assertTrue(detector.onTouchEvent(event))
        }
        singleEvent(MotionEvent.ACTION_UP, 12f, 12f).consume { event ->
            assertTrue(detector.onTouchEvent(event))
        }
    }

    @Test
    fun resetCancelsPendingTwoFingerRecognition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var starts = 0
        val detector =
            PhoneTwoFingerTapDetector(
                context = context,
                onTwoFingerTap = { _, _, _, _ -> },
                onMeasurementGestureStart = { starts += 1 },
            )

        singleEvent(MotionEvent.ACTION_DOWN, 10f, 10f).consume(detector::onTouchEvent)
        multiEvent(MotionEvent.ACTION_POINTER_DOWN, 10f, 10f, 20f, 20f, pointerActionIndex = 1)
            .consume(detector::onTouchEvent)
        detector.reset()
        SystemClock.sleep(220L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(0, starts)
    }

    @Test
    fun disabledTwoFingerGestureRemainsAvailableToTheNativeMap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var enabled = false
        var starts = 0
        val detector =
            PhoneTwoFingerTapDetector(
                context = context,
                onTwoFingerTap = { _, _, _, _ -> },
                onMeasurementGestureStart = { starts += 1 },
                isMeasurementEnabled = { enabled },
            )

        singleEvent(MotionEvent.ACTION_DOWN, 10f, 10f).consume { event ->
            assertFalse(detector.onTouchEvent(event))
        }
        multiEvent(MotionEvent.ACTION_POINTER_DOWN, 10f, 10f, 20f, 20f, pointerActionIndex = 1)
            .consume { event -> assertFalse(detector.onTouchEvent(event)) }
        SystemClock.sleep(220L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(0, starts)
    }

    @Test
    fun disablingDuringPendingRecognitionCancelsWithoutTakingOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var enabled = true
        var starts = 0
        val detector =
            PhoneTwoFingerTapDetector(
                context = context,
                onTwoFingerTap = { _, _, _, _ -> },
                onMeasurementGestureStart = { starts += 1 },
                isMeasurementEnabled = { enabled },
            )

        singleEvent(MotionEvent.ACTION_DOWN, 10f, 10f).consume(detector::onTouchEvent)
        multiEvent(MotionEvent.ACTION_POINTER_DOWN, 10f, 10f, 20f, 20f, pointerActionIndex = 1)
            .consume(detector::onTouchEvent)
        enabled = false
        singleEvent(MotionEvent.ACTION_MOVE, 10f, 10f).consume { event ->
            assertFalse(detector.onTouchEvent(event))
        }
        SystemClock.sleep(220L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(0, starts)
    }

    private fun singleEvent(
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    private inline fun <T> MotionEvent.consume(block: (MotionEvent) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }

    private fun multiEvent(
        action: Int,
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float,
        pointerActionIndex: Int = 0,
    ): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply { id = 0 },
                MotionEvent.PointerProperties().apply { id = 1 },
            )
        val coordinates =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = firstX
                    y = firstY
                },
                MotionEvent.PointerCoords().apply {
                    x = secondX
                    y = secondY
                },
            )
        return MotionEvent.obtain(
            now,
            now,
            action or (pointerActionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
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
    }
}
