package com.glancemap.glancemapcompanionapp.map

/** Identifies callbacks that belong to the current MapLibre renderer instance. */
internal data class PhoneMapLibreGeneration(
    val renderer: Long = 0L,
    val styleRevision: Long = 0L,
) {
    fun nextRenderer(): PhoneMapLibreGeneration = PhoneMapLibreGeneration(renderer = renderer + 1)

    fun accepts(callbackRenderer: Long): Boolean = renderer == callbackRenderer

    fun onStyleReady(callbackRenderer: Long) = if (accepts(callbackRenderer)) nextStyleRevision else this

    private val nextStyleRevision: PhoneMapLibreGeneration
        get() = copy(styleRevision = styleRevision + 1)
}
