package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.SVG
import com.glancemap.trailcore.poi.PoiType

internal fun PoiType.phoneMapPoiMarkerImageId(
    settings: PhoneMapPoiSettings,
): String =
    "companion-poi-${settings.markerStyle.storageValue.lowercase()}-" +
        "${settings.iconSize.storageValue.lowercase()}-$name"

internal fun Context.phoneMapPoiMarkerBitmap(
    type: PoiType,
    settings: PhoneMapPoiSettings,
): Bitmap =
    loadPhoneMapPoiIconBitmapOrNull(type, settings.iconSize.pixels)?.let { icon ->
        if (settings.markerStyle == PhoneMapPoiMarkerStyle.THEME_ICON) {
            createPhoneMapPoiThemeIconBitmap(icon, settings.iconSize.pixels, type)
        } else {
            createPhoneMapPoiBadgeBitmap(type, icon, settings.iconSize.pixels)
        }
    } ?: createPhoneMapPoiBadgeBitmap(type, null, settings.iconSize.pixels)

private fun Context.loadPhoneMapPoiIconBitmapOrNull(
    type: PoiType,
    sizePx: Int,
): Bitmap? =
    runCatching {
        assets.open("osm/${type.phoneMapPoiAssetName}").use { input ->
            val svg = SVG.getFromInputStream(input)
            svg.setDocumentPreserveAspectRatio(PreserveAspectRatio.LETTERBOX)
            svg.setDocumentWidth("${sizePx}px")
            svg.setDocumentHeight("${sizePx}px")
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
                svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
            }
        }
    }.getOrNull()

private fun createPhoneMapPoiBadgeBitmap(
    type: PoiType,
    iconBitmap: Bitmap?,
    sizePx: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val center = sizePx / 2f
    val canvas = Canvas(bitmap)
    val radius = center - 1f
    canvas.drawCircle(
        center,
        center,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = type.phoneMapPoiMarkerColor
            style = Paint.Style.FILL
        },
    )
    canvas.drawCircle(
        center,
        center,
        radius - 0.8f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        },
    )
    if (iconBitmap != null) {
        val iconRect = RectF(sizePx * 0.17f, sizePx * 0.17f, sizePx * 0.83f, sizePx * 0.83f)
        canvas.drawBitmap(
            iconBitmap,
            null,
            RectF(sizePx * 0.14f, sizePx * 0.14f, sizePx * 0.86f, sizePx * 0.86f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = 210
                colorFilter =
                    android.graphics.PorterDuffColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
            },
        )
        canvas.drawBitmap(
            iconBitmap,
            null,
            iconRect,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter =
                    android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            },
        )
    } else {
        drawPhoneMapPoiLabel(canvas, type, center, sizePx * 0.45f, Color.WHITE)
    }
    return bitmap
}

private fun createPhoneMapPoiThemeIconBitmap(
    iconBitmap: Bitmap,
    sizePx: Int,
    type: PoiType,
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val iconRect = RectF(sizePx * 0.12f, sizePx * 0.12f, sizePx * 0.88f, sizePx * 0.88f)
    if (type == PoiType.CUSTOM) {
        canvas.drawBitmap(
            iconBitmap,
            null,
            RectF(sizePx * 0.02f, sizePx * 0.02f, sizePx * 0.98f, sizePx * 0.98f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = 230
                colorFilter =
                    android.graphics.PorterDuffColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
            },
        )
    }
    canvas.drawBitmap(
        iconBitmap,
        null,
        RectF(sizePx * 0.08f, sizePx * 0.08f, sizePx * 0.92f, sizePx * 0.92f),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = 235
            colorFilter =
                android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        },
    )
    canvas.drawBitmap(
        iconBitmap,
        null,
        iconRect,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter =
                android.graphics.PorterDuffColorFilter(
                    type.phoneMapPoiMarkerColor,
                    android.graphics.PorterDuff.Mode.SRC_IN,
                )
        },
    )
    return bitmap
}

private fun drawPhoneMapPoiLabel(
    canvas: Canvas,
    type: PoiType,
    center: Float,
    textSize: Float,
    color: Int,
) {
    val label = type.phoneMapPoiMarkerLabel
    if (label.isEmpty()) return
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textAlign = Paint.Align.CENTER
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    val metrics = paint.fontMetrics
    canvas.drawText(label, center, center - (metrics.ascent + metrics.descent) / 2f, paint)
}

private val PoiType.phoneMapPoiAssetName: String
    get() =
        when (this) {
            PoiType.PEAK -> "peak.svg"
            PoiType.WATER -> "water.svg"
            PoiType.HUT -> "hut.svg"
            PoiType.CAMP -> "camp.svg"
            PoiType.FOOD -> "food.svg"
            PoiType.TOILET -> "toilet.svg"
            PoiType.TRANSPORT -> "transport.svg"
            PoiType.BIKE -> "bike.svg"
            PoiType.VIEWPOINT -> "viewpoint.svg"
            PoiType.PARKING -> "parking.svg"
            PoiType.SHOP -> "shop.svg"
            PoiType.GENERIC -> "generic.svg"
            PoiType.CUSTOM -> "custom.svg"
        }

internal val PoiType.phoneMapPoiMarkerLabel: String
    get() =
        when (this) {
            PoiType.PEAK -> "M"
            PoiType.WATER -> "W"
            PoiType.HUT -> "H"
            PoiType.CAMP -> "C"
            PoiType.FOOD -> "F"
            PoiType.TOILET -> "T"
            PoiType.TRANSPORT -> "R"
            PoiType.BIKE -> "B"
            PoiType.VIEWPOINT -> "V"
            PoiType.PARKING -> "P"
            PoiType.SHOP -> "S"
            PoiType.GENERIC -> "•"
            PoiType.CUSTOM -> "★"
        }

internal val PoiType.phoneMapPoiMarkerColor: Int
    get() =
        when (this) {
            PoiType.PEAK, PoiType.HUT -> Color.rgb(121, 85, 72)
            PoiType.WATER, PoiType.TRANSPORT -> Color.rgb(3, 169, 244)
            PoiType.CAMP -> Color.rgb(76, 175, 80)
            PoiType.FOOD -> Color.rgb(255, 152, 0)
            PoiType.TOILET -> Color.rgb(156, 39, 176)
            PoiType.BIKE -> Color.rgb(0, 188, 212)
            PoiType.VIEWPOINT -> Color.rgb(255, 193, 7)
            PoiType.PARKING -> Color.rgb(63, 81, 181)
            PoiType.SHOP, PoiType.GENERIC -> Color.rgb(96, 125, 139)
            PoiType.CUSTOM -> Color.rgb(255, 213, 79)
        }
