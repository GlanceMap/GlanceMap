package com.glancemap.glancemapwearos.data.repository

const val USER_POI_SOURCE_NAME = "mycreation"
const val USER_POI_SOURCE_PATH = "__mycreation__"
const val USER_POI_CATEGORY_ID = 1
const val USER_POI_CATEGORY_NAME = "Saved places"

data class UserPoiRecord(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val createdAtEpochMs: Long,
    val details: PoiPointDetails =
        PoiPointDetails(
            typeLabel = "My creation",
            source = "GlanceMap",
        ),
)

data class UserPoiSourceState(
    val fileEnabled: Boolean,
    val categoryEnabled: Boolean,
    val points: List<UserPoiRecord>,
)
