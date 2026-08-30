package com.glancemap.glancemapcompanionapp.map

import android.os.Looper
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layers
import java.util.WeakHashMap

/** Pure coalescing queue behind [PhoneMapLayerMutationCoordinator]. */
internal class PhoneMapLayerMutationQueue {
    private var gestureActive = false
    private val pending = ArrayDeque<() -> Unit>()
    private val coalescedPending = LinkedHashMap<Any, () -> Unit>()

    fun setGestureActive(active: Boolean) {
        gestureActive = active
    }

    fun submit(
        coalescingKey: Any?,
        mutation: () -> Unit,
    ): Boolean {
        if (!gestureActive) return true
        if (coalescingKey == null) {
            pending += mutation
        } else {
            coalescedPending[coalescingKey] = mutation
        }
        return false
    }

    fun drainAfterGestureIdle(): List<() -> Unit> {
        if (gestureActive) return emptyList()
        val result = pending.toList() + coalescedPending.values
        pending.clear()
        coalescedPending.clear()
        return result
    }

    fun clear() {
        pending.clear()
        coalescedPending.clear()
    }

    fun hasPendingMutations(): Boolean = pending.isNotEmpty() || coalescedPending.isNotEmpty()
}

/**
 * Serializes phone Mapsforge layer mutations and defers them while Mapsforge is handling touch.
 * This is the phone adaptation of the proven Wear coordinator.
 */
internal object PhoneMapLayerMutationCoordinator {
    private const val GESTURE_IDLE_FLUSH_DELAY_MS = 120L

    private val states = WeakHashMap<MapView, MutationState>()

    fun setGestureActive(
        mapView: MapView,
        active: Boolean,
    ) {
        runOnMapThread(mapView) {
            val state = stateFor(mapView)
            state.queue.setGestureActive(active)
            if (active) {
                state.flushRunnable?.let(mapView::removeCallbacks)
                state.flushRunnable = null
            } else {
                scheduleFlushAfterIdle(mapView, state)
            }
        }
    }

    fun mutateLayers(
        mapView: MapView,
        coalescingKey: Any? = null,
        mutation: (Layers) -> Unit,
    ) {
        runOnMapThread(mapView) {
            val state = stateFor(mapView)
            val mapMutation = { mutation(mapView.layerManager.layers) }
            if (state.queue.submit(coalescingKey, mapMutation)) {
                mapMutation()
                redrawLayersSafely(mapView)
                removeStateIfIdle(mapView, state)
            }
        }
    }

    /** Used only while the host is being destroyed, when resources must be released exactly once. */
    fun mutateLayersImmediately(
        mapView: MapView,
        mutation: (Layers) -> Unit,
    ) {
        runOnMapThread(mapView) {
            states.remove(mapView)?.let { state ->
                state.flushRunnable?.let(mapView::removeCallbacks)
                state.queue.clear()
            }
            mutation(mapView.layerManager.layers)
            redrawLayersSafely(mapView)
        }
    }

    fun redrawLayersSafely(mapView: MapView) {
        runCatching { mapView.layerManager.redrawLayers() }
            .onFailure { mapView.postInvalidate() }
    }

    private fun scheduleFlushAfterIdle(
        mapView: MapView,
        state: MutationState,
    ) {
        if (!state.queue.hasPendingMutations()) {
            removeStateIfIdle(mapView, state)
            return
        }
        state.flushRunnable?.let(mapView::removeCallbacks)
        val flush =
            Runnable {
                val current = states[mapView] ?: return@Runnable
                current.flushRunnable = null
                val mutations = current.queue.drainAfterGestureIdle()
                mutations.forEach { it.invoke() }
                if (mutations.isNotEmpty()) redrawLayersSafely(mapView)
                removeStateIfIdle(mapView, current)
            }
        state.flushRunnable = flush
        mapView.postDelayed(flush, GESTURE_IDLE_FLUSH_DELAY_MS)
    }

    private fun removeStateIfIdle(
        mapView: MapView,
        state: MutationState,
    ) {
        if (!state.queue.hasPendingMutations() && state.flushRunnable == null) states.remove(mapView)
    }

    private fun runOnMapThread(
        mapView: MapView,
        action: () -> Unit,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mapView.post(action)
    }

    private fun stateFor(mapView: MapView): MutationState = states.getOrPut(mapView) { MutationState() }

    private class MutationState {
        val queue = PhoneMapLayerMutationQueue()
        var flushRunnable: Runnable? = null
    }
}
