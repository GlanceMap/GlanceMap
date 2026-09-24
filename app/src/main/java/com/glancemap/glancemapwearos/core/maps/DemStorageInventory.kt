package com.glancemap.glancemapwearos.core.maps

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale

internal data class DemStorageSourceInventory(
    val source: DemSource,
    val renderableFileCount: Int,
    val partialFileCount: Int,
    val missingMarkerCount: Int,
    val ignoredFileCount: Int,
    val renderableBytes: Long,
    val depthTruncated: Boolean,
    val fileCountTruncated: Boolean,
    val renderableBytesSaturated: Boolean,
) {
    val truncated: Boolean
        get() = depthTruncated || fileCountTruncated
}

internal data class DemStorageInventory(
    val sources: List<DemStorageSourceInventory>,
) {
    val truncated: Boolean
        get() = sources.any { source -> source.truncated }
}

internal data class DemStorageInventoryCapture(
    val inventory: DemStorageInventory?,
    val errorType: String? = null,
) {
    val status: String
        get() = if (inventory == null) "error" else "ok"
}

internal object DemStorageInventoryScanner {
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 4096

    fun capture(context: Context): DemStorageInventory = scan(roots(context))

    private fun roots(context: Context): Map<DemSource, File> = DemSource.entries.associateWith { it.rootDir(context) }

    fun captureSafely(context: Context): DemStorageInventoryCapture = captureSafely { capture(context) }

    internal fun captureSafely(scan: () -> DemStorageInventory): DemStorageInventoryCapture =
        runCatching { DemStorageInventoryCapture(inventory = scan()) }
            .getOrElse { error ->
                DemStorageInventoryCapture(
                    inventory = null,
                    errorType = error.javaClass.simpleName.ifBlank { "UnknownException" },
                )
            }

    internal fun scan(roots: Map<DemSource, File>): DemStorageInventory =
        DemStorageInventory(
            sources = DemSource.entries.map { source -> scanSource(source, roots[source] ?: File("")) },
        )

    private fun scanSource(
        source: DemSource,
        root: File,
    ): DemStorageSourceInventory {
        if (!root.exists() || !root.isDirectory) {
            return DemStorageSourceInventory(
                source = source,
                renderableFileCount = 0,
                partialFileCount = 0,
                missingMarkerCount = 0,
                ignoredFileCount = 0,
                renderableBytes = 0L,
                depthTruncated = false,
                fileCountTruncated = false,
                renderableBytesSaturated = false,
            )
        }

        val scan = scanFiles(root)
        val files = scan.files
        var renderableFileCount = 0
        var partialFileCount = 0
        var missingMarkerCount = 0
        var ignoredFileCount = 0
        var renderableBytes = 0L
        var renderableBytesSaturated = false

        files.take(MAX_FILES).forEach { file ->
            val lowerName = file.name.lowercase(Locale.ROOT)
            when {
                lowerName.endsWith(".part") -> partialFileCount += 1
                lowerName.endsWith(".hgt.missing") -> missingMarkerCount += 1
                lowerName.isRenderableDemFile() && file.length() > 0L -> {
                    renderableFileCount += 1
                    val fileBytes = file.length()
                    val accumulated = saturatingAddBytes(renderableBytes, fileBytes)
                    renderableBytes = accumulated.first
                    renderableBytesSaturated = renderableBytesSaturated || accumulated.second
                }
                else -> ignoredFileCount += 1
            }
        }

        return DemStorageSourceInventory(
            source = source,
            renderableFileCount = renderableFileCount,
            partialFileCount = partialFileCount,
            missingMarkerCount = missingMarkerCount,
            ignoredFileCount = ignoredFileCount,
            renderableBytes = renderableBytes,
            depthTruncated = scan.depthTruncated,
            fileCountTruncated = scan.fileCountTruncated,
            renderableBytesSaturated = renderableBytesSaturated,
        )
    }

    private data class BoundedFileScan(
        val files: List<File>,
        val depthTruncated: Boolean,
        val fileCountTruncated: Boolean,
    )

    private data class FileDepth(
        val file: File,
        val depth: Int,
    )

    private data class DirectoryScan(
        val children: List<FileDepth>,
        val depthTruncated: Boolean,
    )

    private fun scanFiles(root: File): BoundedFileScan {
        val pending = ArrayDeque<FileDepth>()
        val files = ArrayList<File>(MAX_FILES + 1)
        var depthTruncated = false
        var fileCountTruncated = false
        pending.addLast(FileDepth(root, depth = 0))

        while (pending.isNotEmpty() && !fileCountTruncated) {
            val current = pending.removeFirst()
            if (current.file.isFile) {
                files.add(current.file)
                if (files.size > MAX_FILES) fileCountTruncated = true
            } else {
                scanDirectory(current)?.let { directory ->
                    depthTruncated = depthTruncated || directory.depthTruncated
                    pending.addAll(directory.children)
                }
            }
        }

        return BoundedFileScan(
            files = files,
            depthTruncated = depthTruncated,
            fileCountTruncated = fileCountTruncated,
        )
    }

    private fun scanDirectory(current: FileDepth): DirectoryScan? {
        val directory = current.file
        val result =
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
                null
            } else {
                val children = directory.listFiles() ?: throw DemStorageScanException()
                if (current.depth >= MAX_DEPTH) {
                    DirectoryScan(children = emptyList(), depthTruncated = children.isNotEmpty())
                } else {
                    DirectoryScan(
                        children = children.map { child -> FileDepth(child, current.depth + 1) },
                        depthTruncated = false,
                    )
                }
            }
        return result
    }

    private class DemStorageScanException : RuntimeException()

    private fun String.isRenderableDemFile(): Boolean = endsWith(".hgt") || endsWith(".hgt.zip") || endsWith(".hgt.gz")
}

internal fun saturatingAddBytes(
    current: Long,
    addition: Long,
): Pair<Long, Boolean> =
    if (addition >= 0L && current > Long.MAX_VALUE - addition) {
        Long.MAX_VALUE to true
    } else {
        (current + addition) to false
    }
