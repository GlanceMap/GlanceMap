package com.glancemap.glancemapwearos.presentation.features.poi

import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R

// Repository taxonomy keys stay stable; only their presentation is translated.
@StringRes
internal fun poiCategoryNameRes(name: String): Int? = POI_CATEGORY_NAME_RES[name]

private val POI_CATEGORY_NAME_RES =
    mapOf(
        "Alpine Huts" to R.string.poi_alpine_huts,
        "Camping" to R.string.poi_camping,
        "Peaks" to R.string.poi_peaks,
        "Viewpoints" to R.string.poi_viewpoints,
        "Toilets" to R.string.poi_toilets,
        "Food" to R.string.poi_food,
        "Parking" to R.string.poi_parking,
        "Saved places" to R.string.poi_saved_places,
        "Water" to R.string.poi_water,
        "Shelter" to R.string.poi_shelter,
        "Transport" to R.string.poi_transport,
        "Services" to R.string.poi_services,
        "Landmarks" to R.string.poi_landmarks,
        "Sports/Leisure" to R.string.poi_sports_leisure,
    )
