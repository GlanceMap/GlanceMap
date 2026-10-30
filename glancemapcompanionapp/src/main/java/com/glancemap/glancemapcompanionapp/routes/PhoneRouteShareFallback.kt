package com.glancemap.glancemapcompanionapp.routes

import java.io.File

/** FileProvider only recognizes the primary external-files volume, so stage other volumes in cache. */
internal fun <T> phoneRouteShareUri(
    source: File,
    cacheDirectory: File,
    uriForFile: (File) -> T,
): T {
    try {
        return uriForFile(source)
    } catch (_: IllegalArgumentException) {
        val directory = File(cacheDirectory, "route-sharing")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val copy = File.createTempFile("route-", ".gpx", directory)
        try {
            source.inputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
            return uriForFile(copy)
        } catch (error: Exception) {
            copy.delete()
            throw error
        }
    }
}
