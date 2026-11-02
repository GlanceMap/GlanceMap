package com.glancemap.glancemapcompanionapp.map

import androidx.compose.runtime.saveable.listSaver

/**
 * Phone port of the watch startup centering (`NavigateStartupCentering` and `OfflineStartCentering`).
 *
 * - A fix older than the marker freshness window is ignored until this map session accepted one.
 * - Online without a fix, the camera waits [PHONE_MAP_STARTUP_FALLBACK_GRACE_MS] before centering on
 *   the active GPX tracks; it never changes follow mode and a fix or user camera action cancels it.
 * - GPS-off mode centers once per map/GPX context and restores the last GPS-off viewport of that context.
 */
internal const val PHONE_MAP_STARTUP_FALLBACK_GRACE_MS = 15_000L

internal fun shouldShowPhoneMapStartupLocation(
    location: PhoneMapLocation?,
    hasAcceptedLocation: Boolean,
    nowElapsedRealtimeMillis: Long,
    expectedIntervalMillis: Long,
): Boolean =
    location != null &&
        (
            hasAcceptedLocation ||
                isPhoneMapLocationFreshForMarker(location, nowElapsedRealtimeMillis, expectedIntervalMillis)
        )

internal fun shouldWaitForPhoneMapStartupFallback(
    startupCenteringPending: Boolean,
    gpsOff: Boolean,
    hasLocation: Boolean,
): Boolean = startupCenteringPending && !gpsOff && !hasLocation

/** Matches the watch: the GPX bounding-box center, with the camera zoom left unchanged. */
internal fun phoneMapGpxStartCenter(overlays: List<PhoneMapGpxOverlay>): PhoneMapCoordinate? =
    overlays
        .flatMap(PhoneMapGpxOverlay::segments)
        .boundsOrNull()
        ?.let { bounds ->
            PhoneMapCoordinate(
                latitude = (bounds.south + bounds.north) * 0.5,
                longitude = (bounds.west + bounds.east) * 0.5,
            )
        }?.takeIf(PhoneMapCoordinate::isValidSearchPoint)

internal fun phoneMapStartCenterContextKey(
    offlineMapIdentity: String?,
    gpxIds: List<String>,
): String {
    val mapKey = offlineMapIdentity?.trim().orEmpty()
    val gpxKey = gpxIds.sorted().joinToString(separator = "|")
    return when {
        mapKey.isEmpty() -> "gpx=$gpxKey"
        gpxKey.isEmpty() -> "map=$mapKey"
        else -> "map=$mapKey;gpx=$gpxKey"
    }
}

internal sealed interface PhoneMapStartCenterAction {
    data object None : PhoneMapStartCenterAction

    /** Online startup without a center keeps waiting for a fix, as on the watch. */
    data object Deferred : PhoneMapStartCenterAction

    data class Center(
        val target: PhoneMapCoordinate,
    ) : PhoneMapStartCenterAction
}

/** Mirrors the watch `MapViewModel` offline start-center bookkeeping. */
internal data class PhoneMapStartCenterMemory(
    val contextKey: String? = null,
    val applied: Boolean = false,
    val forcedContextKey: String? = null,
    val savedViewportContextKey: String? = null,
    val savedViewportCenter: PhoneMapCoordinate? = null,
) {
    fun reset(): PhoneMapStartCenterMemory = copy(contextKey = null, applied = false)

    /** A different offline map always re-centers, like the watch selected-map change. */
    fun onOfflineMapChanged(offlineMapIdentity: String?): PhoneMapStartCenterMemory =
        reset().copy(forcedContextKey = offlineMapIdentity?.let { phoneMapStartCenterContextKey(it, emptyList()) })

    fun saveViewport(
        contextKey: String,
        center: PhoneMapCoordinate,
    ): PhoneMapStartCenterMemory =
        if (applied && this.contextKey == contextKey && center.isValidSearchPoint()) {
            copy(savedViewportContextKey = contextKey, savedViewportCenter = center)
        } else {
            this
        }

    fun resolve(
        contextKey: String,
        hasGpx: Boolean,
        gpxCenter: PhoneMapCoordinate?,
        deferWhenNoCenter: Boolean = false,
    ): Pair<PhoneMapStartCenterMemory, PhoneMapStartCenterAction> {
        val forceStartupCenter = shouldForceStartCenter(contextKey, hasGpx)
        val savedCenter = savedViewportCenter?.takeIf { savedViewportContextKey == contextKey }
        val entered = enterContext(contextKey, hasGpx)
        return when {
            !forceStartupCenter && savedCenter != null ->
                markHandled(contextKey) to PhoneMapStartCenterAction.Center(savedCenter)
            entered.applied -> entered to PhoneMapStartCenterAction.None
            deferWhenNoCenter && gpxCenter == null -> entered to PhoneMapStartCenterAction.Deferred
            // The watch falls back to the selected map center; the phone renderer already opens an
            // offline map at its start point or center when the camera is outside that map.
            else ->
                entered.finish(contextKey, forceStartupCenter) to
                    (gpxCenter?.let(PhoneMapStartCenterAction::Center) ?: PhoneMapStartCenterAction.None)
        }
    }

    private fun shouldForceStartCenter(
        contextKey: String,
        hasGpx: Boolean,
    ): Boolean =
        when {
            savedViewportContextKey == contextKey -> false
            forcedContextKey == contextKey -> true
            else -> hasGpx && this.contextKey != contextKey
        }

    private fun enterContext(
        contextKey: String,
        hasGpx: Boolean,
    ): PhoneMapStartCenterMemory =
        if (this.contextKey == contextKey) {
            this
        } else {
            copy(
                contextKey = contextKey,
                applied = false,
                forcedContextKey = if (hasGpx) contextKey else forcedContextKey,
            )
        }

    private fun finish(
        contextKey: String,
        forceStartupCenter: Boolean,
    ): PhoneMapStartCenterMemory =
        markHandled(contextKey).let { handled ->
            if (forceStartupCenter && handled.forcedContextKey == contextKey) {
                handled.copy(forcedContextKey = null)
            } else {
                handled
            }
        }

    private fun markHandled(contextKey: String): PhoneMapStartCenterMemory =
        copy(contextKey = contextKey, applied = true)
}

internal val phoneMapStartCenterMemorySaver =
    listSaver<PhoneMapStartCenterMemory, Any?>(
        save = { memory ->
            listOf(
                memory.contextKey,
                memory.applied,
                memory.forcedContextKey,
                memory.savedViewportContextKey,
                memory.savedViewportCenter?.latitude,
                memory.savedViewportCenter?.longitude,
            )
        },
        restore = { values ->
            runCatching {
                val latitude = values[4] as Double?
                val longitude = values[5] as Double?
                PhoneMapStartCenterMemory(
                    contextKey = values[0] as String?,
                    applied = values[1] as Boolean,
                    forcedContextKey = values[2] as String?,
                    savedViewportContextKey = values[3] as String?,
                    savedViewportCenter =
                        if (latitude != null && longitude != null) PhoneMapCoordinate(latitude, longitude) else null,
                )
            }.getOrNull()
        },
    )
