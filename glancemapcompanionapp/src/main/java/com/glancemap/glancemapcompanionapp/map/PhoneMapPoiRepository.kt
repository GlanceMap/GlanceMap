package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
    private val poiDirectoryProvider: () -> File,
) {
    constructor(directory: File) : this({ directory })

    constructor(context: Context) : this({ PhoneOfflineStorage(context).poiDirectory() })

    suspend fun queryViewport(
        viewport: PhoneMapViewport,
        limit: Int,
        enabledSourceFileNames: Set<String>? = null,
    ): List<PhoneMapPoi> =
        withContext(Dispatchers.IO) {
            if (viewport.zoom < MINIMUM_POI_ZOOM || limit <= 0) return@withContext emptyList()
            val sources =
                poiSourceFiles().filter { source ->
                    enabledSourceFileNames == null || source.name in enabledSourceFileNames
                }
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

    suspend fun sources(disabledSourceFileNames: Set<String> = emptySet()): List<PhoneMapPoiSource> =
        withContext(Dispatchers.IO) {
            poiSourceFiles().map { file ->
                val isReadable = isPhoneMapPoiFileValid(file)
                PhoneMapPoiSource(
                    fileName = file.name,
                    isReadable = isReadable,
                    isEnabled = file.name !in disabledSourceFileNames,
                    poiCount = file.takeIf { isReadable }?.let(::readPoiPointCount),
                )
            }
        }

    suspend fun renameSource(
        fileName: String,
        newName: String,
    ): String =
        withContext(Dispatchers.IO) {
            val source = managedPoiFile(fileName)
            require(source.isFile) { "The POI source could not be found." }
            val target = File(poiDirectoryProvider(), phoneMapPoiFileName(newName))
            if (source == target) return@withContext source.name
            require(!target.exists()) { "A POI source with that name already exists." }
            require(source.renameTo(target)) { "The POI source could not be renamed." }
            target.name
        }

    suspend fun deleteSource(fileName: String) {
        withContext(Dispatchers.IO) {
            val source = managedPoiFile(fileName)
            require(!source.exists() || source.delete()) { "The POI source could not be deleted." }
        }
    }

    private fun readPoiPointCount(file: File): Int? =
        runCatching {
            val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                database.rawQuery("SELECT COUNT(*) FROM poi_index", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getLong(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else {
                        0
                    }
                }
            } finally {
                database.close()
            }
        }.getOrNull()

    private fun poiSourceFiles(): List<File> =
        poiDirectoryProvider()
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(POI_FILE_EXTENSION, ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.toList()
            .orEmpty()

    private fun managedPoiFile(fileName: String): File {
        val safeName = File(fileName).name
        require(safeName == fileName && safeName.endsWith(POI_FILE_EXTENSION, ignoreCase = true)) {
            "The POI source is outside the app storage."
        }
        val directory = poiDirectoryProvider()
        val file = File(directory, safeName)
        require(
            runCatching { file.canonicalFile.parentFile?.canonicalFile == directory.canonicalFile }.getOrDefault(false),
        ) { "The POI source is outside the app storage." }
        return file
    }

    private companion object {
        private const val MINIMUM_PER_SOURCE_LIMIT = 20
        private const val MINIMUM_POI_ZOOM = 10.0
        private const val POI_FILE_EXTENSION = ".poi"
    }
}

internal fun phoneMapPoiStorageDirectory(context: Context): File = PhoneOfflineStorage(context).poiDirectory()

internal fun isPhoneMapPoiFileValid(file: File): Boolean = isReadablePoiSqliteFile(file)

internal fun phoneMapPoiFileName(value: String): String {
    val trimmed = value.trim().replace(Regex("\\s+"), " ")
    require(trimmed.isNotBlank()) { "Enter a POI name first." }
    val safeName = File(trimmed).name
    require(safeName == trimmed) { "Enter a POI name without a folder path." }
    val baseName = safeName.removePhonePoiFileExtension(".poi").trim()
    require(baseName.isNotBlank()) { "Enter a POI name first." }
    return "$baseName.poi"
}

private fun String.removePhonePoiFileExtension(extension: String): String =
    takeIf { endsWith(extension, ignoreCase = true) }
        ?.dropLast(extension.length)
        ?: this
