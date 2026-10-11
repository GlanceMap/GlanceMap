package com.glancemap.glancemapwearos.presentation.features.maps.theme.bundled

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** APK entry checksums avoid recopying unchanged theme assets after an app update. */
internal fun bundledThemeAssetFingerprintOrNull(
    apkFiles: List<File>,
    themePath: String,
    resourceRoots: Set<String>,
): String? =
    runCatching {
        val xmlEntry = "assets/$themePath"
        val roots = resourceRoots.map { "assets/${it.trimEnd('/')}" }
        val entries = sortedMapOf<String, String>()
        apkFiles.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    if (entry.name == xmlEntry || roots.any { entry.name == it || entry.name.startsWith("$it/") }) {
                        check(entry.crc >= 0L && entry.size >= 0L)
                        // Ambiguous split-APK overrides retain the conservative bundle fallback.
                        check(entries.put(entry.name, "${entry.size}:${entry.crc}") == null)
                    }
                }
            }
        }
        check(xmlEntry in entries)
        check(roots.all { root -> entries.keys.any { it == root || it.startsWith("$root/") } })
        val content = entries.entries.joinToString("\n") { (name, signature) -> "$name:$signature" }
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
