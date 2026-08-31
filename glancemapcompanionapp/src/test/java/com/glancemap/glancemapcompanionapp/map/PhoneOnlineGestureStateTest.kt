package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOnlineGestureStateTest {
    @Test
    fun overlappingPanAndRotateKeepCameraSyncBlockedUntilBothEnd() {
        val panStarted = PhoneOnlineGestureState().withActive(PhoneOnlineGestureType.PAN, true)
        val bothStarted = panStarted.withActive(PhoneOnlineGestureType.ROTATE, true)
        val rotateEnded = bothStarted.withActive(PhoneOnlineGestureType.ROTATE, false)
        val bothEnded = rotateEnded.withActive(PhoneOnlineGestureType.PAN, false)

        assertTrue(panStarted.isActive)
        assertTrue(bothStarted.isActive)
        assertTrue(rotateEnded.isActive)
        assertFalse(bothEnded.isActive)
    }

    @Test
    fun cameraSyncSkipIsRecordedOnlyWhenReasonOrModeChanges() {
        val first =
            PhoneOnlineCameraSyncSkipKey(
                reason = "user_gesture",
                follow = PhoneMapFollowMode.FOLLOW_LOCATION,
                orientation = PhoneMapOrientation.HEADING_UP,
            )
        val same = first.copy()
        val changed = first.copy(reason = "free_mode")

        assertTrue(shouldRecordPhoneOnlineCameraSyncSkip(null, first))
        assertFalse(shouldRecordPhoneOnlineCameraSyncSkip(first, same))
        assertTrue(shouldRecordPhoneOnlineCameraSyncSkip(first, changed))
    }
}
