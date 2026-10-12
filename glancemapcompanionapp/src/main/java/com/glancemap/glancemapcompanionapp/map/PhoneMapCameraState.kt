package com.glancemap.glancemapcompanionapp.map

internal enum class PhoneMapCameraRenderer {
    BASE,
    COMPARISON,
}

/** Only direct camera inputs can take authority; copying a camera never takes it. */
internal data class PhoneMapCameraState(
    val camera: PhoneMapCameraSnapshot,
    val publisher: PhoneMapCameraRenderer = PhoneMapCameraRenderer.BASE,
) {
    fun beginInput(renderer: PhoneMapCameraRenderer): PhoneMapCameraState = copy(publisher = renderer)

    fun update(
        renderer: PhoneMapCameraRenderer,
        camera: PhoneMapCameraSnapshot,
        comparisonActive: Boolean,
    ): PhoneMapCameraState {
        val canPublish = if (comparisonActive) renderer == publisher else renderer == PhoneMapCameraRenderer.BASE
        return if (canPublish) copy(camera = camera, publisher = renderer) else this
    }

    fun cameraForSynchronization(
        renderer: PhoneMapCameraRenderer,
    ): PhoneMapCameraSnapshot? = camera.takeIf { renderer != publisher }
}
