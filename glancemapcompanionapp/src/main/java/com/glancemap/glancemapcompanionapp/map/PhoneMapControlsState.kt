package com.glancemap.glancemapcompanionapp.map

/** Orientation and location follow stay separate even though one map control cycles their UX. */
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
    val follow: PhoneMapFollowMode = PhoneMapFollowMode.FREE,
) {
    fun cycle(): PhoneMapMode =
        when (this) {
            PhoneMapMode(PhoneMapOrientation.NORTH_UP, PhoneMapFollowMode.FREE) ->
                PhoneMapMode(PhoneMapOrientation.HEADING_UP, PhoneMapFollowMode.FREE)
            PhoneMapMode(PhoneMapOrientation.HEADING_UP, PhoneMapFollowMode.FREE) ->
                PhoneMapMode(PhoneMapOrientation.NORTH_UP, PhoneMapFollowMode.FOLLOW_LOCATION)
            else -> PhoneMapMode()
        }
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
