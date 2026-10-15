package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneInstalledBundle
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageLocation
import com.glancemap.glancemapcompanionapp.map.isPhoneDemFileName
import com.glancemap.glancemapcompanionapp.map.isUsablePhoneDemFile
import com.glancemap.glancemapcompanionapp.map.isUsablePhoneRoutingFile
import org.mapsforge.core.model.BoundingBox
import java.io.File
import java.util.Locale

internal enum class PhoneLibraryOfflineSection(
    @StringRes val label: Int,
) {
    AREAS(R.string.library_offline_areas),
    ELEVATION(R.string.library_offline_elevation),
    ROUTING(R.string.library_offline_routing),
}

internal fun discoverPhoneLibraryOfflineData(
    elevationDirectory: File,
    routingDirectory: File,
    bundles: List<PhoneInstalledBundle>,
    location: PhoneOfflineStorageLocation,
    previous: List<PhoneLibraryItem.OfflineData> = emptyList(),
): List<PhoneLibraryItem.OfflineData> {
    val cached = previous.associateBy { it.file.absolutePath }
    return listOf(
        PhoneLibraryOfflineSection.ELEVATION to elevationDirectory,
        PhoneLibraryOfflineSection.ROUTING to routingDirectory,
    ).flatMap { (section, root) ->
        if (!root.isDirectory) return@flatMap emptyList()
        root
            .walkTopDown()
            .maxDepth(3)
            .filter { file ->
                file.isFile &&
                    !file.isHidden &&
                    if (section == PhoneLibraryOfflineSection.ELEVATION) {
                        file.name.isPhoneDemFileName()
                    } else {
                        file.extension.equals("rd5", ignoreCase = true)
                    }
            }.map { file ->
                val old = cached[file.absolutePath]
                // Reopening Library must not decompress every unchanged elevation tile again.
                val usable =
                    if (old != null && old.sizeBytes == file.length() && old.modifiedMillis == file.lastModified()) {
                        old.isUsable
                    } else if (section == PhoneLibraryOfflineSection.ELEVATION) {
                        isUsablePhoneDemFile(file)
                    } else {
                        isUsablePhoneRoutingFile(file)
                    }
                PhoneLibraryItem.OfflineData(
                    file = file,
                    relativePath = file.relativeTo(root).invariantSeparatorsPath,
                    section = section,
                    sizeBytes = file.length(),
                    modifiedMillis = file.lastModified(),
                    isUsable = usable,
                    coverage = phoneLibraryOfflineCoverage(file.name, section),
                    areaNames =
                        bundles.filter { it.usesOfflineFile(file.name, section) }.map { it.areaLabel }.distinct(),
                    storageLocation = location,
                )
            }.toList()
    }.sortedBy { it.title.lowercase(Locale.ROOT) }
}

private fun PhoneInstalledBundle.usesOfflineFile(
    name: String,
    section: PhoneLibraryOfflineSection,
): Boolean =
    if (section == PhoneLibraryOfflineSection.ROUTING) {
        routingFileNames.any { File(it).name.equals(name, ignoreCase = true) }
    } else {
        demTileIds.any { name.substringBefore('.').equals(it, ignoreCase = true) }
    }

internal fun phoneLibraryOfflineCoverage(
    name: String,
    section: PhoneLibraryOfflineSection,
): BoundingBox? {
    val pattern =
        when (section) {
            PhoneLibraryOfflineSection.ELEVATION ->
                Regex("([NS])(\\d{2})([EW])(\\d{3})\\.hgt(?:\\.gz|\\.zip)?", RegexOption.IGNORE_CASE)
            PhoneLibraryOfflineSection.ROUTING ->
                Regex("([EW])(\\d{1,3})_([NS])(\\d{1,2})\\.rd5", RegexOption.IGNORE_CASE)
            PhoneLibraryOfflineSection.AREAS -> null
        }
    val parts = pattern?.matchEntire(name)?.groupValues ?: return null
    val first = signedOfflineCoordinate(parts[1], parts[2])
    val second = signedOfflineCoordinate(parts[3], parts[4])
    val latitude = if (section == PhoneLibraryOfflineSection.ELEVATION) first else second
    val longitude = if (section == PhoneLibraryOfflineSection.ELEVATION) second else first
    val size = if (section == PhoneLibraryOfflineSection.ELEVATION) 1 else 5
    val valid =
        latitude in -90..(90 - size) &&
            longitude in -180..(180 - size) &&
            latitude % size == 0 &&
            longitude % size == 0
    return if (valid) {
        BoundingBox(
            latitude.toDouble(),
            longitude.toDouble(),
            (latitude + size).toDouble(),
            (longitude + size).toDouble(),
        )
    } else {
        null
    }
}

private fun signedOfflineCoordinate(
    hemisphere: String,
    number: String,
): Int = number.toInt() * if (hemisphere.uppercase(Locale.ROOT) in setOf("S", "W")) -1 else 1
