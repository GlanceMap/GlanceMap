package com.glancemap.glancemapwearos.presentation.features.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.Parameters
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.util.MapPositionUtil
import java.util.concurrent.CopyOnWriteArrayList

/** Rotate the existing map image on display frames; background layers keep their own cadence. */
internal class DisplayFrameMapView(
    context: Context,
) : MapView(context),
    DisplayFrameLayerOwner {
    private val foregroundLayers = CopyOnWriteArrayList<Layer>()
    private val displayFrameObserver = Observer { updateDisplayFrameTransform() }
    private val overlayMatrix = Matrix()

    @Volatile private var displayFrameTransform: DisplayFrameTransform? = null

    init {
        // Register after Mapsforge's controllers, including its buffer-swap notification.
        model.mapViewPosition.addObserver(displayFrameObserver)
        model.frameBufferModel.addObserver(displayFrameObserver)
        model.mapViewDimension.addObserver(displayFrameObserver)
        model.displayModel.addObserver(displayFrameObserver)
    }

    // Missing view/buffer state is normal before the first draw; keep explicit guards under the locks.
    @Suppress("ReturnCount")
    private fun updateDisplayFrameTransform() {
        if (!Parameters.ROTATION_MATRIX) return
        // Use the same lock order as Mapsforge's FrameBufferController.
        synchronized(model.mapViewPosition) {
            synchronized(frameBuffer) {
                val bufferPosition = model.frameBufferModel.mapPosition ?: return
                val bufferDimension = frameBuffer.dimension ?: return
                val viewDimension = model.mapViewDimension.dimension ?: return
                val position = model.mapViewPosition.mapPosition
                val scaleFactor =
                    if (model.frameBufferModel.isScaleEnabled) {
                        model.mapViewPosition.scaleFactor
                    } else {
                        Math.pow(2.0, bufferPosition.zoomLevel.toDouble())
                    }
                val transform =
                    DisplayFrameTransform(
                        bufferPosition,
                        bufferDimension,
                        DisplayFrameCamera(
                            position,
                            viewDimension,
                            scaleFactor,
                            model.mapViewPosition.pivot,
                            Point(
                                model.mapViewPosition.mapViewCenterX.toDouble(),
                                model.mapViewPosition.mapViewCenterY.toDouble(),
                            ),
                        ),
                        model.displayModel.tileSize,
                    )
                transform.applyTo(frameBuffer)
                displayFrameTransform = transform
            }
        }
    }

    override fun addDisplayFrameLayer(layer: Layer) {
        foregroundLayers.addIfAbsent(layer)
    }

    override fun removeDisplayFrameLayer(layer: Layer) {
        foregroundLayers.remove(layer)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // super.onDraw may swap the buffered image and synchronously update this transform.
        val transform = displayFrameTransform.takeIf { Parameters.ROTATION_MATRIX } ?: return
        // Mapsforge holds its Layers monitor throughout expensive background draws.
        // onAdd/onRemove maintain this ordered snapshot without taking that monitor here.
        val foreground = displayFrameLayers(foregroundLayers)
        if (foreground.isEmpty()) return
        val bufferPosition = transform.bufferPosition
        val projectionPosition =
            MapPosition(bufferPosition.latLong, bufferPosition.zoomLevel.toDouble(), Rotation.NULL_ROTATION)
        val boundingBox =
            MapPositionUtil.getBoundingBox(
                projectionPosition,
                Rotation.NULL_ROTATION,
                model.displayModel.tileSize,
                transform.bufferDimension,
                0.5f,
                0.5f,
            )
        val topLeft =
            MapPositionUtil.getTopLeftPoint(projectionPosition, transform.bufferDimension, model.displayModel.tileSize)
        overlayMatrix.setValues(transform.matrixValues())
        canvas.save()
        canvas.concat(overlayMatrix)
        val graphics = AndroidGraphicFactory.createGraphicContext(canvas)
        val frameCanvas = DisplayFrameCanvas(graphics, transform.bufferDimension)
        try {
            foreground.forEach {
                (it as DisplayFrameLayer).drawDisplayFrame(
                    boundingBox,
                    bufferPosition.zoomLevel,
                    frameCanvas,
                    topLeft,
                    transform.rotation,
                )
            }
        } finally {
            graphics.destroy()
            canvas.restore()
        }
    }

    override fun destroy() {
        model.mapViewPosition.removeObserver(displayFrameObserver)
        model.frameBufferModel.removeObserver(displayFrameObserver)
        model.mapViewDimension.removeObserver(displayFrameObserver)
        model.displayModel.removeObserver(displayFrameObserver)
        foregroundLayers.clear()
        displayFrameTransform = null
        super.destroy()
    }
}
