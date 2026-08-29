package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteViewport
import com.glancemap.glancemapcompanionapp.refuges.isReadablePoiSqliteFile
import com.glancemap.glancemapcompanionapp.refuges.readPoiSqliteViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Reads the companion's canonical locally-imported POI files without involving the watch. */
internal class PhoneMapPoiRepository(
    private val poiDirectory: File,
) {
    constructor(context: Context) : this(phoneMapPoiStorageDirectory(context))

    suspend fun queryViewport(
        viewport: PhoneMapViewport,
        limit: Int,
    ): List<PhoneMapPoi> =
        withContext(Dispatchers.IO) {
            if (viewport.zoom < MINIMUM_POI_ZOOM || limit <= 0) return@withContext emptyList()
            val sources =
                poiDirectory
                    .listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.name.endsWith(POI_FILE_EXTENSION, ignoreCase = true) }
                    ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                    ?.toList()
                    .orEmpty()
            if (sources.isEmpty()) return@withContext emptyList()

            val pois = mutableListOf<PhoneMapPoi>()
            val perSourceLimit = max(MINIMUM_PER_SOURCE_LIMIT, limit / sources.size)
            for (source in sources) {
                val remaining = limit - pois.size
                if (remaining <= 0) break
                val points =
                    runCatching {
                        readPoiSqliteViewport(
                            file = source,
                            viewport =
                                PoiSqliteViewport(
                                    minLat = viewport.minLat,
                                    maxLat = viewport.maxLat,
                                    minLon = viewport.minLon,
                                    maxLon = viewport.maxLon,
                                ),
                            limit = min(perSourceLimit, remaining),
                        )
                    }.getOrDefault(emptyList())
                points.mapNotNullTo(pois) { point ->
                    point.toPhoneMapPoi(sourceKey = source.name)
                }
            }
            pois
        }

    private companion object {
        private const val MINIMUM_PER_SOURCE_LIMIT = 20
        private const val MINIMUM_POI_ZOOM = 10.0
        private const val POI_FILE_EXTENSION = ".poi"
    }
}

internal fun phoneMapPoiStorageDirectory(context: Context): File = File(context.filesDir, "refuges-poi")

internal fun isPhoneMapPoiFileValid(file: File): Boolean = isReadablePoiSqliteFile(file)
