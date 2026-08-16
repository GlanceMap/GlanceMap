package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class UserPoiRepository(
    private val context: Context,
) {
    private companion object {
        private const val STORE_FILE_NAME = "mycreation.json"
        private const val KEY_FILE_ENABLED = "fileEnabled"
        private const val KEY_CATEGORY_ENABLED = "categoryEnabled"
        private const val KEY_POINTS = "points"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_CREATED_AT = "createdAtEpochMs"
    }

    suspend fun readSourceState(): UserPoiSourceState = readStore()

    suspend fun createPoi(
        lat: Double,
        lon: Double,
    ): UserPoiRecord {
        val current = readStore()
        val nextNumber = (current.points.size + 1).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val record =
            UserPoiRecord(
                id = now,
                name = "Point $nextNumber",
                lat = lat,
                lon = lon,
                createdAtEpochMs = now,
            )
        writeStore(
            current.copy(
                fileEnabled = true,
                categoryEnabled = true,
                points = current.points + record,
            ),
        )
        return record
    }

    suspend fun renamePoi(
        id: Long,
        newName: String,
    ) {
        val normalized = newName.trim()
        require(normalized.isNotBlank()) { "Name cannot be empty." }
        val current = readStore()
        val updated =
            current.points.map { point ->
                if (point.id == id) point.copy(name = normalized) else point
            }
        require(updated.any { it.id == id }) { "POI not found." }
        writeStore(current.copy(points = updated))
    }

    suspend fun deletePoi(id: Long) {
        val current = readStore()
        val updated = current.points.filterNot { it.id == id }
        writeStore(
            current.copy(
                points = updated,
                fileEnabled = current.fileEnabled && updated.isNotEmpty(),
                categoryEnabled = current.categoryEnabled && updated.isNotEmpty(),
            ),
        )
    }

    suspend fun setFileEnabled(enabled: Boolean) {
        val current = readStore()
        writeStore(
            current.copy(
                fileEnabled = enabled,
                categoryEnabled = enabled,
            ),
        )
    }

    suspend fun setCategoryEnabled(enabled: Boolean) {
        val current = readStore()
        writeStore(
            current.copy(
                fileEnabled = enabled,
                categoryEnabled = enabled,
            ),
        )
    }

    suspend fun clearAll() {
        writeStore(
            UserPoiSourceState(
                fileEnabled = false,
                categoryEnabled = false,
                points = emptyList(),
            ),
        )
    }

    private fun readStore(): UserPoiSourceState {
        val json =
            storeFile
                .takeIf { it.exists() }
                ?.let { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }

        val points =
            json
                ?.optJSONArray(KEY_POINTS)
                ?.toUserPoiRecords()
                .orEmpty()

        return UserPoiSourceState(
            fileEnabled = json?.optBoolean(KEY_FILE_ENABLED, points.isNotEmpty()) ?: false,
            categoryEnabled = json?.optBoolean(KEY_CATEGORY_ENABLED, points.isNotEmpty()) ?: false,
            points = points.sortedBy { it.createdAtEpochMs },
        )
    }

    private fun writeStore(state: UserPoiSourceState) {
        val target = storeFile
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val json =
            JSONObject().apply {
                put(KEY_FILE_ENABLED, state.fileEnabled)
                put(KEY_CATEGORY_ENABLED, state.categoryEnabled)
                put(
                    KEY_POINTS,
                    JSONArray().apply {
                        state.points.forEach { point ->
                            put(
                                JSONObject().apply {
                                    put(KEY_ID, point.id)
                                    put(KEY_NAME, point.name)
                                    put(KEY_LAT, point.lat)
                                    put(KEY_LON, point.lon)
                                    put(KEY_CREATED_AT, point.createdAtEpochMs)
                                },
                            )
                        }
                    },
                )
            }
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun JSONArray.toUserPoiRecords(): List<UserPoiRecord> =
        buildList(length()) {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { item ->
                    val id = item.optLong(KEY_ID, 0L)
                    val name = item.optString(KEY_NAME).trim()
                    val lat = item.optDouble(KEY_LAT, Double.NaN)
                    val lon = item.optDouble(KEY_LON, Double.NaN)
                    val validCoordinates = lat.isFinite() && lon.isFinite()
                    if (id > 0L && name.isNotBlank() && validCoordinates) {
                        add(
                            UserPoiRecord(
                                id = id,
                                name = name,
                                lat = lat,
                                lon = lon,
                                createdAtEpochMs = item.optLong(KEY_CREATED_AT, id),
                            ),
                        )
                    }
                }
            }
        }

    private val storeFile: File
        get() = File(context.getDir("user_poi", Context.MODE_PRIVATE), STORE_FILE_NAME)
}
