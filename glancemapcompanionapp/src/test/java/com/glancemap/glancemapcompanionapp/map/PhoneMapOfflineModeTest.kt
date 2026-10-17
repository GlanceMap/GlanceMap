package com.glancemap.glancemapcompanionapp.map

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapOfflineModeTest {
    @Test
    fun enteringOfflineModeDisablesFollowRotationAndAutomaticRecenter() {
        val mode = PhoneMapMode(PhoneMapOrientation.HEADING_UP, manualBearingDegrees = 87f).toggleOfflineMode()

        assertTrue(mode.isOffline)
        assertTrue(mode.isDetachedFromLocation)
        assertEquals(PhoneMapFollowMode.FREE, mode.follow)
        assertEquals(PhoneMapOrientation.NORTH_UP, mode.orientation)
        assertNull(mode.manualBearingDegrees)
        assertFalse(mode.allowsManualRotation)
        assertFalse(mode.canAutoRecenter)
    }

    @Test
    fun panRotationAndRecenterCallbacksCannotExitOfflineBrowsing() {
        val mode = PhoneMapMode().toggleOfflineMode()

        assertSame(mode, mode.detachFromLocation(125f))
        assertSame(mode, mode.withManualBearing(180f))
        assertSame(mode, mode.detachAfterManualRotation(215f))
        assertSame(mode, mode.toggleOrientation())
        assertSame(mode, mode.recenterOnLocation())
    }

    @Test
    fun leavingOfflineModeReturnsToNorthUpGpsFollow() {
        val mode = PhoneMapMode().toggleOfflineMode().toggleOfflineMode()

        assertEquals(PhoneMapMode(), mode)
        assertTrue(mode.detachFromLocation().canAutoRecenter)
    }

    @Test
    fun offlinePresentationAndDelayedComparisonCamerasStayNorthUpWithoutMovingTheViewport() {
        val mode = PhoneMapMode().toggleOfflineMode()
        val camera = PhoneMapCameraSnapshot(62.5, 25.5, 12.75, bearingDegrees = 142f, groundResolutionMetersPerPixel = 2.0)
        val offlineCamera = camera.forMapMode(mode)

        assertEquals(camera.copy(bearingDegrees = 0f), offlineCamera)
        assertEquals(PhoneMapCompassPresentation(0f, null, 0f), phoneMapCompassPresentation(mode, 213f))
        assertSame(camera, camera.forMapMode(PhoneMapMode()))
    }

    @Test
    fun offlinePinchDoesNotLockTheViewportToGps() {
        val touch = PhoneMapCenteredTouchSequence()
        val mode = PhoneMapMode().toggleOfflineMode()

        touch.onTouch(MotionEvent.ACTION_DOWN, 1, mode)
        touch.onTouch(MotionEvent.ACTION_POINTER_DOWN, 2, mode)
        touch.onTouch(MotionEvent.ACTION_MOVE, 2, mode)
        assertFalse(touch.isLocked)
    }
}
