package com.glancemap.glancemapcompanionapp.map

internal enum class PhoneOnlineGestureType {
    PAN,
    ROTATE,
}

/** Keeps camera synchronization blocked until every active MapLibre gesture has ended. */
internal data class PhoneOnlineGestureState(
    val panActive: Boolean = false,
    val rotateActive: Boolean = false,
) {
    val isActive: Boolean
        get() = panActive || rotateActive

    fun withActive(
        type: PhoneOnlineGestureType,
        active: Boolean,
    ): PhoneOnlineGestureState =
        when (type) {
            PhoneOnlineGestureType.PAN -> copy(panActive = active)
            PhoneOnlineGestureType.ROTATE -> copy(rotateActive = active)
        }
}
