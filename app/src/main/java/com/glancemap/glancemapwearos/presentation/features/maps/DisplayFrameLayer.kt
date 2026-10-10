package com.glancemap.glancemapwearos.presentation.features.maps

import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.overlay.Marker

/** Foreground layers remain in Mapsforge's ordering/lifecycle, but draw once on the display frame. */
interface DisplayFrameLayer {
    val displayFrameOwner: DisplayFrameLayerOwner?
    val displayOnFrame: Boolean get() = displayFrameOwner != null

    fun drawDisplayFrame(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    )
}

internal class DisplayFrameMarker(
    latLong: LatLong,
    bitmap: Bitmap,
    override val displayFrameOwner: DisplayFrameLayerOwner?,
) : Marker(latLong, bitmap, 0, 0),
    DisplayFrameLayer {
    override fun onAdd() {
        super.onAdd()
        displayFrameOwner?.addDisplayFrameLayer(this)
    }

    override fun onRemove() {
        displayFrameOwner?.removeDisplayFrameLayer(this)
        super.onRemove()
    }

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        if (!displayOnFrame) super.draw(boundingBox, zoomLevel, canvas, topLeft, mapViewRotation)
    }

    override fun drawDisplayFrame(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) = super.draw(boundingBox, zoomLevel, canvas, topLeft, mapViewRotation)
}

interface DisplayFrameLayerOwner {
    fun addDisplayFrameLayer(layer: Layer)

    fun removeDisplayFrameLayer(layer: Layer)
}

/** Foreground projection uses buffer coordinates, including while panning or animating a zoom. */
internal class DisplayFrameCanvas(
    private val delegate: Canvas,
    private val dimension: Dimension,
) : Canvas by delegate {
    override fun getDimension(): Dimension = dimension

    override fun getWidth(): Int = dimension.width

    override fun getHeight(): Int = dimension.height
}

internal fun displayFrameLayers(layers: Iterable<Layer>): List<Layer> =
    layers.filter {
        it.isVisible &&
            it is DisplayFrameLayer &&
            it.displayOnFrame
    }
