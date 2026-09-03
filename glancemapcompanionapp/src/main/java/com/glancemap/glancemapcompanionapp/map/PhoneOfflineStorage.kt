package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.os.Environment
import java.io.File

internal enum class PhoneOfflineStorageLocation(
    val label: String,
) {
    INTERNAL("Internal storage / GlanceMap"),
    EXTERNAL("SD card / GlanceMap"),
}

/** File-backed locations shared by Mapsforge, POI, DEM, BRouter, and the phone route library. */
@Suppress("TooManyFunctions") // One storage boundary intentionally exposes each concrete data directory.
internal class PhoneOfflineStorage(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun location(): PhoneOfflineStorageLocation =
        preferences
            .getString(KEY_LOCATION, null)
            ?.let { value ->
                runCatching { PhoneOfflineStorageLocation.valueOf(value) }.getOrNull()
            } ?: PhoneOfflineStorageLocation.INTERNAL

    internal fun setLocation(location: PhoneOfflineStorageLocation) {
        preferences.edit().putString(KEY_LOCATION, location.name).apply()
    }

    fun isExternalAvailable(): Boolean = canonicalRoot(PhoneOfflineStorageLocation.EXTERNAL) != null

    /** The canonical root used for new data and after a successful migration. */
    internal fun canonicalRoot(location: PhoneOfflineStorageLocation): File? {
        val base =
            when (location) {
                PhoneOfflineStorageLocation.INTERNAL -> appContext.filesDir
                PhoneOfflineStorageLocation.EXTERNAL -> removableExternalFilesDir()
            }
        return base?.let { File(it, ROOT_DIRECTORY_NAME) }
    }

    /** Existing releases stored the same managed directories directly below these roots. */
    internal fun legacyRoot(location: PhoneOfflineStorageLocation): File? =
        when (location) {
            PhoneOfflineStorageLocation.INTERNAL -> appContext.filesDir
            PhoneOfflineStorageLocation.EXTERNAL -> appContext.getExternalFilesDir(null)
        }

    internal fun activeRoot(): File {
        val selected = location()
        val canonical = canonicalRoot(selected) ?: requireNotNull(canonicalRoot(PhoneOfflineStorageLocation.INTERNAL))
        return if (canonical.containsPhoneOfflineData()) {
            canonical
        } else {
            legacyRoot(selected)?.takeIf(File::containsPhoneOfflineData) ?: canonical
        }
    }

    @Suppress("MaxLineLength")
    internal fun needsCanonicalMigration(): Boolean = activeRoot().absoluteFile != canonicalRoot(location())?.absoluteFile

    internal fun migrationJournalFile(): File = File(appContext.filesDir, JOURNAL_FILE_NAME)

    fun mapsDirectory(): File = directory("maps")

    fun poiDirectory(): File = directory("refuges-poi")

    fun elevationDirectory(): File = directory("elevation")

    fun routingDirectory(): File = directory("routing-segments")

    fun profilesDirectory(): File = directory("brouter/profiles2")

    fun routesDirectory(): File = directory("route-library")

    fun missionPlanDirectory(): File = directory("mission-plan")

    fun weatherDirectory(): File = directory("weather-forecasts")

    fun watchGpxExportsDirectory(): File = directory("watch-gpx-exports")

    /** Persistent app-private staging area for bundle archives and resumable downloads. */
    internal fun bundleDownloadDirectory(): File = appContext.getDir("phone_offline_bundle_downloads", Context.MODE_PRIVATE)

    private fun directory(name: String): File = File(activeRoot(), name)

    private fun removableExternalFilesDir(): File? =
        appContext
            .getExternalFilesDirs(null)
            .asSequence()
            .filterNotNull()
            .firstOrNull { directory ->
                Environment.isExternalStorageRemovable(directory) &&
                    Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED
            }

    private companion object {
        const val PREFERENCES_NAME = "phone_offline_storage"
        const val KEY_LOCATION = "location"
        const val ROOT_DIRECTORY_NAME = "GlanceMap"
        const val JOURNAL_FILE_NAME = "GlanceMap-storage-migration.properties"
    }
}

/** Pure root selection keeps storage-switch and unavailable-external cases testable off-device. */
internal fun resolvePhoneOfflineStorageRoot(
    location: PhoneOfflineStorageLocation,
    internalRoot: File,
    externalRoot: File?,
): File =
    when (location) {
        PhoneOfflineStorageLocation.INTERNAL -> internalRoot
        PhoneOfflineStorageLocation.EXTERNAL -> externalRoot ?: error("External storage is unavailable.")
    }

internal val PHONE_OFFLINE_MANAGED_DIRECTORY_NAMES =
    listOf(
        "maps",
        "refuges-poi",
        "elevation",
        "routing-segments",
        "brouter",
        "route-library",
        "mission-plan",
        "weather-forecasts",
        "watch-gpx-exports",
    )

@Suppress("MaxLineLength")
private fun File.containsPhoneOfflineData(): Boolean = PHONE_OFFLINE_MANAGED_DIRECTORY_NAMES.any { name -> File(this, name).exists() }
