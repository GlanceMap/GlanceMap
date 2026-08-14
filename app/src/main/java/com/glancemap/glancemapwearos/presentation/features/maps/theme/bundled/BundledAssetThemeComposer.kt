package com.glancemap.glancemapwearos.presentation.features.maps.theme.bundled

import android.content.Context
import android.util.Log
import android.util.Xml
import com.glancemap.glancemapwearos.core.maps.theme.BundledRenderThemeAssetLocator
import com.glancemap.glancemapwearos.core.maps.theme.RenderThemeXmlCapabilities
import com.glancemap.glancemapwearos.core.service.diagnostics.MapHotPathDiagnostics
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemeRepositoryImpl
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.util.Locale

class BundledAssetThemeComposer(
    private val context: Context,
) {
    companion object {
        private const val TAG = "BundledThemeComposer"
        private const val RESOURCE_THEME_MARKER_FILE = ".theme_id"
        private const val VOLUNTARY_DEFAULT_STYLE_ID = "vol-hiking"
    }

    private data class StyleMenuMetadata(
        val defaultStyleId: String?,
        val toggleableOverlayLayerIds: Set<String>,
    )

    private data class ThemeAssetMetadata(
        val themePath: String,
        val themeRoot: String?,
        val originalXml: String,
        val supportsNativeHillShading: Boolean,
        val referencedAssetRoots: Set<String>,
        val styleMenuMetadata: StyleMenuMetadata,
    )

    private val themeFingerprintCache = mutableMapOf<String, String>()
    private val themeAssetMetadataCache = mutableMapOf<String, ThemeAssetMetadata>()
    private val themeResourceCacheLock = Any()
    private val appBundleFingerprint: String by lazy(::resolveAppBundleFingerprint)

    private fun String.withoutLeadingBom(): String = removePrefix("\uFEFF")

    fun prewarmThemeAssets(themeId: String) {
        val timingMarker = MapHotPathDiagnostics.begin("themeComposer.prewarmThemeAssets")
        var timingStatus = "ok"
        var normalizedThemeId = themeId.trim()
        try {
            normalizedThemeId = normalizeThemeId(themeId)
            val metadata = assetMetadataForTheme(normalizedThemeId)
            val themeAssetFingerprint = fingerprintForTheme(normalizedThemeId)
            ensureThemeResourcesInCache(
                themeId = normalizedThemeId,
                themeAssetFingerprint = themeAssetFingerprint,
                metadata = metadata,
            )
            timingStatus = "ready"
        } catch (e: IllegalArgumentException) {
            timingStatus = "invalid_theme"
            Log.w(TAG, "Skipping prewarm for unsupported bundled theme: $themeId", e)
        } catch (t: Throwable) {
            timingStatus = "failed"
            Log.w(TAG, "Failed to prewarm bundled theme assets for $normalizedThemeId", t)
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail = "theme=$normalizedThemeId",
            )
        }
    }

    fun createDynamicThemeFileOrNull(
        themeId: String,
        styleId: String,
        enabledOverlayLayerIds: List<String>,
        hillShadingEnabled: Boolean,
    ): File? {
        val timingMarker = MapHotPathDiagnostics.begin("themeComposer.createDynamicThemeFileOrNull")
        var timingStatus = "ok"
        val normalizedThemeId = normalizeThemeId(themeId)
        return try {
            val metadata = assetMetadataForTheme(normalizedThemeId)
            val overlays =
                enabledOverlayLayerIds
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
                    .toList()

            val selectedStyleId =
                if (styleId == ThemeRepositoryImpl.DEFAULT_STYLE_ID) {
                    resolveDefaultStyleIdFromAssets(normalizedThemeId)
                } else {
                    styleId
                }
            if (selectedStyleId.isNullOrBlank()) {
                timingStatus = "missing_style"
                return null
            }

            val isDefault =
                styleId == ThemeRepositoryImpl.DEFAULT_STYLE_ID &&
                    overlays.isEmpty() &&
                    (
                        hillShadingEnabled ||
                            !metadata.supportsNativeHillShading
                    )
            if (isDefault) {
                timingStatus = "default_theme_passthrough"
                return null
            }

            val themeAssetFingerprint = fingerprintForTheme(normalizedThemeId)
            val themeCacheDir =
                ensureThemeResourcesInCache(
                    themeId = normalizedThemeId,
                    themeAssetFingerprint = themeAssetFingerprint,
                    metadata = metadata,
                )

            val themeKey =
                buildThemeKey(
                    themeId = normalizedThemeId,
                    styleId = selectedStyleId,
                    overlaysSorted = overlays,
                    hillShadingEnabled = hillShadingEnabled,
                    themeAssetFingerprint = themeAssetFingerprint,
                )
            val fileName = "dynamic_theme_$themeKey.xml"
            val outFile = File(themeCacheDir, fileName)

            if (outFile.exists() && outFile.length() > 0L) {
                timingStatus = "generated_theme_cache_hit"
                return outFile
            }

            val content =
                generateXml(
                    metadata = metadata,
                    selectedStyleId = selectedStyleId,
                    enabledOverlayLayerIds = overlays.toSet(),
                    hillShadingEnabled = hillShadingEnabled,
                )

            MapHotPathDiagnostics.measure(
                stage = "themeComposer.writeDynamicThemeFile",
                detail = "theme=$normalizedThemeId",
            ) {
                outFile.writeText(content)
            }
            cleanupOldDynamicThemes(themeCacheDir, keepFileName = fileName)
            timingStatus = "generated_theme_file"
            outFile
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail =
                    buildString {
                        append("theme=").append(normalizedThemeId)
                        append(" style=").append(styleId)
                        append(" overlays=").append(enabledOverlayLayerIds.size)
                        append(" hill=").append(hillShadingEnabled)
                    },
            )
        }
    }

    private fun buildThemeKey(
        themeId: String,
        styleId: String,
        overlaysSorted: List<String>,
        hillShadingEnabled: Boolean,
        themeAssetFingerprint: String,
    ): String {
        val raw =
            buildString {
                append("theme:")
                append(themeId)
                append('|')
                append(styleId)
                append('|')
                append("hill:")
                append(hillShadingEnabled)
                append('|')
                append("asset:")
                append(themeAssetFingerprint)
                append('|')
                overlaysSorted.forEach { append(it).append(',') }
            }
        return sha256Hex(raw).take(16)
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    private fun cleanupOldDynamicThemes(
        themeCacheDir: File,
        keepFileName: String,
    ) {
        runCatching {
            themeCacheDir
                .listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.startsWith("dynamic_theme_") && it.name.endsWith(".xml") }
                ?.filter { it.name != keepFileName }
                ?.forEach { it.delete() }
        }
    }

    private fun ensureThemeResourcesInCache(
        themeId: String,
        themeAssetFingerprint: String,
        metadata: ThemeAssetMetadata,
    ): File {
        val timingMarker = MapHotPathDiagnostics.begin("themeComposer.ensureThemeResourcesInCache")
        var timingStatus = "ok"
        val outDir = File(context.cacheDir, "bundled-theme-$themeId")
        val markerFile = File(outDir, RESOURCE_THEME_MARKER_FILE)
        val markerContent = "$themeId|$themeAssetFingerprint"
        return try {
            synchronized(themeResourceCacheLock) {
                if (outDir.exists() && outDir.isDirectory) {
                    val markerMatches =
                        runCatching { markerFile.readText().trim() == markerContent }
                            .getOrDefault(false)
                    if (markerMatches) {
                        timingStatus = "cache_hit"
                        return@synchronized outDir
                    }
                    timingStatus = "cache_refresh"
                    outDir.deleteRecursively()
                } else {
                    timingStatus = "cache_miss"
                }

                outDir.mkdirs()
                runCatching {
                    copyThemeResourcesToCache(
                        metadata = metadata,
                        outDir = outDir,
                    )
                    markerFile.writeText(markerContent)
                    Log.d(TAG, "Copied bundled theme resources to cache: ${outDir.absolutePath}")
                }.onFailure {
                    timingStatus = "copy_failed"
                    Log.w(
                        TAG,
                        "Failed to copy bundled theme resources to cache. Theme may render incorrectly.",
                        it,
                    )
                }
                outDir
            }
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail = "theme=$themeId",
            )
        }
    }

    private fun copyThemeResourcesToCache(
        metadata: ThemeAssetMetadata,
        outDir: File,
    ) {
        val themeRoot = metadata.themeRoot
        if (!themeRoot.isNullOrBlank()) {
            val children = context.assets.list(themeRoot) ?: emptyArray()
            val xmlFileName = metadata.themePath.substringAfterLast('/')
            children
                .filter { it != xmlFileName }
                .forEach { child ->
                    val childAssetPath = "$themeRoot/$child"
                    copyAssetNodeToFile(
                        assetPath = childAssetPath,
                        outFile = File(outDir, child),
                    )
                }
            return
        }

        metadata.referencedAssetRoots.forEach { root ->
            copyAssetNodeToFile(assetPath = root, outFile = File(outDir, root))
        }
    }

    private fun copyAssetNodeToFile(
        assetPath: String,
        outFile: File,
    ) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            outFile.parentFile?.mkdirs()
            if (outFile.exists() && outFile.length() > 0L) return
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        if (!outFile.exists()) outFile.mkdirs()
        children.forEach { child ->
            val childAssetPath = "$assetPath/$child"
            copyAssetNodeToFile(childAssetPath, File(outFile, child))
        }
    }

    private fun collectReferencedAssetRoots(originalXml: String): Set<String> {
        val regex = Regex("""file:([^"'\s>]+)""")
        return regex
            .findAll(originalXml)
            .mapNotNull { match ->
                match.groupValues[1]
                    .trim()
                    .substringBefore('?')
                    .removePrefix("/")
                    .substringBefore('/')
                    .trim()
                    .takeIf { it.isNotEmpty() && it != "." }
            }.toCollection(linkedSetOf())
    }

    private fun resolveDefaultStyleIdFromAssets(themeId: String): String? =
        when (themeId) {
            MapsforgeThemeCatalog.VOLUNTARY_THEME_ID -> VOLUNTARY_DEFAULT_STYLE_ID
            else -> assetMetadataForTheme(themeId).styleMenuMetadata.defaultStyleId
        }

    private fun fingerprintForTheme(themeId: String): String {
        val timingMarker = MapHotPathDiagnostics.begin("themeComposer.fingerprintForTheme")
        var timingStatus = "cache_hit"
        return try {
            synchronized(themeFingerprintCache) {
                themeFingerprintCache[themeId]?.let { return@synchronized it }
                timingStatus = "cache_miss"
                val metadata = assetMetadataForTheme(themeId)
                val fingerprint =
                    sha256Hex(
                        buildString {
                            append("bundle:")
                            append(appBundleFingerprint)
                            append('|')
                            append("theme:")
                            append(themeId)
                            append('|')
                            append(metadata.themePath)
                            append('|')
                            append(metadata.themeRoot.orEmpty())
                            append('|')
                            metadata.referencedAssetRoots
                                .asSequence()
                                .sorted()
                                .forEach { root ->
                                    append(root)
                                    append(',')
                                }
                        },
                    ).take(16)
                themeFingerprintCache[themeId] = fingerprint
                fingerprint
            }
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail = "theme=$themeId",
            )
        }
    }

    private fun resolveAppBundleFingerprint(): String =
        runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            buildString {
                append(packageInfo.longVersionCode)
                append('|')
                append(packageInfo.lastUpdateTime)
            }
        }.getOrElse {
            val apkLastModified = runCatching { File(context.packageCodePath).lastModified() }.getOrDefault(0L)
            "fallback|$apkLastModified"
        }

    private fun generateXml(
        metadata: ThemeAssetMetadata,
        selectedStyleId: String,
        enabledOverlayLayerIds: Set<String>,
        hillShadingEnabled: Boolean,
    ): String =
        MapHotPathDiagnostics.measure(
            stage = "themeComposer.generateXml",
            detail = "style=$selectedStyleId overlays=${enabledOverlayLayerIds.size} hill=$hillShadingEnabled",
        ) {
            val originalXml = metadata.originalXml
            val toggleableOverlayLayerIds = metadata.styleMenuMetadata.toggleableOverlayLayerIds

            val parser =
                XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(StringReader(originalXml))
                }

            val writer = StringWriter()
            val serializer: XmlSerializer =
                Xml.newSerializer().apply {
                    setOutput(writer)
                    startDocument("UTF-8", true)
                }

            var insideStyleMenu = false
            var styleMenuDepth = -1

            var insideSelectedStyleLayer = false
            var selectedStyleLayerDepth = -1

            val writtenToggleableOverlays = LinkedHashSet<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name

                        when {
                            tag == "hillshading" && !hillShadingEnabled -> {
                                skipCurrentTag(parser)
                            }

                            tag == "stylemenu" -> {
                                insideStyleMenu = true
                                styleMenuDepth = parser.depth

                                serializer.startTag(parser.namespace, tag)
                                for (i in 0 until parser.attributeCount) {
                                    val attrName = parser.getAttributeName(i)
                                    val attrNs = parser.getAttributeNamespace(i)
                                    val attrValue = parser.getAttributeValue(i)

                                    if (attrName == "defaultvalue") {
                                        serializer.attribute(attrNs, attrName, selectedStyleId)
                                    } else {
                                        serializer.attribute(attrNs, attrName, attrValue)
                                    }
                                }
                            }

                            insideStyleMenu && tag == "layer" -> {
                                val layerId = parser.getAttributeValue(null, "id")
                                val isStyle = parser.getAttributeValue(null, "visible") == "true"

                                insideSelectedStyleLayer = (isStyle && layerId == selectedStyleId)
                                if (insideSelectedStyleLayer) {
                                    selectedStyleLayerDepth = parser.depth
                                    writtenToggleableOverlays.clear()
                                }

                                serializer.startTag(parser.namespace, tag)

                                var hasEnabledAttr = false
                                for (i in 0 until parser.attributeCount) {
                                    val attrName = parser.getAttributeName(i)
                                    val attrNs = parser.getAttributeNamespace(i)
                                    val attrValue = parser.getAttributeValue(i)

                                    if (attrName == "enabled") hasEnabledAttr = true

                                    if (!isStyle && attrName == "enabled" && layerId != null) {
                                        val enabled = enabledOverlayLayerIds.contains(layerId)
                                        serializer.attribute(attrNs, attrName, enabled.toString())
                                    } else {
                                        serializer.attribute(attrNs, attrName, attrValue)
                                    }
                                }

                                if (!isStyle && layerId != null && !hasEnabledAttr) {
                                    val enabled = enabledOverlayLayerIds.contains(layerId)
                                    serializer.attribute(null, "enabled", enabled.toString())
                                }
                            }

                            insideSelectedStyleLayer && tag == "overlay" -> {
                                val id = parser.getAttributeValue(null, "id")

                                if (id == null) {
                                    copyStartTagWithAttributes(parser, serializer)
                                } else {
                                    val isToggleable = id in toggleableOverlayLayerIds

                                    if (!isToggleable) {
                                        copyStartTagWithAttributes(parser, serializer)
                                    } else {
                                        if (enabledOverlayLayerIds.contains(id)) {
                                            copyStartTagWithAttributes(parser, serializer)
                                            writtenToggleableOverlays.add(id)
                                        } else {
                                            skipCurrentTag(parser)
                                        }
                                    }
                                }
                            }

                            else -> copyStartTagWithAttributes(parser, serializer)
                        }
                    }

                    XmlPullParser.TEXT -> serializer.text(parser.text)

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name

                        if (insideSelectedStyleLayer &&
                            tag == "layer" &&
                            parser.depth == selectedStyleLayerDepth
                        ) {
                            val missing =
                                enabledOverlayLayerIds
                                    .asSequence()
                                    .filter { it in toggleableOverlayLayerIds }
                                    .filter { it !in writtenToggleableOverlays }
                                    .toList()

                            for (id in missing) {
                                serializer.startTag(null, "overlay")
                                serializer.attribute(null, "id", id)
                                serializer.endTag(null, "overlay")
                            }

                            insideSelectedStyleLayer = false
                            selectedStyleLayerDepth = -1
                        }

                        serializer.endTag(parser.namespace, tag)

                        if (insideStyleMenu && tag == "stylemenu" && parser.depth == styleMenuDepth) {
                            insideStyleMenu = false
                            styleMenuDepth = -1
                        }
                    }
                }

                parser.next()
            }

            serializer.endDocument()
            writer.toString()
        }

    private fun assetMetadataForTheme(themeId: String): ThemeAssetMetadata {
        val timingMarker = MapHotPathDiagnostics.begin("themeComposer.assetMetadataForTheme")
        var timingStatus = "cache_hit"
        return try {
            synchronized(themeAssetMetadataCache) {
                themeAssetMetadataCache[themeId]?.let { return@synchronized it }
                timingStatus = "cache_miss"
                val themePath = BundledRenderThemeAssetLocator.resolveThemeXmlPath(context.assets, themeId)
                val themeRoot = BundledRenderThemeAssetLocator.resolveThemeRootPath(context.assets, themeId)
                val originalXml =
                    context.assets
                        .open(themePath)
                        .bufferedReader()
                        .use { it.readText() }
                        .withoutLeadingBom()
                val referencedAssetRoots =
                    if (!themeRoot.isNullOrBlank()) {
                        emptySet()
                    } else {
                        collectReferencedAssetRoots(originalXml)
                    }
                val metadata =
                    ThemeAssetMetadata(
                        themePath = themePath,
                        themeRoot = themeRoot,
                        originalXml = originalXml,
                        supportsNativeHillShading = RenderThemeXmlCapabilities.supportsNativeHillShading(originalXml),
                        referencedAssetRoots = referencedAssetRoots,
                        styleMenuMetadata = parseStyleMenuMetadata(originalXml),
                    )
                themeAssetMetadataCache[themeId] = metadata
                metadata
            }
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail = "theme=$themeId",
            )
        }
    }

    private fun parseStyleMenuMetadata(originalXml: String): StyleMenuMetadata =
        runCatching {
            val parser =
                XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(StringReader(originalXml))
                }
            var insideStyleMenu = false
            var styleMenuDepth = -1
            var defaultStyleId: String? = null
            var firstVisibleLayerId: String? = null
            val toggleable = LinkedHashSet<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when {
                            parser.name == "stylemenu" -> {
                                insideStyleMenu = true
                                styleMenuDepth = parser.depth
                                defaultStyleId =
                                    parser
                                        .getAttributeValue(null, "defaultvalue")
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                            }

                            insideStyleMenu && parser.name == "layer" -> {
                                val layerId =
                                    parser
                                        .getAttributeValue(null, "id")
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?: run {
                                            parser.next()
                                            continue
                                        }
                                val isVisible = parser.getAttributeValue(null, "visible") == "true"
                                if (isVisible) {
                                    if (firstVisibleLayerId == null) {
                                        firstVisibleLayerId = layerId
                                    }
                                } else {
                                    toggleable += layerId
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (insideStyleMenu && parser.name == "stylemenu" && parser.depth == styleMenuDepth) {
                            break
                        }
                    }
                }
                parser.next()
            }

            StyleMenuMetadata(
                defaultStyleId = defaultStyleId ?: firstVisibleLayerId,
                toggleableOverlayLayerIds = toggleable,
            )
        }.getOrElse {
            StyleMenuMetadata(
                defaultStyleId = null,
                toggleableOverlayLayerIds = emptySet(),
            )
        }

    private fun copyStartTagWithAttributes(
        parser: XmlPullParser,
        serializer: XmlSerializer,
    ) {
        serializer.startTag(parser.namespace, parser.name)
        for (i in 0 until parser.attributeCount) {
            serializer.attribute(
                parser.getAttributeNamespace(i),
                parser.getAttributeName(i),
                parser.getAttributeValue(i),
            )
        }
    }

    private fun skipCurrentTag(parser: XmlPullParser) {
        val startDepth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == startDepth) return
        }
    }

    private fun normalizeThemeId(themeId: String): String {
        val normalizedThemeId = themeId.trim()
        require(MapsforgeThemeCatalog.isBundledAssetTheme(normalizedThemeId)) {
            "Unknown bundled theme id: $themeId"
        }
        return normalizedThemeId
    }
}
