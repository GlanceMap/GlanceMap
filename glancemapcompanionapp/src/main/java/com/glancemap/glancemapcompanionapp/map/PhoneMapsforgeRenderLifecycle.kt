package com.glancemap.glancemapcompanionapp.map

/** The exact Mapsforge readiness boundary used by the proven Wear renderer lifecycle. */
internal data class PhoneMapViewRenderReadiness(
    val attachedToWindow: Boolean,
    val width: Int,
    val height: Int,
    val hasWindowFocus: Boolean,
) {
    val isReady: Boolean
        get() = attachedToWindow && width > 0 && height > 0 && hasWindowFocus
}

/**
 * Keeps semantic renderer work pending until the Android MapView can safely own a Mapsforge
 * base layer. The holder owns the delayed post; this class deliberately stays Android-free.
 */
internal class PhoneMapsforgeRenderWorkGate {
    private var generation = 0L
    private var pending = false
    private var scheduledGeneration: Long? = null

    fun requestWork() {
        pending = true
    }

    fun scheduleIfReady(readiness: PhoneMapViewRenderReadiness): Long? {
        if (!pending || !readiness.isReady || scheduledGeneration != null) return null
        // New state shares the queued callback's deadline; only a new callback gets a new ticket.
        generation += 1L
        scheduledGeneration = generation
        return generation
    }

    fun consumeIfCurrent(
        scheduled: Long,
        readiness: PhoneMapViewRenderReadiness,
    ): Boolean {
        val isCurrent = pending && scheduledGeneration == scheduled
        val isReady = readiness.isReady
        if (isCurrent && !isReady && scheduledGeneration == scheduled) {
            scheduledGeneration = null
        }
        val canConsume = isCurrent && isReady
        if (canConsume) {
            pending = false
            scheduledGeneration = null
        }
        return canConsume
    }

    fun hasPendingWork(): Boolean = pending
}

/** Ensures each Mapsforge-owned resource bundle is released once across swap and host teardown. */
internal class PhoneMapsforgeReleaseOnce {
    private var released = false

    fun release(action: () -> Unit): Boolean {
        if (released) return false
        released = true
        action()
        return true
    }
}

internal enum class PhoneMapsforgeTouchAction {
    DOWN,
    POINTER_DOWN,
    MOVE,
    POINTER_UP,
    UP,
    CANCEL,
}

/** Identifies two-finger bearing changes without losing small, accumulated rotations. */
internal class PhoneMapsforgeRotationGestureTracker {
    private var twoFingerActive = false
    private var referenceBearing: Float? = null

    val isTwoFingerActive: Boolean
        get() = twoFingerActive

    fun onTouch(
        action: PhoneMapsforgeTouchAction,
        pointerCount: Int,
    ) {
        when (action) {
            PhoneMapsforgeTouchAction.DOWN -> reset()
            PhoneMapsforgeTouchAction.POINTER_DOWN -> {
                if (pointerCount >= 2) {
                    twoFingerActive = true
                    clearBearingHistory()
                }
            }
            PhoneMapsforgeTouchAction.MOVE -> {
                if (pointerCount < 2) twoFingerActive = false
            }
            PhoneMapsforgeTouchAction.POINTER_UP -> {
                twoFingerActive = pointerCount - 1 >= 2
                clearBearingHistory()
            }
            PhoneMapsforgeTouchAction.UP,
            PhoneMapsforgeTouchAction.CANCEL,
            -> reset()
        }
    }

    fun observeBearing(
        bearingDegrees: Float,
        reportUserRotation: Boolean,
    ): Float? {
        val bearing = normalizePhoneHeadingDegrees(bearingDegrees)
        val previous = referenceBearing
        if (!reportUserRotation || !twoFingerActive || previous == null) {
            referenceBearing = bearing
            return null
        }
        return if (phoneMapBearingNeedsSync(previous, bearing)) {
            referenceBearing = bearing
            bearing
        } else {
            null
        }
    }

    fun reset() {
        twoFingerActive = false
        clearBearingHistory()
    }

    private fun clearBearingHistory() {
        referenceBearing = null
    }
}

internal fun phoneMapsforgeShouldReportPan(
    follow: PhoneMapFollowMode?,
    isTwoFingerActive: Boolean,
): Boolean = follow != PhoneMapFollowMode.FOLLOW_LOCATION || !isTwoFingerActive

internal enum class PhoneMapsforgeBaseLayerChange {
    NONE,
    MAP_SWAP,
    THEME_RELOAD,
}

internal data class PhoneMapsforgeBaseLayerIdentity(
    val mapIdentity: String,
    val themeConfig: PhoneOfflineThemeConfig,
)

internal fun phoneMapsforgeBaseLayerChange(
    current: PhoneMapsforgeBaseLayerIdentity?,
    requested: PhoneMapsforgeBaseLayerIdentity,
): PhoneMapsforgeBaseLayerChange =
    when {
        current == null -> PhoneMapsforgeBaseLayerChange.MAP_SWAP
        current.mapIdentity != requested.mapIdentity -> PhoneMapsforgeBaseLayerChange.MAP_SWAP
        current.themeConfig != requested.themeConfig -> PhoneMapsforgeBaseLayerChange.THEME_RELOAD
        else -> PhoneMapsforgeBaseLayerChange.NONE
    }
