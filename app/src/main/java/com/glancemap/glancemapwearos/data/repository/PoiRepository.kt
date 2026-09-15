package com.glancemap.glancemapwearos.data.repository

import com.glancemap.glancemapwearos.core.maps.GeoBounds
import java.io.File
import java.io.InputStream

data class PoiCategory(
    val id: Int,
    val name: String,
    val parentId: Int?,
    val depth: Int,
    val hasChildren: Boolean,
)

data class PoiViewport(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

data class PoiPoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val name: String?,
    val type: PoiType,
    val details: PoiPointDetails? = null,
)

data class PoiPointDetails(
    val typeLabel: String? = null,
    val elevationMeters: Int? = null,
    val sleepingPlaces: Int? = null,
    val state: String? = null,
    val shortDescription: String? = null,
    val website: String? = null,
    val source: String? = null,
)

enum class PoiType {
    PEAK,
    WATER,
    HUT,
    CAMP,
    FOOD,
    TOILET,
    TRANSPORT,
    BIKE,
    VIEWPOINT,
    PARKING,
    SHOP,
    GENERIC,
    CUSTOM,
}

interface PoiRepository {
    suspend fun listPoiFiles(): List<File>

    @Suppress("LongParameterList")
    suspend fun savePoiFileAtomic(
        fileName: String,
        inputStream: InputStream,
        onProgress: (bytesCopied: Long) -> Unit,
        expectedSize: Long? = null,
        resumeOffset: Long = 0L,
        diagnosticContext: String? = null,
    ): String?

    suspend fun deletePoiFile(path: String): Boolean

    suspend fun fileExists(fileName: String): Boolean

    suspend fun readCategories(path: String): List<PoiCategory>

    suspend fun readCoverageBounds(path: String): GeoBounds?

    /** The GPX file that supplied this imported waypoint folder, if any. */
    suspend fun readLinkedGpxWaypointFileName(path: String): String?

    suspend fun findGpxWaypointPoiFiles(gpxFileName: String): List<File>

    suspend fun updateLinkedGpxWaypointFileName(
        previousGpxFileName: String,
        newGpxFileName: String,
    ): Int

    suspend fun isFileEnabled(path: String): Boolean

    suspend fun setFileEnabled(
        path: String,
        enabled: Boolean,
    )

    suspend fun getEnabledCategories(
        path: String,
        availableCategoryIds: Set<Int>,
    ): Set<Int>

    suspend fun setEnabledCategories(
        path: String,
        enabledCategoryIds: Set<Int>,
    )

    suspend fun countPoiPoints(
        path: String,
        categoryIds: Set<Int>,
    ): Int

    suspend fun queryPoiPointsByCategories(
        path: String,
        categoryIds: Set<Int>,
        limit: Int,
    ): List<PoiPoint>

    suspend fun queryPoiPoints(
        path: String,
        viewport: PoiViewport,
        enabledCategoryIds: Set<Int>,
        limit: Int,
    ): List<PoiPoint>

    suspend fun searchPoiPoints(
        path: String,
        query: String,
        enabledCategoryIds: Set<Int>,
        limit: Int,
    ): List<PoiPoint>

    suspend fun deleteVisibilityState(path: String)
}
