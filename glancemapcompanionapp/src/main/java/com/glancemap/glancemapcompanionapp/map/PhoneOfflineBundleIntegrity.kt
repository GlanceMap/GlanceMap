package com.glancemap.glancemapcompanionapp.map

import btools.util.Crc32
import com.glancemap.glancemapcompanionapp.routing.isUsableRoutingPackCache
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.math.sqrt

/** The same lightweight routing index check used by the watch before opening an RD5 file. */
@Suppress("ThrowsCount") // Header/trailer validation reports each truncation boundary explicitly.
internal fun validatePhoneRoutingSegmentIndex(file: File) {
    if (!file.exists() || !file.isFile || file.length() <= 0L) {
        throw IOException("Routing segment is missing or empty.")
    }
    RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(ROUTING_INDEX_HEADER_BYTES)
        input.readFully(header)
        val indexCrc = Crc32.crc(header, 0, header.size)
        val index = java.nio.ByteBuffer.wrap(header)
        var trailingIndexPosition = 0L
        repeat(ROUTING_INDEX_ENTRY_COUNT) { entry ->
            val value = index.long
            if (entry == ROUTING_INDEX_ENTRY_COUNT - 1) {
                trailingIndexPosition = value and ROUTING_INDEX_POSITION_MASK
            }
        }
        if (file.length() == trailingIndexPosition) return

        var trailingIndexBytes = ROUTING_TRAILING_INDEX_BYTES
        if (file.length() - trailingIndexPosition > trailingIndexBytes) trailingIndexBytes += 1
        if (file.length() < trailingIndexPosition + trailingIndexBytes) {
            throw IOException("Routing segment is truncated.")
        }
        val trailer = ByteArray(trailingIndexBytes)
        input.seek(trailingIndexPosition)
        input.readFully(trailer)
        val trailerReader = java.nio.ByteBuffer.wrap(trailer)
        trailerReader.long
        val storedIndexCrc = trailerReader.int
        if (storedIndexCrc != indexCrc && (storedIndexCrc xor 2) != indexCrc) {
            throw IOException("Routing segment index checksum failed.")
        }
    }
}

internal fun isUsablePhoneRoutingFile(file: File): Boolean {
    val hasSupportedVersion = isUsableRoutingPackCache(file)
    return hasSupportedVersion && runCatching { validatePhoneRoutingSegmentIndex(file) }.isSuccess
}

internal fun isUsablePhoneDemFile(file: File): Boolean {
    val isNonEmptyFile = file.isFile && file.length() > 0L
    return isNonEmptyFile && runCatching { validatePhoneDemFile(file) }.isSuccess
}

internal fun validatePhoneDemFile(file: File) {
    when {
        file.name.endsWith(".gz", ignoreCase = true) -> validatePhoneDemGzip(file)
        file.name.endsWith(".zip", ignoreCase = true) -> validatePhoneDemZip(file)
        else -> check(isPlausiblePhoneDemSize(file.length())) { "Invalid DEM size." }
    }
}

internal fun isPlausiblePhoneDemSize(size: Long): Boolean {
    if (size <= 0L || size % 2L != 0L) return false
    val rowLength = sqrt((size / 2L).toDouble()).toInt()
    return rowLength * rowLength.toLong() == size / 2L && rowLength in 1201..3601
}

internal fun phoneOfflineDemFile(
    elevationRoot: File,
    source: PhoneOfflineDemSource,
    tileId: String,
): File {
    val normalizedTileId = tileId.uppercase(Locale.ROOT)
    return File(
        File(File(elevationRoot, source.id), normalizedTileId.substring(0, 3)),
        source.remoteFileName(normalizedTileId),
    )
}

