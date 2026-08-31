@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.map

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.hills.DemFile
import org.mapsforge.map.layer.hills.DemFileFS
import org.mapsforge.map.layer.hills.DemFolder
import org.mapsforge.map.layer.hills.HgtFileInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Phone-owned DEM storage. Files are deliberately imported into private storage before render. */
internal class PhoneElevationStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val storage = PhoneOfflineStorage(context)

    val directory: File
        get() = storage.elevationDirectory()

    fun directory(source: PhoneOfflineDemSource): File = File(directory, source.id)

    fun readDirectories(): List<File> {
        val selectedSource = PhoneMapSettingsPreferences(appContext).load().demSource
        return listOf(directory(selectedSource)) +
            PhoneOfflineDemSource.entries.filterNot { source -> source == selectedSource }.map(::directory) +
            directory
    }

    fun hasData(): Boolean = directory.containsPhoneDemFile()

    fun import(
        contentResolver: ContentResolver,
        uris: List<Uri>,
    ): Int {
        if (uris.isEmpty() || (!directory.exists() && !directory.mkdirs())) return 0
        var imported = 0
        uris.forEach { uri ->
            val name = displayName(contentResolver, uri) ?: return@forEach
            if (!name.isPhoneDemFileName()) return@forEach
            val destination = nextDestination(name)
            val copied =
                runCatching {
                    val input = contentResolver.openInputStream(uri) ?: return@runCatching false
                    input.use { source ->
                        destination.outputStream().use { output -> source.copyTo(output) }
                    }
                    true
                }.getOrDefault(false)
            if (copied && destination.isFile && destination.length() > 0L) {
                imported += 1
            } else {
                destination.delete()
            }
        }
        return imported
    }

    private fun displayName(
        contentResolver: ContentResolver,
        uri: Uri,
    ): String? =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.getString(index.takeIf { it >= 0 } ?: return@use null)
                } else {
                    null
                }
            }
        }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun nextDestination(name: String): File {
        val safeName = File(name).name
        val base = File(directory, safeName)
        if (!base.exists()) return base
        val stem = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "hgt")
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem ($index).$extension")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }
}

internal fun File.containsPhoneDemFile(): Boolean =
    exists() &&
        isDirectory &&
        walkTopDown()
            .maxDepth(PHONE_DEM_SCAN_MAX_DEPTH)
            .any { file -> file.isFile && file.name.isPhoneDemFileName() && file.length() > 0L }

private fun String.isPhoneDemFileName(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return lower.endsWith(".hgt") || lower.endsWith(".hgt.gz") || lower.endsWith(".hgt.zip")
}

