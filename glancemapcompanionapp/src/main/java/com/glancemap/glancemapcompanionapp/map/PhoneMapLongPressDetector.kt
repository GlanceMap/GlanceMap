package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

internal fun phoneMapLongPressDetector(
    context: Context,
    onLongPress: (x: Float, y: Float) -> Unit,
): GestureDetector =
    GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent): Boolean = true

            override fun onLongPress(event: android.view.MotionEvent) {
                onLongPress(event.x, event.y)
            }
        },
    )

internal fun GestureDetector.cancelPhoneMapLongPress(event: MotionEvent) {
    MotionEvent.obtain(event).also { cancelEvent ->
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        onTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }
}

internal fun GestureDetector.cancelPhoneMapLongPress() {
    val now = SystemClock.uptimeMillis()
    MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { cancelEvent ->
        onTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }
}

/** Stops a renderer's native gesture after the measurement detector takes ownership. */
internal fun View.cancelPhoneMapNativeGesture() {
    val now = SystemClock.uptimeMillis()
    MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { cancelEvent ->
        onTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }
}

internal class PhoneMapTouchDetectorCleanup(
    private val twoFingerTapDetector: PhoneTwoFingerTapDetector,
    private val longPressDetector: GestureDetector,
) {
    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        twoFingerTapDetector.reset()
        longPressDetector.cancelPhoneMapLongPress()
    }
}
