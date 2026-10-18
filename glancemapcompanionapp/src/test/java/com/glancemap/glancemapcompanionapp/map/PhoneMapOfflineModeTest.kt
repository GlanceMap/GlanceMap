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
    fun enteringOfflineModeStartsNorthUpWithManualRotationAndNoAutomaticRecenter() {
        val mode = PhoneMapMode(PhoneMapOrientation.HEADING_UP, manualBearingDegrees = 87f).toggleOfflineMode()

        assertTrue(mode.isOffline)
        assertTrue(mode.isDetachedFromLocation)
        assertEquals(PhoneMapFollowMode.FREE, mode.follow)
        assertEquals(PhoneMapOrientation.NORTH_UP, mode.orientation)
        assertNull(mode.manualBearingDegrees)
        assertTrue(mode.allowsManualRotation)
        assertFalse(mode.canAutoRecenter)
    }

    @Test
    fun panAndRecenterCallbacksCannotExitOfflineBrowsingOrLoseItsManualBearing() {
        val mode = PhoneMapMode().toggleOfflineMode().withManualBearing(180f)

        assertSame(mode, mode.detachFromLocation(125f))
        assertSame(mode, mode.recenterOnLocation())
        assertEquals(215f, requireNotNull(mode.detachAfterManualRotation(215f).manualBearingDegrees), 0.001f)
    }

    @Test
    fun offlineCenterButtonRestoresNorthUpWithoutEnablingFollowOrCompass() {
        val browsing = PhoneMapMode().toggleOfflineMode()
        val rotated = browsing.withManualBearing(450f)
        val reset = PhoneMapUiState(mapMode = rotated).toggleMapOrientation().mapMode

        assertEquals(90f, requireNotNull(rotated.manualBearingDegrees), 0.001f)
        assertEquals(PhoneMapCompassPresentation(90f, null, 270f), phoneMapCompassPresentation(rotated, 213f))
        assertEquals(browsing, reset)
        assertEquals(PhoneMapCompassPresentation(0f, null, 0f), phoneMapCompassPresentation(reset, 213f))
        assertEquals(reset, reset.toggleOrientation())
        assertFalse(reset.canAutoRecenter)
    }

    @Test
    fun leavingOfflineModeReturnsToNorthUpGpsFollow() {
        val mode = PhoneMapMode().toggleOfflineMode().withManualBearing(125f).toggleOfflineMode()

        assertEquals(PhoneMapMode(), mode)
        assertTrue(mode.detachFromLocation().canAutoRecenter)
    }

    @Test
    fun offlineComparisonKeepsTheChosenBearingAndViewport() {
        val mode = PhoneMapMode().toggleOfflineMode()
        val camera = PhoneMapCameraSnapshot(62.5, 25.5, 12.75, bearingDegrees = 142f, groundResolutionMetersPerPixel = 2.0)
        val offlineCamera = camera.forMapMode(mode)

        assertEquals(camera.copy(bearingDegrees = 0f), offlineCamera)
        assertEquals(PhoneMapCompassPresentation(0f, null, 0f), phoneMapCompassPresentation(mode, 213f))
        val rotated = mode.withManualBearing(270f)
        assertEquals(camera.copy(bearingDegrees = 270f), camera.forMapMode(rotated))
        assertEquals(camera.copy(bearingDegrees = 0f), camera.forMapMode(rotated.toggleOrientation()))
        assertEquals(PhoneMapCompassPresentation(270f, null, 90f), phoneMapCompassPresentation(rotated, 213f))
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