/** Small bounded HGT reader shared by live elevation and the phone relief overlay. */
internal class PhoneElevationRepository(
    private val demRootDirsProvider: () -> List<File>,
) {
    constructor(demRootDirs: List<File>) : this({ demRootDirs })

    constructor(context: Context) : this({ PhoneElevationStore(context).readDirectories() })

    private val demRootDirs: List<File>
        get() = demRootDirsProvider()

    private val tileCache =
        object : LinkedHashMap<String, PhoneDemTile?>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, PhoneDemTile?>?,
            ): Boolean = size > PHONE_DEM_CACHE_ENTRIES
        }

    fun hasData(): Boolean = demRootDirs.any(File::containsPhoneDemFile)

    fun invalidate() {
        synchronized(tileCache) { tileCache.clear() }
    }

    fun elevationAt(
        latitude: Double,
        longitude: Double,
    ): Double? {
        val safeLatitude = latitude.coerceIn(-89.999999, 89.999999)
        val safeLongitude = longitude.coerceIn(-179.999999, 179.999999)
        val latTile = floor(safeLatitude).toInt()
        val lonTile = floor(safeLongitude).toInt()
        val tile = loadTile(latTile, lonTile) ?: return null
        return interpolate(tile, latTile, lonTile, safeLatitude, safeLongitude)
    }

    internal fun loadTile(
        latitudeTile: Int,
        longitudeTile: Int,
    ): PhoneDemTile? {
        val tileId = phoneDemTileId(latitudeTile, longitudeTile)
        synchronized(tileCache) {
            if (tileCache.containsKey(tileId)) return tileCache[tileId]
        }
        val loaded =
            runCatching {
                resolveDemFile(tileId)?.let { file ->
                    readDemBytes(file)?.let(::decodeDemBytes)
                }
            }.onFailure { error -> Log.w(PHONE_TERRAIN_TAG, "Could not read DEM $tileId", error) }
                .getOrNull()
        synchronized(tileCache) { tileCache[tileId] = loaded }
        return loaded
    }

    internal fun elevationAtUsingTile(
        tile: PhoneDemTile,
        latitudeTile: Int,
        longitudeTile: Int,
        latitude: Double,
        longitude: Double,
    ): Double? =
        if (floor(latitude).toInt() == latitudeTile && floor(longitude).toInt() == longitudeTile) {
            interpolate(tile, latitudeTile, longitudeTile, latitude, longitude)
        } else {
            elevationAt(latitude, longitude)
        }

    private fun resolveDemFile(tileId: String): File? =
        demRootDirs
            .asSequence()
            .flatMap { root -> phoneDemTileCandidates(root, tileId) }
            .firstOrNull { file -> file.isFile && file.length() > 0L }

    private fun readDemBytes(file: File): ByteArray? =
        when {
            file.name.endsWith(".zip", ignoreCase = true) ->
                ZipInputStream(FileInputStream(file).buffered()).use { zip ->
                    val entry =
                        generateSequence { zip.nextEntry }
                            .firstOrNull { item ->
                                !item.isDirectory && item.name.endsWith(".hgt", ignoreCase = true)
                            }
                    entry?.let { zip.readBytes() }
                }
            file.name.endsWith(".gz", ignoreCase = true) ->
                GZIPInputStream(FileInputStream(file).buffered()).use(InputStream::readBytes)
            else -> file.readBytes()
        }

    @Suppress("ReturnCount") // Invalid or non-square DEM input exits before allocating a tile.
    private fun decodeDemBytes(bytes: ByteArray): PhoneDemTile? {
        if (bytes.size < 4 || bytes.size % 2 != 0) return null
        val pointCount = bytes.size / 2
        val rowLength = kotlin.math.sqrt(pointCount.toDouble()).toInt()
        if (rowLength < 2 || rowLength * rowLength != pointCount) return null
        val samples = ShortArray(pointCount)
        var offset = 0
        samples.indices.forEach { index ->
            val highByte = bytes[offset].toInt() and 0xff
            val lowByte = bytes[offset + 1].toInt() and 0xff
            samples[index] = ((highByte shl 8) or lowByte).toShort()
            offset += 2
        }
        return PhoneDemTile(
            axisLength = rowLength - 1,
            rowLength = rowLength,
            samples = samples,
        )
    }

    private fun interpolate(
        tile: PhoneDemTile,
        latitudeTile: Int,
        longitudeTile: Int,
        latitude: Double,
        longitude: Double,
    ): Double? {
        val axis = tile.axisLength
        val row = ((1.0 - (latitude - latitudeTile)) * axis).coerceIn(0.0, axis.toDouble())
        val column = ((longitude - longitudeTile) * axis).coerceIn(0.0, axis.toDouble())
        val row0 = floor(row).toInt().coerceIn(0, axis)
        val column0 = floor(column).toInt().coerceIn(0, axis)
        val row1 = min(axis, row0 + 1)
        val column1 = min(axis, column0 + 1)
        val rowFraction = (row - row0).coerceIn(0.0, 1.0)
        val columnFraction = (column - column0).coerceIn(0.0, 1.0)
        val samples =
            listOf(
                tile.samples[row0 * tile.rowLength + column0] to (1.0 - rowFraction) * (1.0 - columnFraction),
                tile.samples[row0 * tile.rowLength + column1] to (1.0 - rowFraction) * columnFraction,
                tile.samples[row1 * tile.rowLength + column0] to rowFraction * (1.0 - columnFraction),
                tile.samples[row1 * tile.rowLength + column1] to rowFraction * columnFraction,
            )
        var value = 0.0
        var weight = 0.0
        samples.forEach { (sample, sampleWeight) ->
            if (sample != PHONE_DEM_VOID_SAMPLE && sampleWeight > 0.0) {
                value += sample.toDouble() * sampleWeight
                weight += sampleWeight
            }
        }
        return value.takeIf { weight > 0.0 }?.let { value / weight }
    }
}

internal data class PhoneDemTile(
    val axisLength: Int,
    val rowLength: Int,
    val samples: ShortArray,
)

