package com.glancemap.glancemapcompanionapp.map

internal data class PhonePoiCreationState(
    val point: PhoneMapCoordinate?,
    val name: String,
    val saving: Boolean,
    val saveFailed: Boolean,
)

internal data class PhonePoiCreationActions(
    val onPickMap: () -> Unit,
    val onCoordinatesSelected: (PhoneMapCoordinate) -> Unit,
    val onNameChanged: (String) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)

internal enum class PhonePoiCreationStep { LOCATION, COORDINATES, REVIEW }

internal fun parsePhonePoiAxis(
    text: String,
    latitude: Boolean,
): Double? {
    val limit = if (latitude) 90.0 else 180.0
    return text
        .trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in -limit..limit }
}

internal fun parsePhonePoiCoordinate(
    latitude: String,
    longitude: String,
): PhoneMapCoordinate? {
    val lat = parsePhonePoiAxis(latitude, latitude = true)
    val lon = parsePhonePoiAxis(longitude, latitude = false)
    return if (lat != null && lon != null) PhoneMapCoordinate(lat, lon) else null
}
