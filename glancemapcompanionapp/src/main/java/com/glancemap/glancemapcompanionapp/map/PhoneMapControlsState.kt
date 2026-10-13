package com.glancemap.glancemapcompanionapp.map

/** Orientation and location follow remain independent map-control state. */
internal enum class PhoneMapOrientation {
    NORTH_UP,
    HEADING_UP,
}

internal enum class PhoneMapFollowMode {
    FREE,
    FOLLOW_LOCATION,
}

internal const val PHONE_MAP_DEFAULT_ZOOM = 14.0

/** Each SDK resolves search targets to the configured scale using its own tile units. */
internal fun PhoneMapCameraCommand.requestedZoom(
    currentZoom: Double,
    settings: PhoneMapSettings,
    viewportWidthPx: Double,
    tileSizePx: Double,
): Double =
    target?.let {
        phoneMapZoomForScale(it.latitude, settings.zoomDefaultScaleMeters, viewportWidthPx, tileSizePx) ?: currentZoom
    } ?: (currentZoom + zoomDelta)

internal data class PhoneMapMode(
    val orientation: PhoneMapOrientation = PhoneMapOrientation.NORTH_UP,
    val follow: PhoneMapFollowMode = PhoneMapFollowMode.FOLLOW_LOCATION,
    val manualBearingDegrees: Float? = null,
) {
    val isDetachedFromLocation: Boolean
        get() = follow == PhoneMapFollowMode.FREE

    fun toggleOrientation(): PhoneMapMode =
        copy(
            orientation =
                when (orientation) {
                    PhoneMapOrientation.NORTH_UP -> PhoneMapOrientation.HEADING_UP
                    PhoneMapOrientation.HEADING_UP -> PhoneMapOrientation.NORTH_UP
                },
        )

    fun detachFromLocation(currentBearingDegrees: Float? = null): PhoneMapMode =
        copy(
            follow = PhoneMapFollowMode.FREE,
            manualBearingDegrees = currentBearingDegrees?.let(::normalizePhoneHeadingDegrees) ?: manualBearingDegrees,
        )

    fun detachAfterManualRotation(bearingDegrees: Float): PhoneMapMode =
        copy(
            follow = PhoneMapFollowMode.FREE,
            manualBearingDegrees = normalizePhoneHeadingDegrees(bearingDegrees),
        )

    fun recenterOnLocation(): PhoneMapMode =
        copy(
            follow = PhoneMapFollowMode.FOLLOW_LOCATION,
            manualBearingDegrees = null,
        )
}

/** One renderer-neutral request from the map controls; the active renderer consumes it once. */
internal data class PhoneMapCameraCommand(
    val id: Long,
    val zoomDelta: Int = 0,
    val target: PhoneMapCoordinate? = null,
) {
    init {
        require(id > 0L)
        require(if (target == null) zoomDelta in setOf(-1, 1) else zoomDelta == 0 && target.isValidSearchPoint())
    }
}