internal fun phoneDemTileId(
    latitudeTile: Int,
    longitudeTile: Int,
): String =
    "${if (latitudeTile >= 0) "N" else "S"}${kotlin.math.abs(latitudeTile).toString().padStart(2, '0')}" +
        "${if (longitudeTile >= 0) "E" else "W"}${kotlin.math.abs(longitudeTile).toString().padStart(3, '0')}"

private fun phoneDemTileCandidates(
    root: File,
    tileId: String,
): Sequence<File> {
    val folder = tileId.substring(0, 3)
    return sequenceOf(
        File(File(root, folder), "$tileId.hgt"),
        File(File(root, folder), "$tileId.hgt.gz"),
        File(File(root, folder), "$tileId.hgt.zip"),
        File(root, "$tileId.hgt"),
        File(root, "$tileId.hgt.gz"),
        File(root, "$tileId.hgt.zip"),
    )
}

/** Mapsforge adapter for the same .hgt/.hgt.gz/.hgt.zip files used by the watch. */
internal class PhoneMapsforgeDemFolder(
    private val roots: List<File>,
    requiredTileIds: Set<String>? = null,
) : DemFolder {
    private val required = requiredTileIds?.mapTo(linkedSetOf()) { it.uppercase(Locale.ROOT) }

    override fun files(): Iterable<DemFile> {
        val candidates =
            required?.asSequence()?.flatMap { id ->
                roots.asSequence().flatMap { root -> phoneDemTileCandidates(root, id) }
            } ?: roots.asSequence().flatMap { root ->
                if (root.isDirectory) root.walkTopDown().filter { it.isFile } else emptySequence()
            }
        return candidates
            .filter { it.isFile && it.length() > 0L }
            .mapNotNull(::phoneMapsforgeDemFile)
            .distinctBy { it.name.uppercase(Locale.ROOT) }
            .toList()
    }

    override fun subs(): Iterable<DemFolder> = emptyList()
}

private fun phoneMapsforgeDemFile(file: File): DemFile? {
    val lower = file.name.lowercase(Locale.ROOT)
    val source =
        when {
            lower.endsWith(".hgt") -> DemFileFS(file)
            lower.endsWith(".hgt.gz") -> PhoneGzipDemFile(file)
            lower.endsWith(".hgt.zip") -> PhoneZipDemFile(file)
            else -> return null
        }
    return limitPhoneHillshadeDemFileInput(source)
}

private fun limitPhoneHillshadeDemFileInput(source: DemFile): DemFile {
    val sourceAxis = HgtFileInfo.computeAxisLen(source.size)
    val stride = phoneHillshadeDownsamplingStride(sourceAxis)
    return if (stride == 1) source else PhoneDownsampledDemFile(source, sourceAxis, stride)
}

internal fun phoneHillshadeDownsamplingStride(
    sourceAxisLength: Int,
    maxAxisLength: Int = PHONE_HILLSHADE_MAX_INPUT_AXIS,
): Int {
    if (sourceAxisLength <= 0 || sourceAxisLength <= maxAxisLength) return 1
    var stride = ((sourceAxisLength + maxAxisLength - 1) / maxAxisLength).coerceAtLeast(1)
    while (sourceAxisLength % stride != 0) stride += 1
    return stride
}

private class PhoneGzipDemFile(
    private val file: File,
) : DemFile {
    @Volatile private var cachedSize: Long? = null

    override fun getName(): String = file.name.removeSuffix(".gz")

    override fun getSize(): Long = cachedSize ?: readGzipSize(file).also { cachedSize = it }

    override fun openInputStream(bufferSize: Int): InputStream =
        GZIPInputStream(
            BufferedInputStream(FileInputStream(file), bufferSize),
        )

    override fun asStream(): InputStream = openInputStream(DemFile.BufferSizeDefault)

    override fun asRawStream(): InputStream = openInputStream(DemFile.BufferSizeRaw)
}

