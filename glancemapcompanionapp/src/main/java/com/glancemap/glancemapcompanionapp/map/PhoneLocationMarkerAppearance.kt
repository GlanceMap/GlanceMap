package com.glancemap.glancemapcompanionapp.map

import android.graphics.Color
import androidx.annotation.DrawableRes
import com.glancemap.glancemapcompanionapp.R
import org.maplibre.android.location.LocationComponentOptions

// Match the usual online marker size and keep it stable across map renderers and zoom levels.
internal const val PHONE_LOCATION_MARKER_SCALE = 0.85f
internal const val PHONE_LOCATION_MARKER_HISTORICAL_ARGB: Int = 0xFF9CA3AF.toInt()

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
    locationFresh: Boolean = true,
    compassConeRenderable: Boolean = compassRenderable,
): LocationComponentOptions =
    toBuilder()
        .foregroundDrawable(phoneLocationMarkerDrawable(settings.markerStyle, compassRenderable = false))
        .foregroundTintColor(
            when {
                compassRenderable -> Color.TRANSPARENT
                locationFresh -> null
                else -> PHONE_LOCATION_MARKER_HISTORICAL_ARGB
            },
        )
        .backgroundTintColor(Color.TRANSPARENT)
        .bearingDrawable(phoneLocationMarkerDrawable(settings.markerStyle, compassConeRenderable))
        .bearingTintColor(if (locationFresh) null else PHONE_LOCATION_MARKER_HISTORICAL_ARGB)
        .elevation(0f)
        .minZoomIconScale(PHONE_LOCATION_MARKER_SCALE)
        .maxZoomIconScale(PHONE_LOCATION_MARKER_SCALE)
        // The map uses immediate compass updates; a second SDK animation makes the cone lag behind it.
        .compassAnimationEnabled(false)
        .accuracyAlpha(if (settings.gpsAccuracyCircleEnabled) 0.25f else 0f)
        .build()
