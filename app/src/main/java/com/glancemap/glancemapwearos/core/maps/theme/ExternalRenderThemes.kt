package com.glancemap.glancemapwearos.core.maps.theme

import android.content.Context
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemePackageException
import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import com.glancemap.shared.transfer.MapsforgeThemePackage
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleLayer
import org.mapsforge.map.rendertheme.XmlUtils
import org.mapsforge.map.rendertheme.rule.RenderThemeHandler
import java.io.File
import java.util.ArrayDeque

internal fun externalRenderTheme(source: RenderThemeSource.External): ExternalRenderTheme =
    ExternalRenderTheme(source.xmlFile, defaultRenderThemeMenuCallback()).apply {
        setResourceProvider { _, resource ->
            val path = MapsforgeThemePackage.resourceFileName(resource)
            val file = File(source.xmlFile.parentFile, path).canonicalFile
            if (!file.path.startsWith(source.resourceRoot.canonicalPath + File.separator)) {
                throw ThemePackageException("Not a valid Mapsforge theme package")
            }
            if (!file.isFile) throw ThemePackageException("Theme resources are missing")
            file.inputStream()
        }
    }

// Mapsforge exposes implementation-specific parse/decode exceptions for imported data.
@Suppress("TooGenericExceptionCaught")
internal fun validateExternalRenderTheme(
    context: Context,
    source: RenderThemeSource.External,
) {
    if (AndroidGraphicFactory.INSTANCE == null) AndroidGraphicFactory.createInstance(context.applicationContext)
    try {
        val theme = externalRenderTheme(source)
        val displayModel = DisplayModel()
        val info =
            source.xmlFile.inputStream().use { MapsforgeThemePackage.inspectXml(it) }
                ?: throw ThemePackageException("No render theme XML found")
        info.resourceSources.forEach { resource ->
            XmlUtils
                .createBitmap(
                    AndroidGraphicFactory.INSTANCE,
                    displayModel,
                    theme.relativePathPrefix,
                    resource,
                    theme.resourceProvider,
                    0,
                    0,
                    100,
                ).decrementRefCount()
        }
        RenderThemeHandler
            .getRenderTheme(
                AndroidGraphicFactory.INSTANCE,
                displayModel,
                theme,
            ).destroy()
    } catch (error: Exception) {
        throw ThemePackageException("Not a valid Mapsforge theme package", error)
    }
}

internal fun defaultRenderThemeMenuCallback(): XmlRenderThemeMenuCallback =
    XmlRenderThemeMenuCallback { menu ->
        val styleLayer =
            menu.getLayer(menu.defaultValue)
                ?: menu.layers.values.firstOrNull { it.isVisible }
                ?: return@XmlRenderThemeMenuCallback emptySet()
        val categories = linkedSetOf<String>()
        categories.addAll(styleLayer.categories)
        addEnabledOverlayCategories(styleLayer, categories)
        categories
    }

private fun addEnabledOverlayCategories(
    layer: XmlRenderThemeStyleLayer,
    categories: MutableSet<String>,
) {
    val visited = hashSetOf<XmlRenderThemeStyleLayer>()
    val pending = ArrayDeque<XmlRenderThemeStyleLayer>()
    pending.add(layer)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (!visited.add(current)) continue
        categories.addAll(current.categories)
        current.overlays
            .asReversed()
            .filter { it.isEnabled }
            .forEach { pending.add(it) }
    }
}