internal fun phoneOfflineBundleIntegrity(
    context: android.content.Context,
    bundle: PhoneInstalledBundle,
): List<PhoneOfflineFileIntegrity> {
    val storage = PhoneOfflineStorage(context)
    val entries =
        buildList {
            add(
                "map/${bundle.mapFileName}" to
                    File(storage.mapsDirectory(), File(bundle.mapFileName).name),
            )
            add("poi/${bundle.poiFileName}" to File(storage.poiDirectory(), File(bundle.poiFileName).name))
            bundle.refugesInfoFileName?.let { name ->
                add("refuges.info/$name" to File(storage.poiDirectory(), File(name).name))
            }
            bundle.downloadedRoutingFileNames.forEach { name ->
                add("routing/${File(name).name}" to File(storage.routingDirectory(), File(name).name))
            }
            bundle.downloadedDemTileIds.forEach { tileId ->
                add(
                    "dem/${bundle.demSource.id}/${tileId.uppercase(Locale.ROOT)}" to
                        phoneOfflineDemFile(storage.elevationDirectory(), bundle.demSource, tileId),
                )
            }
        }
    return entries
        .distinctBy { it.first }
        .filter { (_, file) -> file.isFile && file.length() > 0L }
        .map { (name, file) ->
            PhoneOfflineFileIntegrity(
                fileName = name,
                sizeBytes = file.length(),
                lastModifiedMillis = file.lastModified(),
            )
        }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // Validates all bundle asset families in one status pass.
internal fun phoneOfflineBundleHealth(
    context: android.content.Context,
    mapStore: PhoneOfflineMapStore,
    bundle: PhoneInstalledBundle,
    recovery: PhoneOfflineBundleRecovery? = null,
): PhoneOfflineBundleHealth {
    val storage = PhoneOfflineStorage(context)
    val mapFile = File(storage.mapsDirectory(), File(bundle.mapFileName).name)
    val poiFile = File(storage.poiDirectory(), File(bundle.poiFileName).name)
    val refugesFile =
        bundle.refugesInfoFileName?.let { name ->
            File(storage.poiDirectory(), File(name).name)
        }
    val hasMap = mapStore.findValidBundleMap(bundle.mapFileName) != null
    val hasPoi = isPhoneMapPoiFileValid(poiFile)
    val hasRefuges = refugesFile?.let(::isPhoneMapPoiFileValid) == true

    val invalid = linkedSetOf<String>()
    if (mapFile.isFile && !hasMap) invalid += "map"
    if (poiFile.isFile && !hasPoi) invalid += "poi"
    if (refugesFile?.isFile == true && !hasRefuges) invalid += "refuges.info"

    val downloadedRouting =
        bundle.downloadedRoutingFileNames.filter { name ->
            val file = File(storage.routingDirectory(), File(name).name)
            if (file.isFile && !isUsablePhoneRoutingFile(file)) invalid += file.name
            isUsablePhoneRoutingFile(file)
        }
    val downloadedDem =
        bundle.downloadedDemTileIds.filter { tileId ->
            val file = phoneOfflineDemFile(storage.elevationDirectory(), bundle.demSource, tileId)
            val valid = isUsablePhoneDemFile(file)
            if (file.isFile && !valid) invalid += bundle.demSource.remoteFileName(tileId)
            valid
        }
    if (bundle.integrity.isNotEmpty()) {
        val currentIntegrity = phoneOfflineBundleIntegrity(context, bundle).associateBy { it.fileName }
        bundle.integrity.forEach { expected ->
            val actual = currentIntegrity[expected.fileName]
            if (actual == null ||
                actual.sizeBytes != expected.sizeBytes ||
                actual.lastModifiedMillis != expected.lastModifiedMillis
            ) {
                invalid += expected.fileName
            }
        }
    }
    val base =
        phoneOfflineBundleHealth(
            hasMap = hasMap,
            hasPoi = hasPoi,
            expectsRefugesInfo = bundle.refugesInfoFileName != null,
            hasRefugesInfo = hasRefuges,
            expectedRoutingFileNames = bundle.routingFileNames,
            downloadedRoutingFileNames = downloadedRouting,
            expectedDemTileIds = bundle.demTileIds,
            downloadedDemTileIds = downloadedDem,
            hasRecovery = recovery != null,
        )
    val status =
        when {
            recovery != null -> PhoneOfflineBundleStatus.RECOVERY_NEEDED
            invalid.isNotEmpty() -> PhoneOfflineBundleStatus.PARTIAL
            else -> base.status
        }
    return base.copy(
        status = status,
        invalidFileNames = invalid.toList(),
        integrity = bundle.integrity,
        hasRecovery = recovery != null,
    )
}

private fun validatePhoneDemGzip(file: File) {
    val uncompressedSize =
        GZIPInputStream(FileInputStream(file).buffered()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    check(isPlausiblePhoneDemSize(uncompressedSize)) { "Invalid DEM GZIP size." }
}

private fun validatePhoneDemZip(file: File) {
    val entries =
        ZipFile(file).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.name.endsWith(".hgt", ignoreCase = true)
                }.toList()
        }
    check(entries.isNotEmpty() && entries.all { entry -> isPlausiblePhoneDemSize(entry.size) }) {
        "Invalid DEM ZIP."
    }
}

private const val ROUTING_INDEX_ENTRY_COUNT = 25
private const val ROUTING_INDEX_HEADER_BYTES = ROUTING_INDEX_ENTRY_COUNT * Long.SIZE_BYTES
private const val ROUTING_TRAILING_INDEX_BYTES = Long.SIZE_BYTES + (26 * Int.SIZE_BYTES)
private const val ROUTING_INDEX_POSITION_MASK = 0x0000FFFFFFFFFFFFL
