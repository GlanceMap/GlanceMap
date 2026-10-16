package com.glancemap.glancemapcompanionapp.map

import android.graphics.Color
import androidx.annotation.DrawableRes
import com.glancemap.glancemapcompanionapp.R
import org.maplibre.android.location.LocationComponentOptions

@DrawableRes
internal fun phoneLocationMarkerDrawable(
    style: PhoneMapMarkerStyle,
    compassRenderable: Boolean,
): Int =
    when {
        style == PhoneMapMarkerStyle.TRIANGLE -> R.drawable.map_location_marker_triangle
        compassRenderable -> R.drawable.map_location_marker_dot_compass
        else -> R.drawable.map_location_marker_dot
    }

internal fun LocationComponentOptions.withPhoneLocationMarker(
    settings: PhoneMapSettings,
    compassRenderable: Boolean,
): LocationComponentOptions =
    toBuilder()
        .foregroundDrawable(phoneLocationMarkerDrawable(settings.markerStyle, compassRenderable = false))
        .foregroundTintColor(if (compassRenderable) Color.TRANSPARENT else null)
        .backgroundTintColor(Color.TRANSPARENT)
        .bearingDrawable(phoneLocationMarkerDrawable(settings.markerStyle, compassRenderable = true))
        .bearingTintColor(null)
        .elevation(0f)
        // The map uses immediate compass updates; a second SDK animation makes the cone lag behind it.
        .compassAnimationEnabled(false)
        .accuracyAlpha(if (settings.gpsAccuracyCircleEnabled) 0.25f else 0f)
        .build()
