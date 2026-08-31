package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Recognizes only a stationary, short two-finger tap and never consumes the event. */
internal class PhoneTwoFingerTapDetector(
    context: Context,
    private val onTwoFingerTap: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val initialPoints = mutableMapOf<Int, ScreenPoint>()
    private var candidate = false
    private var downTime = 0L
    private var releasedPoints: Pair<ScreenPoint, ScreenPoint>? = null

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_UP -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    private fun handleDown(event: MotionEvent) {
        reset()
        candidate = true
        downTime = event.eventTime
        initialPoints[event.getPointerId(0)] = event.screenPoint(0)
    }

    private fun handlePointerDown(event: MotionEvent) {
        if (candidate && event.pointerCount == 2) {
            initialPoints[event.getPointerId(event.actionIndex)] = event.screenPoint(event.actionIndex)
        } else {
            candidate = false
        }
    }

    private fun handleMove(event: MotionEvent) {
        if (
            candidate &&
            initialPoints.any { (pointerId, initialPoint) ->
                val index = event.findPointerIndex(pointerId)
                index >= 0 && initialPoint.distanceTo(event.screenPoint(index)) > touchSlop
            }
        ) {
            candidate = false
        }
    }

    private fun handlePointerUp(event: MotionEvent) {
        if (candidate && event.pointerCount == 2) {
            releasedPoints = event.screenPoint(0) to event.screenPoint(1)
        }
    }

    private fun handleUp(event: MotionEvent) {
        if (candidate && releasedPoints != null && event.eventTime - downTime <= MAX_TAP_DURATION_MS) {
            val (first, second) = releasedPoints ?: return
            onTwoFingerTap(first.x, first.y, second.x, second.y)
        }
        reset()
    }

    fun reset() {
        candidate = false
        downTime = 0L
        releasedPoints = null
        initialPoints.clear()
    }
}

private data class ScreenPoint(
    val x: Float,
    val y: Float,
) {
    fun distanceTo(other: ScreenPoint): Float = hypot(x - other.x, y - other.y)
}

private fun MotionEvent.screenPoint(index: Int): ScreenPoint = ScreenPoint(x = getX(index), y = getY(index))

private const val MAX_TAP_DURATION_MS = 500L
