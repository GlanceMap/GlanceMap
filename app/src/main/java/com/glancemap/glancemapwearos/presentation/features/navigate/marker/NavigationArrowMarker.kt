package com.glancemap.glancemapwearos.presentation.features.navigate

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

internal fun drawArrowMarker(
    canvas: Canvas,
    sizePx: Int,
    fillColor: Int = NAVIGATION_MARKER_BLUE_ARGB,
) {
    val size = sizePx.toFloat()
    val center = size * 0.5f

    // Distinct "navigation arrow" silhouette to avoid reading as a dot at small sizes.
    val path =
        Path().apply {
            moveTo(center, size * 0.05f)
            lineTo(size * 0.20f, size * 0.88f)
            lineTo(center, size * 0.70f)
            lineTo(size * 0.80f, size * 0.88f)
            close()
        }

    val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
    val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA001A33.toInt()
            style = Paint.Style.STROKE
            strokeWidth = size * 0.04f
        }

    canvas.drawPath(path, fillPaint)
    canvas.drawPath(path, strokePaint)
}
