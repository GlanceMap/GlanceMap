package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.view.GestureDetector

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
