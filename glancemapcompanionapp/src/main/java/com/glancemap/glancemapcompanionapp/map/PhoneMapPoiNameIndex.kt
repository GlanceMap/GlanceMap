package com.glancemap.glancemapcompanionapp.map

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.glancemap.glancemapcompanionapp.refuges.visitPoiSqlitePoints
import com.glancemap.trailcore.poi.PoiSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** FTS4 is available on our older Android devices; imported POI files are always opened read-only. */
internal class PhoneMapPoiNameIndex(
    private val cacheDirectory: File,
) {
    private val mutex = Mutex()

    suspend fun search(
        source: File,
        query: String,
    ): List<PhoneMapSearchResult> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    continuation.resumeWith(runCatching { searchSource(source, query, cancellation) })
                }
            }
        }

    private fun searchSource(
        source: File,
        query: String,
        cancellation: CancellationSignal,
    ): List<PhoneMapSearchResult> {
        val expression = phoneMapPoiSearchExpression(query)
        if (expression.isBlank()) return emptyList()
        if (!source.isFile) throw IOException("POI source unavailable")
        cancellation.throwIfCanceled()
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) throw IOException("Search index unavailable")
        val key = MessageDigest.getInstance("SHA-256").digest(source.canonicalPath.toByteArray())
        val index = File(cacheDirectory, key.joinToString("") { "%02x".format(it) } + ".sqlite")
        val database = SQLiteDatabase.openOrCreateDatabase(index, null)
        try {
            database.execSQL("CREATE TABLE IF NOT EXISTS revision (value TEXT)")
            database.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS names USING fts4(" +
                    "name, poi_id, lat, lon, label, " +
                    "notindexed=poi_id, notindexed=lat, notindexed=lon, notindexed=label)",
            )
            val revision = source.searchRevision()
            val currentRevision =
                database.rawQuery("SELECT value FROM revision LIMIT 1", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            if (revision != currentRevision) rebuild(database, source, revision, cancellation)
            return readMatches(database, source.name, expression, cancellation)
        } finally {
            database.close()
        }
    }

    private fun rebuild(
        database: SQLiteDatabase,
        source: File,
        revision: String,
        cancellation: CancellationSignal,
    ) {
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM names")
            database
                .compileStatement("INSERT INTO names(name, poi_id, lat, lon, label) VALUES (?, ?, ?, ?, ?)")
                .use { insert ->
                    visitPoiSqlitePoints(source, cancellation) { point ->
                        val location = PhoneMapCoordinate(point.lat, point.lon)
                        val label = PoiSemantics.displayName(point.tags)
                        if (point.sourceId != null && label != null && location.isValidSearchPoint()) {
                            val names =
                                point.tags
                                    .filterKeys { it == "name" || it.startsWith("name:") || it == "alt_name" }
                                    .values
                                    .joinToString(" ", transform = ::normalizePhoneMapSearch)
                            insert.bindString(1, names)
                            insert.bindLong(2, point.sourceId)
                            insert.bindDouble(3, point.lat)
                            insert.bindDouble(4, point.lon)
                            insert.bindString(5, label)
                            insert.executeInsert()
                        }
                    }
                }
            cancellation.throwIfCanceled()
            if (source.searchRevision() != revision) throw IOException("POI source changed during indexing")
            database.execSQL("DELETE FROM revision")
            database.execSQL("INSERT INTO revision(value) VALUES (?)", arrayOf(revision))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun readMatches(
        database: SQLiteDatabase,
        sourceName: String,
        expression: String,
        cancellation: CancellationSignal,
    ): List<PhoneMapSearchResult> =
        database
            .rawQuery(
                "SELECT poi_id, lat, lon, label FROM names WHERE name MATCH ? ORDER BY label LIMIT ?",
                arrayOf(expression, PHONE_MAP_SEARCH_LIMIT.toString()),
                cancellation,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        cancellation.throwIfCanceled()
                        add(
                            PhoneMapSearchResult(
                                id = "local:$sourceName:${cursor.getLong(0)}",
                                label = cursor.getString(3),
                                detail = sourceName,
                                point = PhoneMapCoordinate(cursor.getDouble(1), cursor.getDouble(2)),
                            ),
                        )
                    }
                }
            }
}

private fun File.searchRevision(): String = "1:${length()}:${lastModified()}"
