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

internal data class PhoneMapMode(
    val orientation: PhoneMapOrientation = PhoneMapOrientation.NORTH_UP,
    val follow: PhoneMapFollowMode = PhoneMapFollowMode.FOLLOW_LOCATION,
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

    fun detachFromLocation(): PhoneMapMode = copy(follow = PhoneMapFollowMode.FREE)

    fun recenterOnLocation(): PhoneMapMode = copy(follow = PhoneMapFollowMode.FOLLOW_LOCATION)
}

/** One renderer-neutral request from the map controls; the active renderer consumes it once. */
internal data class PhoneMapCameraCommand(
    val id: Long,
    val zoomDelta: Int,
) {
    init {
        require(id > 0L)
        require(zoomDelta in setOf(-1, 1))
    }
}
