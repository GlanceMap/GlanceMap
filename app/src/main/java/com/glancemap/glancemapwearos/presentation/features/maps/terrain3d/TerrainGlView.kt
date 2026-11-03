package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import com.glancemap.glancemapwearos.core.maps.sanitizeTerrain3dViewingAngle
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.features.navigate.ScreenAnchor
import com.glancemap.glancemapwearos.presentation.features.navigate.shouldStartMapPan
import org.mapsforge.core.model.LatLong
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal data class TerrainCallbacks(
    val onPanStarted: () -> Unit,
    val onPanFinished: (LatLong, Float, Float) -> Unit,
    val onPanMoved: (LatLong, Float, Float) -> Unit,
    val onTiltChanged: (Float) -> Unit,
    val onZoomChanged: (Float) -> Unit,
    val onFailure: (Throwable) -> Unit,
    val onFirstFrame: () -> Unit,
)

@Suppress("LongParameterList", "TooManyFunctions")
internal class TerrainGlView(
    context: Context,
    mesh: TerrainMesh,
    bitmap: Bitmap,
    viewingAngle: Int,
    initialDistance: Float = INITIAL_TERRAIN_CAMERA_DISTANCE,
    initialCenter: LatLong,
    initialWidth: Float,
    hillShadingEnabled: Boolean,
    private val callbacks: TerrainCallbacks,
) : GLSurfaceView(context) {
    private val terrainRenderer =
        TerrainGlRenderer(
            mesh,
            bitmap,
            viewingAngle,
            initialDistance,
            initialCenter,
            initialWidth,
            hillShadingEnabled,
            { error -> post { callbacks.onFailure(error) } },
            { post { callbacks.onFirstFrame() } },
        )
    val drawCount: Long get() = terrainRenderer.drawCount.get()
    private var active = true

    @Volatile private var released = false
    private val pendingLock = Any()
    private val pendingBitmaps = mutableSetOf<Bitmap>()
    private var previousX = 0f
    private var previousY = 0f
    private var panStart: ScreenAnchor? = null
    private val gesture = TerrainGesture()
    var pinchEnabled = false
    var cameraAdjustMode = false
    private val enterTilt =
        Runnable {
            if (panStart != null && gesture.longPress()) {
                Log.i(TERRAIN_LOG_TAG, "tilt_mode_enter")
            }
        }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!pinchEnabled) return false
                    terrainRenderer.zoom(detector.scaleFactor)
                    callbacks.onZoomChanged(terrainRenderer.distance)
                    requestRender()
                    return true
                }
            },
        )

    init {
        logViewingAngle(viewingAngle)
        setEGLContextClientVersion(2)
        setEGLConfigChooser(TerrainEglConfigChooser { error -> post { callbacks.onFailure(error) } })
        setRenderer(terrainRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                previousX = event.x
                previousY = event.y
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    panStart = ScreenAnchor(event.x.toDouble(), event.y.toDouble())
                    gesture.press()
                    if (cameraAdjustMode) {
                        enterTilt.run()
                    }
                } else {
                    removeCallbacks(enterTilt)
                    exitTilt()
                    gesture.multiTouch()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                previousX = event.getX(remaining)
                previousY = event.getY(remaining)
                if (!gesture.panning) panStart = ScreenAnchor(previousX.toDouble(), previousY.toDouble())
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (gesture.tilting) {
                        val pitched = terrainRenderer.pitch.tiltByDrag(event.y - previousY)
                        val rotated = cameraAdjustMode && terrainRenderer.bearing.rotateByDrag(event.x - previousX)
                        if (pitched) {
                            callbacks.onTiltChanged(terrainRenderer.pitch.viewingAngleDegrees)
                        }
                        if (pitched || rotated) requestRender()
                    } else if (!cameraAdjustMode) {
                        handlePanMove(event)
                    }
                }
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(enterTilt)
                exitTilt()
                if (gesture.panning) {
                    val (base, east, north) = terrainRenderer.panState()
                    callbacks.onPanFinished(base, east, north)
                }
                panStart = null
                gesture.release()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun handlePanMove(event: MotionEvent) {
        val start = panStart
        val starting =
            !gesture.panning &&
                shouldStartMapPan(start, ScreenAnchor(event.x.toDouble(), event.y.toDouble()), touchSlop)
        if (starting) {
            gesture.startPan()
            removeCallbacks(enterTilt)
            callbacks.onPanStarted()
        }
        if (gesture.panning) {
            val dx = if (starting && start != null) event.x - start.x.toFloat() else event.x - previousX
            val dy = if (starting && start != null) event.y - start.y.toFloat() else event.y - previousY
            terrainRenderer.pan(dx, dy, height)
            val (base, east, north) = terrainRenderer.panState()
            callbacks.onPanMoved(base, east, north)
            requestRender()
        }
    }

    private fun exitTilt() {
        if (gesture.tilting) {
            gesture.multiTouch()
            Log.i(TERRAIN_LOG_TAG, "tilt_mode_exit viewing_angle=${terrainRenderer.pitch.viewingAngleDegrees}")
        }
    }

    fun setInteractiveViewingAngle(angle: Float) {
        if (!released && terrainRenderer.pitch.setInteractiveViewingAngle(angle) && active) requestRender()
    }

    fun setViewingAngle(angle: Int) {
        if (released || !terrainRenderer.pitch.setViewingAngle(angle)) return
        logViewingAngle(angle)
        if (active) requestRender()
    }

    fun setBearing(degrees: Float) {
        if (!released && terrainRenderer.bearing.set(degrees) && active) requestRender()
    }

    private fun logViewingAngle(angle: Int) {
        Log.i(
            TERRAIN_LOG_TAG,
            "viewing_angle=${sanitizeTerrain3dViewingAngle(angle)} " +
                "renderer_elevation=${terrainRenderer.pitch.rendererElevationDegrees}",
        )
    }

    fun zoomByStep(step: Int): Boolean {
        if (released || !active) return false
        val changed = terrainRenderer.zoomByStep(step)
        if (changed) {
            callbacks.onZoomChanged(terrainRenderer.distance)
            requestRender()
        }
        return changed
    }

    val terrainCameraDistance: Float
        get() = terrainRenderer.distance

    fun currentPanState(): Triple<LatLong, Float, Float> = terrainRenderer.panState()

    fun setTarget(center: LatLong) {
        terrainRenderer.setTarget(center)
        if (active && !released) requestRender()
    }

    fun replaceTerrain(
        prepared: PreparedTerrain,
        target: LatLong?,
        revision: AtomicLong,
        expectedRevision: Long,
        onSwapped: () -> Unit,
    ) {
        synchronized(pendingLock) {
            if (released) {
                prepared.bitmap.recycle()
                return
            }
            pendingBitmaps.add(prepared.bitmap)
        }
        queueEvent {
            synchronized(pendingLock) {
                if (pendingBitmaps.remove(prepared.bitmap)) {
                    if (released || revision.get() != expectedRevision) {
                        prepared.bitmap.recycle()
                    } else {
                        terrainRenderer.replaceTerrain(prepared, target, revision, expectedRevision) { post(onSwapped) }
                        requestRender()
                    }
                }
            }
        }
    }

    fun setActive(visible: Boolean) {
        if (released || active == visible) return
        active = visible
        if (visible) {
            onResume()
            requestRender()
        } else {
            onPause()
        }
    }

    fun release() {
        if (released) return
        removeCallbacks(enterTilt)
        gesture.release()
        synchronized(pendingLock) {
            released = true
            pendingBitmaps.forEach { it.recycle() }
            pendingBitmaps.clear()
        }
        if (active) {
            queueEvent { terrainRenderer.releaseGpu() }
            onPause()
            active = false
        }
        terrainRenderer.releaseBitmap()
        Log.i(TERRAIN_LOG_TAG, "resources_released")
    }
}

