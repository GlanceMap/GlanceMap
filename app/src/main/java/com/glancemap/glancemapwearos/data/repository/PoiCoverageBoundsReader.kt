package com.glancemap.glancemapwearos.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.glancemap.glancemapwearos.core.maps.GeoBounds
import com.glancemap.glancemapwearos.core.maps.geoBoundsOrNull
import java.io.File

internal fun readPoiCoverageBounds(path: String): GeoBounds? =
    File(path)
        .takeIf { it.exists() && it.isFile }
        ?.let { poiFile ->
            tracePoiMetadata("coverage_open", poiFile) {
                SQLiteDatabase.openDatabase(poiFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            }.use { db ->
                tracePoiMetadata("coverage_query", poiFile) { queryPoiCoverageBounds(db) }
            }
        }

private fun queryPoiCoverageBounds(db: SQLiteDatabase): GeoBounds? =
    db
        .rawQuery(
            """
            SELECT
                MIN(lat) AS min_lat,
                MAX(lat) AS max_lat,
                MIN(lon) AS min_lon,
                MAX(lon) AS max_lon,
                COUNT(*) AS point_count
            FROM poi_index
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            cursor
                .takeIf { it.moveToFirst() }
                ?.coverageColumns()
                ?.takeIf { cursor.hasCoverageValues(it) }
                ?.let { columns ->
                    geoBoundsOrNull(
                        minLat = cursor.getDouble(columns.minLat),
                        maxLat = cursor.getDouble(columns.maxLat),
                        minLon = cursor.getDouble(columns.minLon),
                        maxLon = cursor.getDouble(columns.maxLon),
                    )
                }
        }

private fun Cursor.coverageColumns(): PoiCoverageColumns? =
    PoiCoverageColumns(
        pointCount = getColumnIndex("point_count"),
        minLat = getColumnIndex("min_lat"),
        maxLat = getColumnIndex("max_lat"),
        minLon = getColumnIndex("min_lon"),
        maxLon = getColumnIndex("max_lon"),
    ).takeIf { columns -> columns.indices.all { it >= 0 } }

private fun Cursor.hasCoverageValues(columns: PoiCoverageColumns): Boolean =
    getLong(columns.pointCount) > 0L &&
        columns.coordinateIndices.none { isNull(it) }

private data class PoiCoverageColumns(
    val pointCount: Int,
    val minLat: Int,
    val maxLat: Int,
    val minLon: Int,
    val maxLon: Int,
) {
    val indices: List<Int> = listOf(pointCount, minLat, maxLat, minLon, maxLon)
    val coordinateIndices: List<Int> = listOf(minLat, maxLat, minLon, maxLon)
}
