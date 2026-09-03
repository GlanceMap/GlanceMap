package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent

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
