package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.R

/** Third-party or custom names remain untouched when they have no app-owned label. */
internal fun phoneThemeStyleResource(label: String): Int? =
    when (label) {
        "Hiking" -> R.string.map_theme_style_hiking
        "Cycling" -> R.string.map_theme_style_cycling
        "Mountain bike" -> R.string.phone_theme_mountain_bike
        "Mountain biking" -> R.string.map_theme_style_mountain_biking
        "Classic" -> R.string.map_theme_style_classic
        "Dark" -> R.string.map_theme_style_dark
        "Default" -> R.string.map_theme_overlay_default
        "Day · Explorer 1:25k" -> R.string.phone_theme_day_explorer
        "Day · Landranger 1:50k" -> R.string.phone_theme_day_landranger
        "Day · Dynamic scale" -> R.string.phone_theme_day_dynamic
        "Night · Explorer 1:25k" -> R.string.phone_theme_night_explorer
        "Night · Landranger 1:50k" -> R.string.phone_theme_night_landranger
        "Night · Dynamic scale" -> R.string.phone_theme_night_dynamic
        "City" -> R.string.phone_theme_city
        "White ski" -> R.string.phone_theme_white_ski
        "Hiking & Wintersport" -> R.string.phone_theme_hiking_wintersport
        "Road" -> R.string.phone_theme_road
        "Multi" -> R.string.phone_theme_multi
        "Transparent" -> R.string.phone_theme_transparent
        "Yellow" -> R.string.phone_theme_yellow
        else -> null
    }

internal fun PhoneOfflineThemeStyle.localizedLabel(text: (Int) -> String): String =
    phoneThemeStyleResource(label)?.let(text) ?: label
