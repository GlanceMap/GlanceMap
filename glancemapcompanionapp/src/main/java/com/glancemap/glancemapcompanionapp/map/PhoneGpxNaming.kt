package com.glancemap.glancemapcompanionapp.map

/** Returns the stable user-facing GPX identity without changing meaningful filename text. */
internal fun phoneGpxDisplayNameFromFileName(fileName: String): String {
    val baseName =
        fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
    val withoutExtension =
        if (baseName.endsWith(".gpx", ignoreCase = true)) {
            baseName.dropLast(".gpx".length)
        } else {
            baseName
        }
    return withoutExtension.trim().takeIf(String::isNotBlank) ?: "Imported route"
}
