package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Recognizes a short two-finger press and keeps reporting its points while they move. */
@Suppress("LongParameterList", "TooManyFunctions") // One touch-state machine keeps pointer ownership explicit.
internal class PhoneTwoFingerTapDetector(
    context: Context,
    private val onTwoFingerTap: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
    private val onTwoFingerMove: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit =
        { _, _, _, _ -> },
    private val measurementHandleAt: (x: Float, y: Float) -> Int? = { _, _ -> null },
    private val onMeasurementPointMove: (index: Int, x: Float, y: Float) -> Unit =
        { _, _, _ -> },
    private val onMeasurementGestureStart: () -> Unit = {},
    private val onMeasurementPointDragStart: () -> Unit = {},
    private val onMeasurementPointDragEnd: (cancelled: Boolean) -> Unit = { _ -> },
    private val isMeasurementEnabled: () -> Boolean = { true },
) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val handler = Handler(Looper.getMainLooper())
    private val initialPoints = mutableMapOf<Int, ScreenPoint>()
    private val latestPoints = mutableMapOf<Int, ScreenPoint>()
    private var candidate = false
    private var active = false
    private var firstPointerId: Int? = null
    private var secondPointerId: Int? = null
    private var draggedPointerId: Int? = null
    private var draggedHandleIndex: Int? = null
    private var measurementGestureOwned = false

    private val activateRunnable = Runnable { activateIfPossible() }

    /** Returns true when this detector owns the current gesture instead of the native map. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val ownedBeforeEvent = measurementGestureOwned
        if (isMeasurementEnabled()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> handleDown(event)
                MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
                MotionEvent.ACTION_MOVE -> handleMove(event)
                MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
                MotionEvent.ACTION_UP -> handleUp()
                MotionEvent.ACTION_CANCEL -> reset()
            }
        } else if (measurementGestureOwned) {
            reset()
        } else {
            cancelCandidate()
        }
        return ownedBeforeEvent || measurementGestureOwned
    }

    private fun handleDown(event: MotionEvent) {
        reset()
        val point = event.screenPoint(0)
        val pointerId = event.getPointerId(0)
        initialPoints[pointerId] = point
        latestPoints[pointerId] = point
        candidate = true
        measurementHandleAt(point.x, point.y)?.let { handleIndex ->
            candidate = false
            measurementGestureOwned = true
            draggedPointerId = pointerId
            draggedHandleIndex = handleIndex
            onMeasurementGestureStart()
            onMeasurementPointDragStart()
        }
    }

    private fun handlePointerDown(event: MotionEvent) {
        if (draggedPointerId != null) {
            finishMeasurementDrag(cancelled = true)
            return
        }
        if (measurementGestureOwned) return
        if (candidate && event.pointerCount == 2) {
            val pointerId = event.getPointerId(event.actionIndex)
            val point = event.screenPoint(event.actionIndex)
            initialPoints[pointerId] = point
            latestPoints[pointerId] = point
            firstPointerId = event.getPointerId(0)
            secondPointerId = pointerId
            initialPoints[event.getPointerId(0)] = event.screenPoint(0)
            updateLatestPoints(event)
            handler.postDelayed(activateRunnable, TWO_FINGER_RECOGNITION_DELAY_MS)
        } else {
            cancelCandidate()
        }
    }

    private fun handleMove(event: MotionEvent) {
        updateLatestPoints(event)
        val draggedPointer = draggedPointerId
        val draggedHandle = draggedHandleIndex
        if (draggedPointer != null && draggedHandle != null) {
            val index = event.findPointerIndex(draggedPointer)
            if (index >= 0) {
                onMeasurementPointMove(draggedHandle, event.getX(index), event.getY(index))
            }
            return
        }
        if (active) {
            currentTwoFingerPoints()?.let { points ->
                onTwoFingerMove(points.first.x, points.first.y, points.second.x, points.second.y)
            }
            return
        }
        val movedBeyondSlop =
            initialPoints.any { (pointerId, initialPoint) ->
                val currentPoint = latestPoints[pointerId]
                currentPoint != null && initialPoint.distanceTo(currentPoint) > touchSlop
            }
        if (candidate && (hasTwoFingerTransform() || movedBeyondSlop)) {
            cancelCandidate()
        }
    }

    private fun hasTwoFingerTransform(): Boolean {
        val initial = currentTwoFingerPoints(initialPoints)
        val latest = currentTwoFingerPoints()
        return if (initial == null || latest == null) {
            false
        } else {
            val spanChange = abs(initial.first.distanceTo(initial.second) - latest.first.distanceTo(latest.second))
            val rotation =
                shortestPhoneHeadingDelta(
                    latest.first.angleTo(latest.second),
                    initial.first.angleTo(initial.second),
                )
            spanChange > touchSlop || abs(rotation) >= PHONE_MAP_ROTATION_ACTIVATION_DEGREES
        }
    }

    /** Native zoom/rotation has started; measurement must wait for a fresh gesture. */
    fun cancelPendingMeasurement() {
        if (!measurementGestureOwned) cancelCandidate()
    }

    private fun handlePointerUp(event: MotionEvent) {
        updateLatestPoints(event)
        val liftedPointerId = event.getPointerId(event.actionIndex)
        if (draggedPointerId != null) {
            if (draggedPointerId == liftedPointerId) finishMeasurementDrag(cancelled = false)
            return
        }
        val isTrackedPointer = liftedPointerId == firstPointerId || liftedPointerId == secondPointerId
        if (!isTrackedPointer) return
        if (candidate && event.pointerCount == 2) {
            activateIfPossible()
            active = false
            clearTouchTracking()
        } else if (active) {
            currentTwoFingerPoints()?.let { points ->
                onTwoFingerMove(points.first.x, points.first.y, points.second.x, points.second.y)
            }
            active = false
            clearTouchTracking()
        } else {
            cancelCandidate()
        }
    }

    private fun handleUp() {
        if (draggedPointerId != null) {
            finishMeasurementDrag(cancelled = false)
        } else {
            clearTouchTracking()
        }
        measurementGestureOwned = false
    }

    private fun activateIfPossible() {
        if (!isMeasurementEnabled()) {
            cancelCandidate()
        } else if (!candidate || active) {
            return
        } else {
            val points = currentTwoFingerPoints() ?: return
            active = true
            measurementGestureOwned = true
            onMeasurementGestureStart()
            onTwoFingerTap(points.first.x, points.first.y, points.second.x, points.second.y)
        }
    }

    private fun currentTwoFingerPoints(points: Map<Int, ScreenPoint> = latestPoints): Pair<ScreenPoint, ScreenPoint>? =
        firstPointerId
            ?.let(points::get)
            ?.let { first -> secondPointerId?.let(points::get)?.let { second -> first to second } }

    private fun updateLatestPoints(event: MotionEvent) {
        repeat(event.pointerCount) { index ->
            latestPoints[event.getPointerId(index)] = event.screenPoint(index)
        }
    }

    private fun finishMeasurementDrag(cancelled: Boolean) {
        clearTouchTracking()
        onMeasurementPointDragEnd(cancelled)
    }

    private fun cancelCandidate() {
        handler.removeCallbacks(activateRunnable)
        candidate = false
        active = false
        firstPointerId = null
        secondPointerId = null
        initialPoints.clear()
        latestPoints.clear()
    }

    private fun clearTouchTracking() {
        handler.removeCallbacks(activateRunnable)
        candidate = false
        active = false
        firstPointerId = null
        secondPointerId = null
        draggedPointerId = null
        draggedHandleIndex = null
        initialPoints.clear()
        latestPoints.clear()
    }

    fun reset() {
        val wasDragging = draggedPointerId != null
        cancelCandidate()
        draggedPointerId = null
        draggedHandleIndex = null
        measurementGestureOwned = false
        if (wasDragging) onMeasurementPointDragEnd(true)
    }
}

private data class ScreenPoint(
    val x: Float,
    val y: Float,
) {
    fun distanceTo(other: ScreenPoint): Float = hypot(x - other.x, y - other.y)

    fun angleTo(other: ScreenPoint): Float {
        val radians = atan2((other.y - y).toDouble(), (other.x - x).toDouble())
        return Math.toDegrees(radians).toFloat()
    }
}

private fun MotionEvent.screenPoint(index: Int): ScreenPoint = ScreenPoint(x = getX(index), y = getY(index))

private const val TWO_FINGER_RECOGNITION_DELAY_MS = 180L
