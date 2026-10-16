package com.glancemap.glancemapcompanionapp.map

import android.graphics.PointF
import android.view.MotionEvent
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

internal fun MapLibreMap.applyPhoneMapGestureSettings(mode: PhoneMapMode) {
    uiSettings.isRotateGesturesEnabled = mode.allowsManualRotation
    if (!mode.allowsManualRotation) gesturesManager.rotateGestureDetector.interrupt()
    val insets = padding
    uiSettings.focalPoint =
        if (mode.follow == PhoneMapFollowMode.FOLLOW_LOCATION) {
            PointF((width + insets[0] - insets[2]) / 2f, (height + insets[1] - insets[3]) / 2f)
        } else {
            null
        }
}

/** Retains native scale/rotation while preventing two-finger translation in follow mode. */
internal class PhoneOnlineCenteredGestures(
    private val view: MapView,
    private val map: () -> MapLibreMap?,
    private val mode: () -> PhoneMapMode,
) {
    private val touch = PhoneMapCenteredTouchSequence()
    private var restoreMoveEnabled: Boolean? = null
    private var sequence = 0

    fun onTouch(event: MotionEvent) {
        val activeMap = map() ?: return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            reset()
            sequence += 1
        }
        touch.onTouch(event.actionMasked, event.pointerCount, mode())
        activeMap.applyPhoneMapGestureSettings(mode())
        if (touch.isLocked && restoreMoveEnabled == null) {
            val move = activeMap.gesturesManager.moveGestureDetector
            restoreMoveEnabled = move.isEnabled
            move.isEnabled = false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val endingSequence = sequence
            // Keep translation disabled until the SDK has processed the final UP/CANCEL.
            view.post { if (sequence == endingSequence) reset() }
        }
    }

    fun reset() {
        restoreMoveEnabled?.let { map()?.gesturesManager?.moveGestureDetector?.isEnabled = it }
        restoreMoveEnabled = null
        touch.reset()
    }
}
