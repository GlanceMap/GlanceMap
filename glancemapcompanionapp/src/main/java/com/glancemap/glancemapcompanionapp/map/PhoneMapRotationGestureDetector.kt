package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import org.maplibre.android.gestures.AndroidGesturesManager
import org.maplibre.android.gestures.RotateGestureDetector

// Match MapLibre's map rotation threshold for offline gestures and measurement arbitration.
internal const val PHONE_MAP_ROTATION_ACTIVATION_DEGREES = 3f

/** Observes rotation without consuming the native map's pinch-zoom events. */
internal fun phoneMapRotationGestureDetector(
    context: Context,
    onRotationStart: () -> Unit,
    onRotation: (bearingDelta: Float) -> Unit,
): AndroidGesturesManager =
    AndroidGesturesManager(context)
        .apply {
            setRotateGestureListener(
                object : RotateGestureDetector.SimpleOnRotateGestureListener() {
                    override fun onRotateBegin(detector: RotateGestureDetector): Boolean {
                        onRotationStart()
                        return true
                    }

                    override fun onRotate(
                        detector: RotateGestureDetector,
                        rotationDegreesSinceLast: Float,
                        rotationDegreesSinceFirst: Float,
                    ): Boolean {
                        onRotation(rotationDegreesSinceLast)
                        return true
                    }
                },
            )
            rotateGestureDetector.angleThreshold = PHONE_MAP_ROTATION_ACTIVATION_DEGREES
        }

internal fun AndroidGesturesManager.cancelPhoneMapRotation() {
    val now = SystemClock.uptimeMillis()
    MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { event ->
        onTouchEvent(event)
        event.recycle()
    }
}
