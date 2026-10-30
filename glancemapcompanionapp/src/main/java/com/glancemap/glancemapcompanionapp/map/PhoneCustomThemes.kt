package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.glancemap.glancemapwearos.data.repository.maps.theme.ExternalThemeStore
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemePackageException
import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import com.glancemap.shared.transfer.MapsforgeThemePackage
import com.glancemap.shared.transfer.mapsforgeSvgStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlUtils
import org.mapsforge.map.rendertheme.rule.RenderThemeHandler
import java.io.File

/** The same bounded, atomic package installer used by the watch. */
internal object PhoneCustomThemes {
    private var store: ExternalThemeStore? = null
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        val created = ExternalThemeStore(File(app.filesDir, "mapsforge-themes"), validateTheme = { validatePhoneTheme(app, it) })
        store = created
        PhoneOfflineThemeCatalog.setExternalThemes(created.themes())
    }

    @Synchronized
    fun installed(): List<RenderThemeSource.External> = checkNotNull(store).themes()

    @Synchronized
    fun import(context: Context, uri: Uri): List<RenderThemeSource.External> {
        val app = context.applicationContext
        initialize(app)
        val name = displayName(app, uri)
        require(MapsforgeThemePackage.isThemeZipCandidate(name)) { "Select a Mapsforge theme ZIP" }
        val temporary = File.createTempFile("phone-theme-", ".zip", app.cacheDir)
        try {
            val input = app.contentResolver.openInputStream(uri) ?: error("Cannot open theme package")
            try {
                MapsforgeThemePackage.boundedArchiveStream(input).use { source ->
                    temporary.outputStream().use { destination -> source.copyTo(destination) }
                }
            } catch (error: IllegalStateException) {
                if (error.message == "Theme package is too large") throw ThemePackageException(error.message!!, error)
                throw error
            }
            val imported = checkNotNull(store).install(temporary, MapsforgeThemePackage.transferName(name))
            refreshCatalog()
            return imported
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun delete(packageId: String) {
        try {
            checkNotNull(store).deletePackage(packageId)
        } finally {
            refreshCatalog()
        }
    }

    private fun refreshCatalog() {
        PhoneOfflineThemeCatalog.setExternalThemes(checkNotNull(store).themes())
        mutableChanges.value += 1
    }

    private fun displayName(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return File(name ?: uri.lastPathSegment ?: "theme.zip").name
    }
}

internal fun phoneExternalRenderTheme(
    source: RenderThemeSource.External,
    callback: XmlRenderThemeMenuCallback,
): ExternalRenderTheme =
    ExternalRenderTheme(source.xmlFile, callback).apply {
        setResourceProvider { _, resource ->
            val path = MapsforgeThemePackage.resourceFileName(resource)
            val file = File(source.xmlFile.parentFile, path).canonicalFile
            if (!file.path.startsWith(source.resourceRoot.canonicalPath + File.separator)) {
                throw ThemePackageException("Not a valid Mapsforge theme package")
            }
            if (!file.isFile) throw ThemePackageException("Theme resources are missing")
            if (file.extension.equals("svg", ignoreCase = true)) {
                file.inputStream().use { mapsforgeSvgStream(it) }
            } else {
                file.inputStream()
            }
        }
    }

private fun validatePhoneTheme(context: Context, source: RenderThemeSource.External) {
    if (AndroidGraphicFactory.INSTANCE == null) AndroidGraphicFactory.createInstance(context.applicationContext)
    try {
        val theme = phoneExternalRenderTheme(source, XmlRenderThemeMenuCallback { menu ->
            val style = menu.getLayer(menu.defaultValue) ?: menu.layers.values.firstOrNull { it.isVisible }
            if (style == null) emptySet() else enabledPhoneOfflineThemeCategories(style, null)
        })
        val displayModel = DisplayModel()
        val info = source.xmlFile.inputStream().use { MapsforgeThemePackage.inspectXml(it) }
            ?: throw ThemePackageException("No render theme XML found")
        info.resourceSources.forEach { resource ->
            XmlUtils.createBitmap(
                AndroidGraphicFactory.INSTANCE, displayModel, theme.relativePathPrefix, resource,
                theme.resourceProvider, 0, 0, 100,
            ).decrementRefCount()
        }
        RenderThemeHandler.getRenderTheme(AndroidGraphicFactory.INSTANCE, displayModel, theme).destroy()
    } catch (error: Exception) {
        throw ThemePackageException("Not a valid Mapsforge theme package", error)
    }
}