private class PhoneZipDemFile(
    private val file: File,
) : DemFile {
    override fun getName(): String = file.name.removeSuffix(".zip")

    override fun getSize(): Long =
        ZipFile(file).use { zip ->
            val entry = zip.firstPhoneHgtEntry() ?: return 0L
            entry.size.takeIf { it > 0L } ?: zip.getInputStream(entry).use { it.readBytes().size.toLong() }
        }

    override fun openInputStream(bufferSize: Int): InputStream {
        val zip = ZipFile(file)
        val entry =
            zip.firstPhoneHgtEntry() ?: run {
                zip.close()
                error("No HGT entry in ${file.name}")
            }
        return object : FilterInputStream(BufferedInputStream(zip.getInputStream(entry), bufferSize)) {
            override fun close() {
                runCatching { super.close() }
                zip.close()
            }
        }
    }

    override fun asStream(): InputStream = openInputStream(DemFile.BufferSizeDefault)

    override fun asRawStream(): InputStream = openInputStream(DemFile.BufferSizeRaw)
}

private fun ZipFile.firstPhoneHgtEntry() =
    entries().asSequence().firstOrNull { entry ->
        !entry.isDirectory && entry.name.endsWith(".hgt", ignoreCase = true)
    }

private fun readGzipSize(file: File): Long {
    if (file.length() < PHONE_GZIP_FOOTER_BYTES) return 0L
    RandomAccessFile(file, "r").use { randomAccessFile ->
        randomAccessFile.seek(file.length() - PHONE_GZIP_FOOTER_BYTES)
        var size = 0L
        repeat(PHONE_GZIP_FOOTER_BYTES) { index ->
            size = size or ((randomAccessFile.readUnsignedByte().toLong() and 0xffL) shl (index * 8))
        }
        return size
    }
}

private class PhoneDownsampledDemFile(
    private val source: DemFile,
    sourceAxisLength: Int,
    private val stride: Int,
) : DemFile {
    private val sourceRowLength = sourceAxisLength + 1
    private val outputRowLength = sourceAxisLength / stride + 1

    override fun getName(): String = source.name

    override fun getSize(): Long = outputRowLength.toLong() * outputRowLength * 2L

    override fun openInputStream(bufferSize: Int): InputStream =
        PhoneDownsamplingInputStream(
            source.openInputStream(bufferSize),
            sourceRowLength,
            outputRowLength,
            stride,
        )

    override fun asStream(): InputStream = openInputStream(DemFile.BufferSizeDefault)

    override fun asRawStream(): InputStream = openInputStream(DemFile.BufferSizeRaw)
}

private class PhoneDownsamplingInputStream(
    private val source: InputStream,
    private val sourceRowLength: Int,
    private val outputRowLength: Int,
    private val stride: Int,
) : InputStream() {
    private val sourceRow = ByteArray(sourceRowLength * 2)
    private val outputRow = ByteArray(outputRowLength * 2)
    private var outputOffset = outputRow.size
    private var row = 0

    override fun read(): Int {
        if (!ensureRow()) return -1
        return outputRow[outputOffset++].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        var written = 0
        while (written < length && ensureRow()) {
            val count = min(length - written, outputRow.size - outputOffset)
            outputRow.copyInto(buffer, offset + written, outputOffset, outputOffset + count)
            outputOffset += count
            written += count
        }
        return written.takeIf { it > 0 } ?: -1
    }

    override fun close() = source.close()

    @Suppress("ReturnCount") // Stream exhaustion and row reuse are explicit fast paths.
    private fun ensureRow(): Boolean {
        if (outputOffset < outputRow.size) return true
        if (row >= outputRowLength) return false
        repeat(stride - 1) { readFully(sourceRow) }
        readFully(sourceRow)
        var sourceOffset = 0
        var outputOffset = 0
        repeat(outputRowLength) {
            outputRow[outputOffset++] = sourceRow[sourceOffset]
            outputRow[outputOffset++] = sourceRow[sourceOffset + 1]
            sourceOffset += stride * 2
        }
        row += 1
        this.outputOffset = 0
        return true
    }

    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = source.read(buffer, offset, buffer.size - offset)
            if (count <= 0) error("Incomplete HGT input")
            offset += count
        }
    }
}

