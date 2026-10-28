package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

internal class TerrainEglConfigChooser(
    private val onFailure: (Throwable) -> Unit,
) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(
        egl: EGL10,
        display: EGLDisplay,
    ): EGLConfig {
        // GLSurfaceView's GL thread can fail before its renderer callbacks run.
        Thread.currentThread().uncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { _, error ->
                onFailure(error)
            }
        val attributes =
            intArrayOf(
                EGL10.EGL_RED_SIZE,
                8,
                EGL10.EGL_GREEN_SIZE,
                8,
                EGL10.EGL_BLUE_SIZE,
                8,
                EGL10.EGL_DEPTH_SIZE,
                16,
                EGL10.EGL_RENDERABLE_TYPE,
                4,
                EGL10.EGL_SURFACE_TYPE,
                EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(egl.eglChooseConfig(display, attributes, configs, configs.size, count) && count[0] > 0) {
            "No compatible OpenGL ES 2.0 configuration"
        }
        return checkNotNull(configs[0]) { "No compatible OpenGL ES 2.0 configuration" }
    }
}
