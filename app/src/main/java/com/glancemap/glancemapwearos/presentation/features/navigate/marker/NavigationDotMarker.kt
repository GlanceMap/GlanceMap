package com.glancemap.glancemapwearos.presentation.features.navigate

import android.graphics.Canvas
import android.graphics.Paint

internal fun drawDotMarkerBitmap(
    canvas: Canvas,
    sizePx: Int,
    fillColor: Int = NAVIGATION_MARKER_BLUE_ARGB,
) {
    val center = sizePx / 2f
    val radius = sizePx * 0.23f
    val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
        }
    val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
    val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.03f
        }

    canvas.drawCircle(center, center, radius + sizePx * 0.01f, ringPaint)
    canvas.drawCircle(center, center, radius, fillPaint)
    canvas.drawCircle(center, center, radius, strokePaint)
}