@Suppress("LongParameterList", "TooManyFunctions")
private class TerrainGlRenderer(
    private var mesh: TerrainMesh,
    private var bitmap: Bitmap,
    initialViewingAngle: Int,
    initialDistance: Float,
    initialCenter: LatLong,
    initialWidth: Float,
    private val hillShadingEnabled: Boolean,
    private val onFailure: (Throwable) -> Unit,
    private val onFirstFrame: () -> Unit,
) : GLSurfaceView.Renderer {
    val drawCount = AtomicLong()
    private val panLock = Any()
    private var areaCenter = initialCenter
    private var areaWidth = initialWidth
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val combined = FloatArray(16)
    private val buffers = IntArray(2)
    private val texture = IntArray(1)
    private var program = 0
    private var width = 1
    private var height = 1
    private var firstFrameReported = false
    private var failed = false
    private var diagnosticFrames = 0
    private var diagnosticDrawNanos = 0L
    private var diagnosticMaxDrawNanos = 0L

    val pitch = TerrainPitch(initialViewingAngle)
    val bearing = TerrainBearing()

    @Volatile var distance = initialDistance
        private set

    @Volatile var panEast = 0f
        private set

    @Volatile var panNorth = 0f
        private set

    fun pan(
        dx: Float,
        dy: Float,
        height: Int,
    ) {
        val (east, north) = terrainPanDelta(dx, dy, distance, pitch.rendererElevationDegrees, height, bearing.degrees)
        synchronized(panLock) {
            panEast += east
            panNorth += north
        }
    }

    fun panState(): Triple<LatLong, Float, Float> = synchronized(panLock) { Triple(areaCenter, panEast, panNorth) }

    fun setTarget(center: LatLong) {
        synchronized(panLock) {
            val area = TerrainArea(areaCenter.latitude, areaCenter.longitude, areaWidth.toDouble())
            val (east, north) = area.metersForCoordinate(center.latitude, center.longitude)
            panEast = east
            panNorth = north
        }
    }

    fun zoom(factor: Float) {
        distance = terrainZoomDistance(distance, factor)
    }

    fun zoomByStep(step: Int): Boolean {
        val next = terrainZoomByStep(distance, step)
        val changed = next != distance
        distance = next
        return changed
    }

    // Platform and driver failures must leave the previously uploaded terrain available.
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun replaceTerrain(
        prepared: PreparedTerrain,
        target: LatLong?,
        revision: AtomicLong,
        expectedRevision: Long,
        onSwapped: () -> Unit,
    ) {
        val nextBuffers = IntArray(2)
        val nextTexture = IntArray(1)
        var installed = false
        try {
            GLES20.glGenBuffers(2, nextBuffers, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, nextBuffers[0])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                (prepared.mesh.vertices.size + prepared.mesh.light.size) * 4,
                vertexBuffer(prepared.mesh),
                GLES20.GL_STATIC_DRAW,
            )
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, nextBuffers[1])
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER,
                prepared.mesh.indices.size * 2,
                indexBuffer(prepared.mesh),
                GLES20.GL_STATIC_DRAW,
            )
            GLES20.glGenTextures(1, nextTexture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nextTexture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, prepared.bitmap, 0)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Terrain replacement upload failed" }
            val wasCurrent =
                installIfCurrent(revision, expectedRevision) {
                    synchronized(panLock) {
                        val oldArea = TerrainArea(areaCenter.latitude, areaCenter.longitude, areaWidth.toDouble())
                        val oldTarget = oldArea.coordinateForMeters(panEast.toDouble(), panNorth.toDouble())
                        val targetLat = target?.latitude ?: oldTarget.first
                        val targetLon = target?.longitude ?: oldTarget.second
                        val newArea =
                            TerrainArea(
                                prepared.center.latitude,
                                prepared.center.longitude,
                                prepared.widthMeters.toDouble(),
                            )
                        val (east, north) = newArea.metersForCoordinate(targetLat, targetLon)
                        panEast = east
                        panNorth = north
                        areaCenter = prepared.center
                        areaWidth = prepared.widthMeters
                    }
                    GLES20.glDeleteBuffers(2, buffers, 0)
                    GLES20.glDeleteTextures(1, texture, 0)
                    buffers[0] = nextBuffers[0]
                    buffers[1] = nextBuffers[1]
                    texture[0] = nextTexture[0]
                    mesh = prepared.mesh
                    bitmap.recycle()
                    bitmap = prepared.bitmap
                    installed = true
                }
            if (wasCurrent) onSwapped()
        } catch (error: RuntimeException) {
            Log.w(TERRAIN_LOG_TAG, "terrain replacement failed", error)
        } catch (error: OutOfMemoryError) {
            Log.w(TERRAIN_LOG_TAG, "terrain replacement failed", error)
        } finally {
            if (!installed) {
                GLES20.glDeleteBuffers(2, nextBuffers, 0)
                GLES20.glDeleteTextures(1, nextTexture, 0)
                prepared.bitmap.recycle()
            }
        }
    }

    private fun vertexBuffer(mesh: TerrainMesh) =
        ByteBuffer
            .allocateDirect((mesh.vertices.size + mesh.light.size) * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                for (i in mesh.light.indices) {
                    put(mesh.vertices, i * 5, 5)
                    put(mesh.light[i])
                }
                position(0)
            }

    private fun indexBuffer(mesh: TerrainMesh) =
        ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(mesh.indices)
            position(0)
        }

    // Renderer and driver failures must return to the retained 2D map.
    @Suppress("TooGenericExceptionCaught")
    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        val start = SystemClock.elapsedRealtime()
        try {
            program = 0
            failed = false
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            GLES20.glGenBuffers(2, buffers, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                (mesh.vertices.size + mesh.light.size) * 4,
                vertexBuffer(mesh),
                GLES20.GL_STATIC_DRAW,
            )
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER,
                mesh.indices.size * 2,
                indexBuffer(mesh),
                GLES20.GL_STATIC_DRAW,
            )
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "OpenGL initialization failed" }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            Log.i("Terrain3D", "rendererInitMs=${SystemClock.elapsedRealtime() - start}")
        } catch (error: RuntimeException) {
            reportFailure(error)
        } catch (error: OutOfMemoryError) {
            reportFailure(error)
        }
    }

    private fun reportFailure(error: Throwable) {
        failed = true
        program = 0
        onFailure(error)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (program == 0 || failed) return
        drawCount.incrementAndGet()
        val diagnostics = DebugTelemetry.isFullDiagnosticsCaptureEnabled()
        val drawStart = if (diagnostics) SystemClock.elapsedRealtimeNanos() else 0L
        GLES20.glClearColor(0.08f, 0.09f, 0.1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val (eyeEast, eyeHeight, eyeSouth) =
            terrainCameraOffset(distance, pitch.rendererElevationDegrees, bearing.degrees)
        val targetEast = panEast
        val targetSouth = -panNorth
        Matrix.setLookAtM(
            view,
            0,
            targetEast + eyeEast,
            eyeHeight,
            targetSouth + eyeSouth,
            targetEast,
            0f,
            targetSouth,
            0f,
            1f,
            0f,
        )
        Matrix.perspectiveM(projection, 0, TERRAIN_FIELD_OF_VIEW_DEGREES, width.toFloat() / height, 20f, 150000f)
        Matrix.multiplyMM(combined, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "matrix"), 1, false, combined, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        val light = GLES20.glGetAttribLocation(program, "light")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glEnableVertexAttribArray(light)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glVertexAttribPointer(light, 1, GLES20.GL_FLOAT, false, 24, 20)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "lighting"), if (hillShadingEnabled) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, 0)
        if (diagnostics) recordDrawTiming(SystemClock.elapsedRealtimeNanos() - drawStart)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            failed = true
            onFailure(IllegalStateException("OpenGL draw failed"))
        } else if (!firstFrameReported) {
            firstFrameReported = true
            onFirstFrame()
        }
    }

    private fun recordDrawTiming(elapsed: Long) {
        diagnosticFrames++
        diagnosticDrawNanos += elapsed
        diagnosticMaxDrawNanos = maxOf(diagnosticMaxDrawNanos, elapsed)
        if (diagnosticFrames >= 120) {
            flushDrawTiming()
        }
    }

    private fun flushDrawTiming() {
        if (diagnosticFrames == 0) return
        DebugTelemetry.log(
            TERRAIN_LOG_TAG,
            "event=draw_summary frames=$diagnosticFrames " +
                "cpu_avg_us=${diagnosticDrawNanos / diagnosticFrames / 1000} " +
                "cpu_max_us=${diagnosticMaxDrawNanos / 1000}",
        )
        diagnosticFrames = 0
        diagnosticDrawNanos = 0
        diagnosticMaxDrawNanos = 0
    }

    fun releaseGpu() {
        flushDrawTiming()
        GLES20.glDeleteBuffers(2, buffers, 0)
        GLES20.glDeleteTextures(1, texture, 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }

    fun releaseBitmap() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER =
            "attribute vec4 position; attribute vec2 uv; attribute float light; uniform mat4 matrix; " +
                "varying vec2 texCoord; varying float shade; " +
                "void main() { gl_Position = matrix * position; texCoord = uv; shade = light; }"
        private const val FRAGMENT_SHADER =
            "precision mediump float; uniform sampler2D image; uniform float lighting; " +
                "varying vec2 texCoord; varying float shade; " +
                "void main() { vec4 color = texture2D(image, texCoord); " +
                "gl_FragColor = vec4(color.rgb * mix(1.0, shade, lighting), color.a); }"
    }
}
