package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
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
    private val onMeasurementPointDragStart: () -> Unit = {},
    private val onMeasurementPointDragEnd: (cancelled: Boolean) -> Unit = { _ -> },
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

    private val activateRunnable = Runnable { activateIfPossible() }

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_UP -> handleUp()
            MotionEvent.ACTION_CANCEL -> reset()
        }
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
            draggedPointerId = pointerId
            draggedHandleIndex = handleIndex
            onMeasurementPointDragStart()
        }
    }

    private fun handlePointerDown(event: MotionEvent) {
        if (draggedPointerId != null) {
            finishMeasurementDrag(cancelled = true)
            return
        }
        if (candidate && event.pointerCount == 2) {
            val pointerId = event.getPointerId(event.actionIndex)
            val point = event.screenPoint(event.actionIndex)
            initialPoints[pointerId] = point
            latestPoints[pointerId] = point
            firstPointerId = event.getPointerId(0)
            secondPointerId = pointerId
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
        if (
            candidate &&
            initialPoints.any { (pointerId, initialPoint) ->
                val currentPoint = latestPoints[pointerId]
                currentPoint != null && initialPoint.distanceTo(currentPoint) > touchSlop
            }
        ) {
            cancelCandidate()
        }
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
            clearTouchState()
        } else if (active) {
            currentTwoFingerPoints()?.let { points ->
                onTwoFingerMove(points.first.x, points.first.y, points.second.x, points.second.y)
            }
            active = false
            clearTouchState()
        } else {
            cancelCandidate()
        }
    }

    private fun handleUp() {
        if (draggedPointerId != null) {
            finishMeasurementDrag(cancelled = false)
        } else {
            clearTouchState()
        }
    }

    private fun activateIfPossible() {
        if (!candidate || active) return
        val points = currentTwoFingerPoints() ?: return
        active = true
        onTwoFingerTap(points.first.x, points.first.y, points.second.x, points.second.y)
    }

    private fun currentTwoFingerPoints(): Pair<ScreenPoint, ScreenPoint>? =
        firstPointerId
            ?.let(latestPoints::get)
            ?.let { first -> secondPointerId?.let(latestPoints::get)?.let { second -> first to second } }

    private fun updateLatestPoints(event: MotionEvent) {
        repeat(event.pointerCount) { index ->
            latestPoints[event.getPointerId(index)] = event.screenPoint(index)
        }
    }

    private fun finishMeasurementDrag(cancelled: Boolean) {
        clearTouchState()
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

    private fun clearTouchState() {
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
        if (wasDragging) onMeasurementPointDragEnd(true)
    }
}

private data class ScreenPoint(
    val x: Float,
    val y: Float,
) {
    fun distanceTo(other: ScreenPoint): Float = hypot(x - other.x, y - other.y)
}

private fun MotionEvent.screenPoint(index: Int): ScreenPoint = ScreenPoint(x = getX(index), y = getY(index))

private const val TWO_FINGER_RECOGNITION_DELAY_MS = 180L
