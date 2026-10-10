package com.glancemap.glancemapwearos.presentation.features.maps

import org.mapsforge.map.layer.cache.TileCache
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal fun closeMapRendererTileCache(
    cache: TileCache,
    previousCacheId: String,
    nextCacheId: String,
) {
    try {
        if (previousCacheId == nextCacheId) cache.purge()
    } finally {
        cache.destroy()
    }
}

internal fun mapRendererFileSignature(file: File): String =
    runCatching {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        "${file.absolutePath}|${attributes.lastModifiedTime()}|${attributes.size()}|${attributes.fileKey()}"
    }.getOrElse {
        val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
        val length = runCatching { file.length() }.getOrDefault(0L)
        "${file.absolutePath}|$lastModified|$length"
    }
