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

    fun requestWork(): Long {
        pending = true
        generation += 1L
        return generation
    }

    fun scheduleIfReady(readiness: PhoneMapViewRenderReadiness): Long? {
        val nextGeneration = generation.takeIf { pending && readiness.isReady && scheduledGeneration != generation }
        if (nextGeneration != null) scheduledGeneration = nextGeneration
        return nextGeneration
    }

    fun consumeIfCurrent(
        scheduled: Long,
        readiness: PhoneMapViewRenderReadiness,
    ): Boolean {
        if (!readiness.isReady || !pending || scheduled != generation) return false
        pending = false
        scheduledGeneration = null
        return true
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
