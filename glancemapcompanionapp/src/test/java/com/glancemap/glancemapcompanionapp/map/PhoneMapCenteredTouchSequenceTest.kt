package com.glancemap.glancemapcompanionapp.map

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapCenteredTouchSequenceTest {
    @Test
    fun centeredPinchKeepsItsLockUntilTheLastFingerLeaves() {
        val touch = PhoneMapCenteredTouchSequence()
        val mode = PhoneMapMode()
        touch.onTouch(MotionEvent.ACTION_DOWN, 1, mode)
        assertFalse(touch.isLocked)
        touch.onTouch(MotionEvent.ACTION_POINTER_DOWN, 2, mode)
        assertTrue(touch.isLocked)
        touch.onTouch(MotionEvent.ACTION_MOVE, 2, mode)
        touch.onTouch(MotionEvent.ACTION_POINTER_UP, 2, mode)
        touch.onTouch(MotionEvent.ACTION_MOVE, 1, mode)
        assertTrue(touch.isLocked)
        touch.onTouch(MotionEvent.ACTION_UP, 1, mode)
        assertFalse(touch.isLocked)
    }

    @Test
    fun freePinchDoesNotLockTranslation() {
        val touch = PhoneMapCenteredTouchSequence()
        val mode = PhoneMapMode(follow = PhoneMapFollowMode.FREE)
        touch.onTouch(MotionEvent.ACTION_DOWN, 1, mode)
        touch.onTouch(MotionEvent.ACTION_POINTER_DOWN, 2, mode)
        touch.onTouch(MotionEvent.ACTION_MOVE, 2, mode)
        assertFalse(touch.isLocked)
    }

    @Test
    fun cancellationAndANewSingleFingerGestureReleaseTheLock() {
        val touch = PhoneMapCenteredTouchSequence()
        touch.onTouch(MotionEvent.ACTION_POINTER_DOWN, 2, PhoneMapMode())
        touch.onTouch(MotionEvent.ACTION_CANCEL, 2, PhoneMapMode())
        assertFalse(touch.isLocked)
        touch.onTouch(MotionEvent.ACTION_POINTER_DOWN, 2, PhoneMapMode())
        touch.onTouch(MotionEvent.ACTION_DOWN, 1, PhoneMapMode())
        assertFalse(touch.isLocked)
    }

    @Test
    fun enteringCompassModeClearsManualBearingAndRejectsLateRotationCallbacks() {
        val mode = PhoneMapMode().withManualBearing(90f).recenterOnLocation().toggleOrientation()
        assertEquals(PhoneMapOrientation.HEADING_UP, mode.orientation)
        assertNull(mode.manualBearingDegrees)
        assertFalse(mode.allowsManualRotation)
        assertEquals(mode, mode.withManualBearing(120f))
        assertEquals(45f, phoneMapCompassPresentation(mode, 45f).mapBearingDegrees, 0.001f)
    }

    @Test
    fun northUpRotationEntersPanningWhileFreeRotationWorksInEitherOrientation() {
        val rotated = PhoneMapMode().withManualBearing(375f)
        assertEquals(PhoneMapFollowMode.FREE, rotated.follow)
        assertTrue(rotated.isDetachedFromLocation)
        assertEquals(15f, requireNotNull(rotated.manualBearingDegrees), 0.001f)
        assertEquals(PhoneMapMode(), rotated.recenterOnLocation())
        PhoneMapOrientation.entries.forEach { orientation ->
            val free = PhoneMapMode(orientation, PhoneMapFollowMode.FREE)
            assertTrue(free.allowsManualRotation)
            val panning = free.withManualBearing(90f)
            assertEquals(90f, requireNotNull(panning.manualBearingDegrees), 0.001f)
            assertEquals(PhoneMapMode(orientation), panning.recenterOnLocation())
        }
    }

    @Test
    fun rotationDoesNotTurnTheRemainingPinchFingerIntoANewPan() {
        val touch = PhoneMapCenteredTouchSequence()
        touch.onTouch(MotionEvent.ACTION_DOWN, 1, PhoneMapMode())
        touch.onTouch(MotionEvent.ACTION_POINTER_DOWN, 2, PhoneMapMode())
        val panning = PhoneMapMode().withManualBearing(90f)
        touch.onTouch(MotionEvent.ACTION_MOVE, 2, panning)
        touch.onTouch(MotionEvent.ACTION_POINTER_UP, 2, panning)
        touch.onTouch(MotionEvent.ACTION_MOVE, 1, panning)
        assertTrue(touch.isLocked)
        touch.onTouch(MotionEvent.ACTION_UP, 1, panning)
        assertFalse(touch.isLocked)
    }
}