/** Watch-compatible slope-band relief, rendered asynchronously and only from cached bitmaps. */
internal class PhoneReliefOverlayLayer(
    private val elevation: PhoneElevationRepository,
) : Layer() {
    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-relief-worker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    private val cache = LinkedHashMap<PhoneReliefTileKey, Bitmap>(PHONE_RELIEF_CACHE_ENTRIES, 0.75f, true)
    private val pending = mutableSetOf<PhoneReliefTileKey>()

    @Volatile private var destroyed = false

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        if (destroyed || zoomLevel.toInt() < PHONE_RELIEF_MIN_ZOOM) return
        val tileSize = displayModel?.tileSize ?: return
        phoneVisibleReliefTiles(boundingBox, zoomLevel, tileSize, topLeftPoint)
            .take(PHONE_RELIEF_MAX_VISIBLE_TILES)
            .forEach { tile ->
                val bitmap = synchronized(cache) { cache[tile.key] }
                if (bitmap != null && !bitmap.isDestroyed) {
                    canvas.drawBitmap(bitmap, 0, 0, tileSize, tileSize, tile.left, tile.top, tile.right, tile.bottom)
                } else {
                    schedule(tile.key)
                }
            }
    }

    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        worker.shutdownNow()
        synchronized(cache) {
            cache.values.forEach { bitmap -> bitmap.decrementRefCount() }
            cache.clear()
            pending.clear()
        }
        super.onDestroy()
    }

    private fun schedule(key: PhoneReliefTileKey) {
        synchronized(pending) {
            if (!pending.add(key)) return
            if (pending.size > PHONE_RELIEF_MAX_PENDING_JOBS) {
                pending.remove(key)
                return
            }
        }
        worker.execute {
            val result = runCatching { buildTile(key) }.getOrNull()
            synchronized(pending) { pending.remove(key) }
            if (destroyed) {
                result?.decrementRefCount()
                return@execute
            }
            synchronized(cache) {
                cache.remove(key)?.decrementRefCount()
                if (result != null) {
                    cache[key] = result
                    while (cache.size > PHONE_RELIEF_CACHE_ENTRIES) {
                        cache.remove(cache.keys.first())?.decrementRefCount()
                    }
                }
            }
            requestRedraw()
        }
    }

    private fun buildTile(key: PhoneReliefTileKey): Bitmap? {
        val step =
            when {
                key.zoom >= 17 -> 2
                key.zoom >= 15 -> 4
                else -> 8
            }
        val mapSize = MercatorProjection.getMapSize(key.zoom.toByte(), key.tileSize)
        val pixels = IntArray(key.tileSize * key.tileSize)
        var colored = false
        var y = 0
        while (y < key.tileSize) {
            val height = min(step, key.tileSize - y)
            var x = 0
            while (x < key.tileSize) {
                val width = min(step, key.tileSize - x)
                val worldX = key.tileX * key.tileSize + x + width * 0.5
                val worldY = key.tileY * key.tileSize + y + height * 0.5
                val longitude =
                    MercatorProjection.pixelXToLongitude(
                        wrapPhonePixelX(worldX, mapSize.toDouble()),
                        mapSize,
                    )
                val latitude =
                    MercatorProjection.pixelYToLatitude(
                        worldY.coerceIn(0.0, mapSize.toDouble()),
                        mapSize,
                    )
                val color = phoneReliefColor(elevation, latitude, longitude)
                if (color != 0) colored = true
                for (row in y until y + height) {
                    pixels.fill(color, row * key.tileSize + x, row * key.tileSize + x + width)
                }
                x += step
            }
            y += step
        }
        if (!colored) return null
        val bitmap = AndroidGraphicFactory.INSTANCE.createBitmap(key.tileSize, key.tileSize, true)
        AndroidGraphicFactory.getBitmap(bitmap).setPixels(pixels, 0, key.tileSize, 0, 0, key.tileSize, key.tileSize)
        return bitmap
    }
}

private data class PhoneReliefTileKey(
    val zoom: Int,
    val tileX: Long,
    val tileY: Long,
    val tileSize: Int,
)

