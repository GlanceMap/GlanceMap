package com.glancemap.glancemapcompanionapp.map

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.glancemap.trailcore.map.MapMode
import org.mapsforge.map.reader.MapFile
import java.io.File

/** A locally copied Mapsforge source owned by the companion phone application. */
internal data class PhoneOfflineMap(
    val file: File,
) {
    val displayName: String
        get() = file.name
}

/** The active phone renderer source; Mapsforge and file details remain in the companion. */
internal sealed interface PhoneMapSource {
    val mode: MapMode

    data object Online : PhoneMapSource {
        override val mode: MapMode = MapMode.ONLINE
    }

    data class Offline(
        val map: PhoneOfflineMap,
    ) : PhoneMapSource {
        override val mode: MapMode = MapMode.OFFLINE
    }
}

/** A small renderer-neutral camera bridge for temporary online/offline switching. */
internal data class PhoneMapCameraSnapshot(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(zoom.isFinite() && zoom >= 0.0)
    }
}

internal enum class PhoneOfflineMapError {
    MISSING,
    INVALID,
    IMPORT_FAILED,
}

/**
 * The canonical companion-owned storage for phone-rendered Mapsforge maps.
 *
 * The transfer flow deliberately remains URI-based and watch-only; importing here is solely for
 * the phone map surface.
 */
internal class PhoneOfflineMapStore(
    private val directory: File,
) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    fun discover(): List<PhoneOfflineMap> =
        directory
            .listFiles()
            ?.asSequence()
            ?.filter(::isPhoneOfflineMapCandidate)
            ?.map(::PhoneOfflineMap)
            ?.sortedBy { map -> map.displayName.lowercase() }
            ?.toList()
            .orEmpty()

    /** Opens only the Mapsforge header; callers run it away from the Compose UI thread. */
    fun validate(map: PhoneOfflineMap): PhoneOfflineMapError? {
        if (!isPhoneOfflineMapCandidate(map.file)) return PhoneOfflineMapError.MISSING
        return runCatching {
            MapFile(map.file).close()
        }.fold(
            onSuccess = { null },
            onFailure = { PhoneOfflineMapError.INVALID },
        )
    }

    fun import(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Result<PhoneOfflineMap> =
        runCatching {
            val fileName = resolveFileName(contentResolver, uri)
            require(fileName.endsWith(MAP_EXTENSION, ignoreCase = true))
            check(directory.exists() || directory.mkdirs())

            val destination = nextAvailableMapFile(fileName)
            val temporary = File(directory, "${destination.name}.part")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use(input::copyTo)
                } ?: error("Unable to open selected map.")

                val map = PhoneOfflineMap(temporary)
                check(validate(map) == null)
                check(temporary.renameTo(destination))
                PhoneOfflineMap(destination)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }

    private fun resolveFileName(
        contentResolver: ContentResolver,
        uri: Uri,
    ): String =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor
                    .getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let(cursor::getString)
            }
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.trim()
                .orEmpty()

    private fun nextAvailableMapFile(fileName: String): File {
        val safeFileName = File(fileName).name
        val baseName = safeFileName.substringBeforeLast('.', missingDelimiterValue = safeFileName)
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else " ($index)"
            val candidate = File(directory, "$baseName$suffix$MAP_EXTENSION")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "maps"
        const val MAP_EXTENSION = ".map"
    }
}

internal fun isPhoneOfflineMapCandidate(file: File): Boolean =
    file.isFile &&
        file.canRead() &&
        file.length() > 0L &&
        file.name.endsWith(".map", ignoreCase = true)
