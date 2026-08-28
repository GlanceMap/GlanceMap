package com.glancemap.glancemapwearos.data.repository

import com.glancemap.glancemapwearos.core.maps.GeoBounds
import com.glancemap.trailcore.poi.PoiDetails
import java.io.File
import java.io.InputStream

typealias PoiPointDetails = PoiDetails
typealias PoiType = com.glancemap.trailcore.poi.PoiType

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

interface PoiRepository {
    suspend fun listPoiFiles(): List<File>

    suspend fun savePoiFileAtomic(
        fileName: String,
        inputStream: InputStream,
        onProgress: (bytesCopied: Long) -> Unit,
        expectedSize: Long? = null,
        resumeOffset: Long = 0L,
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
