package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteCodec
import com.glancemap.glancemapcompanionapp.refuges.PoiSqlitePoint
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteWriteOptions
import java.io.File

/** Persists phone-created POIs in the same SQLite format already consumed by the map overlay. */
internal class PhoneMapUserPoiStore(
    private val poiDirectoryProvider: () -> File,
) {
    constructor(context: Context) : this({ PhoneOfflineStorage(context).poiDirectory() })

    fun create(
        latitude: Double,
        longitude: Double,
        name: String,
    ): String {
        require(latitude in -90.0..90.0 && latitude.isFinite()) { "Invalid POI latitude." }
        require(longitude in -180.0..180.0 && longitude.isFinite()) { "Invalid POI longitude." }

        val directory = poiDirectoryProvider()
        check(directory.exists() || directory.mkdirs()) { "Unable to create the POI directory." }
        val file = File(directory, USER_POI_FILE_NAME)
        val points = if (file.exists()) PoiSqliteCodec.read(file) else emptyList()
        val normalizedName = normalizePhoneUserPoiName(name)
        val updatedPoints =
            points +
                PoiSqlitePoint(
                    lat = latitude,
                    lon = longitude,
                    categoryName = USER_POI_CATEGORY,
                    tags =
                        linkedMapOf(
                            "name" to normalizedName,
                            "tourism" to "information",
                            "description" to "Created on phone",
                        ),
                )

        // Write a complete replacement first so a codec failure leaves the installed file untouched.
        val temporaryFile = File(directory, ".$USER_POI_FILE_NAME.part")
        if (temporaryFile.exists()) temporaryFile.delete()
        try {
            PoiSqliteCodec.write(
                file = temporaryFile,
                points = updatedPoints,
                options =
                    PoiSqliteWriteOptions(
                        comment = "Phone-created POIs",
                        writer = "glancemap-phone",
                        extraMetadata = mapOf("phone_user_poi" to "true"),
                    ),
            )
            temporaryFile.copyTo(file, overwrite = true)
        } finally {
            temporaryFile.delete()
        }
        return normalizedName
    }

    private companion object {
        const val USER_POI_FILE_NAME = "my-places.poi"
        const val USER_POI_CATEGORY = "Saved places"
    }
}

internal fun normalizePhoneUserPoiName(name: String): String =
    name
        .trim()
        .take(MAX_PHONE_USER_POI_NAME_LENGTH)
        .ifBlank { "Point" }

private const val MAX_PHONE_USER_POI_NAME_LENGTH = 80
