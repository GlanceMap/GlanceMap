package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.oam.OamDownloadArea
import java.io.File
import java.util.zip.ZipFile

/** Resolves bundle names from metadata hints, then lets the caller validate the actual file. */
internal fun <T> resolvePhoneOfflineBundleLocalAsset(
    candidateFileNames: Iterable<String?>,
    findValidAsset: (String) -> T?,
): T? =
    candidateFileNames
        .mapNotNull { candidate -> candidate?.let { File(it).name.takeIf(String::isNotBlank) } }
        .distinct()
        .firstNotNullOfOrNull(findValidAsset)

internal fun phoneOfflineBundleMapCandidateFileNames(
    area: OamDownloadArea,
    completedFileName: String?,
    recoveryFileName: String?,
): List<String?> =
    listOf(
        completedFileName,
        recoveryFileName,
        "${area.region}_oam.osm.map",
        "${area.region}.map",
    )

internal fun phoneOfflineBundlePoiCandidateFileNames(
    area: OamDownloadArea,
    completedFileName: String?,
    recoveryFileName: String?,
): List<String?> =
    listOf(
        completedFileName,
        recoveryFileName,
        "${area.region}.poi",
    )

/** Reuses only a complete archive that can be opened and contains the expected safe entry. */
@Suppress("ReturnCount") // Each invalid archive condition must remove only that archive before a retry.
internal fun reusablePhoneBundleArchiveOrNull(
    directory: File,
    fileName: String,
    entryExtension: String,
    expectedSize: Long? = null,
): File? {
    val archive = File(directory, File(fileName).name)
    if (!archive.isFile || archive.length() <= 0L) return null
    if (expectedSize != null && expectedSize > 0L && archive.length() != expectedSize) {
        archive.delete()
        return null
    }
    val containsExpectedEntry =
        runCatching {
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory &&
                        expectedPhoneBundleArchiveEntryName(entry.name, entryExtension) != null
                }
            }
        }.getOrDefault(false)
    if (containsExpectedEntry) return archive
    archive.delete()
    return null
}