private data class PhoneVisibleReliefTile(
    val key: PhoneReliefTileKey,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

private fun phoneVisibleReliefTiles(
    boundingBox: BoundingBox,
    zoomLevel: Byte,
    tileSize: Int,
    topLeftPoint: Point,
): List<PhoneVisibleReliefTile> {
    val mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize)
    val leftPixel = MercatorProjection.longitudeToPixelX(boundingBox.minLongitude, mapSize)
    var rightPixel = MercatorProjection.longitudeToPixelX(boundingBox.maxLongitude, mapSize)
    if (rightPixel < leftPixel) rightPixel += mapSize
    val topPixel = MercatorProjection.latitudeToPixelY(boundingBox.maxLatitude, mapSize)
    val bottomPixel = MercatorProjection.latitudeToPixelY(boundingBox.minLatitude, mapSize)
    val tileCount = (mapSize / tileSize.toLong()).coerceAtLeast(1L)
    val startX = floor(leftPixel / tileSize).toLong()
    val endX = ceil(rightPixel / tileSize).toLong() - 1L
    val startY = floor(topPixel / tileSize).toLong().coerceIn(0L, tileCount - 1L)
    val endY = (ceil(bottomPixel / tileSize).toLong() - 1L).coerceIn(0L, tileCount - 1L)
    if (endX < startX || endY < startY) return emptyList()
    return buildList {
        for (tileY in startY..endY) {
            for (tileX in startX..endX) {
                val wrappedX = Math.floorMod(tileX, tileCount)
                val left = round(tileX * tileSize - topLeftPoint.x).toInt()
                val top = round(tileY * tileSize - topLeftPoint.y).toInt()
                add(
                    PhoneVisibleReliefTile(
                        key = PhoneReliefTileKey(zoomLevel.toInt(), wrappedX, tileY, tileSize),
                        left = left,
                        top = top,
                        right = left + tileSize,
                        bottom = top + tileSize,
                    ),
                )
            }
        }
    }
}

@Suppress("LongMethod", "ReturnCount") // Relief color sampling keeps the four-neighbor slope calculation together.
internal fun phoneReliefColor(
    elevation: PhoneElevationRepository,
    latitude: Double,
    longitude: Double,
): Int {
    val base =
        elevation.loadTile(
            floor(latitude).toInt(),
            floor(longitude).toInt(),
        ) ?: return 0
    val latTile = floor(latitude).toInt()
    val lonTile = floor(longitude).toInt()
    val epsilon = 1.0 / max(base.axisLength, 1200).toDouble()
    val north =
        elevation.elevationAtUsingTile(
            base,
            latTile,
            lonTile,
            latitude + epsilon,
            longitude,
        ) ?: return 0
    val south =
        elevation.elevationAtUsingTile(
            base,
            latTile,
            lonTile,
            latitude - epsilon,
            longitude,
        ) ?: return 0
    val east =
        elevation.elevationAtUsingTile(
            base,
            latTile,
            lonTile,
            latitude,
            longitude + epsilon,
        ) ?: return 0
    val west =
        elevation.elevationAtUsingTile(
            base,
            latTile,
            lonTile,
            latitude,
            longitude - epsilon,
        ) ?: return 0
    val latitudeRadians = Math.toRadians(latitude)
    val metersPerLatitudeDegree = 111132.954 - 559.822 * cos(2 * latitudeRadians) + 1.175 * cos(4 * latitudeRadians)
    val metersPerLongitudeDegree = max(1.0, 111320.0 * cos(latitudeRadians))
    val dx = max(0.5, epsilon * metersPerLongitudeDegree)
    val dy = max(0.5, epsilon * metersPerLatitudeDegree)
    val slopeDegrees = Math.toDegrees(atan(hypot((east - west) / (2.0 * dx), (south - north) / (2.0 * dy))))
    val alpha =
        when {
            slopeDegrees < 15.0 -> 0
            slopeDegrees < 22.0 -> 96
            slopeDegrees < 29.0 -> 116
            slopeDegrees < 36.0 -> 148
            slopeDegrees < 43.0 -> 164
            else -> 180
        }
    return (alpha shl 24) or
        (246 shl 16) or
        ((max(0.0, 239.0 - (slopeDegrees - 15.0) * 7.0).toInt().coerceIn(61, 239)) shl 8)
}

private fun wrapPhonePixelX(
    pixel: Double,
    mapSize: Double,
): Double {
    val wrapped = pixel % mapSize
    return if (wrapped < 0.0) wrapped + mapSize else wrapped
}

private const val PHONE_TERRAIN_TAG = "PhoneTerrain"
private const val PHONE_DEM_SCAN_MAX_DEPTH = 6
private const val PHONE_DEM_CACHE_ENTRIES = 3
private const val PHONE_DEM_VOID_SAMPLE: Short = Short.MIN_VALUE
private const val PHONE_HILLSHADE_MAX_INPUT_AXIS = 1800
private const val PHONE_RELIEF_MIN_ZOOM = 13
private const val PHONE_RELIEF_MAX_VISIBLE_TILES = 20
private const val PHONE_RELIEF_MAX_PENDING_JOBS = 32
private const val PHONE_RELIEF_CACHE_ENTRIES = 24
private const val PHONE_GZIP_FOOTER_BYTES = 4
