package com.glancemap.glancemapcompanionapp.map

internal enum class PhoneOnlineGestureType {
    PAN,
    SCALE,
    ROTATE,
}

/** Keeps camera synchronization blocked until every active MapLibre gesture has ended. */
internal data class PhoneOnlineGestureState(
    val panActive: Boolean = false,
    val scaleActive: Boolean = false,
    val rotateActive: Boolean = false,
) {
    val isActive: Boolean
        get() = panActive || scaleActive || rotateActive

    val isScaleOrRotateActive: Boolean
        get() = scaleActive || rotateActive

    fun shouldSuppressPan(follow: PhoneMapFollowMode): Boolean = follow == PhoneMapFollowMode.FOLLOW_LOCATION && isScaleOrRotateActive

    fun withActive(
        type: PhoneOnlineGestureType,
        active: Boolean,
    ): PhoneOnlineGestureState =
        when (type) {
            PhoneOnlineGestureType.PAN -> copy(panActive = active)
            PhoneOnlineGestureType.SCALE -> copy(scaleActive = active)
            PhoneOnlineGestureType.ROTATE -> copy(rotateActive = active)
        }
}
