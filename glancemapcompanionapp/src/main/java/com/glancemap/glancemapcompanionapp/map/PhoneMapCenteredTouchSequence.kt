package com.glancemap.glancemapcompanionapp.map

import android.view.MotionEvent

/** A pinch owns its remaining finger until the whole touch sequence ends. */
internal class PhoneMapCenteredTouchSequence {
    var isLocked: Boolean = false
        private set

    fun onTouch(
        action: Int,
        pointerCount: Int,
        mode: PhoneMapMode,
    ) {
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount >= 2 && mode.follow == PhoneMapFollowMode.FOLLOW_LOCATION) isLocked = true
            }
        }
    }

    fun reset() {
        isLocked = false
    }
}
