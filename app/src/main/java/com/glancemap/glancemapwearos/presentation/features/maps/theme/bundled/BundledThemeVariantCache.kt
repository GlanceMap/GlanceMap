package com.glancemap.glancemapwearos.presentation.features.maps.theme.bundled

import java.io.File

private const val MAX_DYNAMIC_THEME_VARIANTS = 4

internal fun retainBundledThemeVariants(currentTheme: File) {
    if (!currentTheme.isFile || currentTheme.length() == 0L) return
    runCatching {
        val variants =
            currentTheme.parentFile
                ?.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith("dynamic_theme_") && it.name.endsWith(".xml") }
        val lastUsed = variants.associateWith(::bundledThemeVariantLastUsed)
        val newestUse = maxOf(System.currentTimeMillis(), lastUsed.values.maxOrNull() ?: 0L)
        // XML modification times and file identities form the rendered tile-cache key.
        bundledThemeVariantUsageFile(currentTheme)
            .writeText((newestUse.coerceAtMost(Long.MAX_VALUE - 1) + 1).toString())
        variants
            .filter { it != currentTheme }
            .sortedWith(compareByDescending<File> { lastUsed.getValue(it) }.thenBy { it.name })
            .drop(MAX_DYNAMIC_THEME_VARIANTS - 1)
            .forEach { variant ->
                if (variant.delete()) bundledThemeVariantUsageFile(variant).delete()
            }
    }
}

private fun bundledThemeVariantLastUsed(file: File): Long =
    runCatching { bundledThemeVariantUsageFile(file).readText().toLong() }
        .getOrElse { file.lastModified() }

private fun bundledThemeVariantUsageFile(file: File): File = File(file.parentFile, ".${file.name}.last_used")
