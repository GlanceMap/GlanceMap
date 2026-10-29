package com.glancemap.glancemapwearos.data.repository.maps.theme

import android.content.SharedPreferences
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import java.security.MessageDigest

/** Map choices inherit the existing global defaults until the user changes that map's appearance. */
internal class MapThemePreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    var mapPath: String? = null

    private fun scoped(key: String): String = mapPath?.let { prefix(it) + key } ?: key

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = delegate.getString(scoped(key), delegate.getString(key, defValue))

    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? = delegate.getStringSet(scoped(key), delegate.getStringSet(key, defValues))

    override fun getAll(): Map<String, *> {
        val all = delegate.all.filterKeys { !it.startsWith(MAP_PREFIX) }.toMutableMap()
        val activePrefix = mapPath?.let(::prefix) ?: return all
        delegate.all.filterKeys { it.startsWith(activePrefix) }.forEach { (key, value) ->
            all[key.removePrefix(activePrefix)] = value
        }
        return all
    }

    override fun edit(): SharedPreferences.Editor = ScopedEditor(delegate.edit())

    // Normalizing an inherited legacy choice must not create a map association.
    fun editLegacyChoice(): SharedPreferences.Editor {
        val hasMapChoice = mapPath != null && delegate.contains(scoped(SELECTED_THEME))
        return if (hasMapChoice) edit() else delegate.edit()
    }

    private inner class ScopedEditor(
        private val editor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by editor {
        private var choiceStarted = false

        private fun choiceKey(key: String): String {
            if (!isChoiceKey(key)) return key
            if (!choiceStarted && mapPath != null) {
                choiceStarted = true
                editor.putString(
                    scoped(SELECTED_THEME),
                    getString(SELECTED_THEME, MapsforgeThemeCatalog.ELEVATE_THEME_ID),
                )
            }
            return scoped(key)
        }

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor =
            apply {
                editor.putString(choiceKey(key), value)
            }

        override fun putStringSet(
            key: String,
            values: Set<String>?,
        ): SharedPreferences.Editor =
            apply {
                editor.putStringSet(choiceKey(key), values)
            }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = apply { editor.putBoolean(key, value) }

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = apply { editor.putInt(key, value) }

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = apply { editor.putLong(key, value) }

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = apply { editor.putFloat(key, value) }

        override fun remove(key: String): SharedPreferences.Editor =
            apply {
                editor.remove(if (isChoiceKey(key)) scoped(key) else key)
            }
    }

    fun renameMap(
        oldPath: String,
        newPath: String,
    ) {
        val oldPrefix = prefix(oldPath)
        val newPrefix = prefix(newPath)
        val editor = delegate.edit()
        delegate.all.filterKeys { it.startsWith(oldPrefix) }.forEach { (key, value) ->
            editor.remove(key)
            when (value) {
                is String -> editor.putString(newPrefix + key.removePrefix(oldPrefix), value)
                is Set<*> ->
                    editor.putStringSet(
                        newPrefix + key.removePrefix(oldPrefix),
                        value.filterIsInstance<String>().toSet(),
                    )
            }
        }
        editor.apply()
    }

    fun deleteMap(path: String) {
        val prefix = prefix(path)
        val editor = delegate.edit()
        delegate.all.keys
            .filter { it.startsWith(prefix) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun invalidateExternalThemes(validIds: Set<String>) {
        val editor = delegate.edit()
        val all = delegate.all
        val invalidKeys =
            all
                .filter { (key, value) ->
                    key.endsWith(SELECTED_THEME) && isInvalidExternal(value, validIds)
                }.keys
        invalidKeys.forEach { key ->
            val scope = key.removeSuffix(SELECTED_THEME)
            val removedKeys =
                if (scope.startsWith(
                        MAP_PREFIX,
                    )
                ) {
                    all.keys.filter { it.startsWith(scope) }
                } else {
                    listOf(key)
                }
            removedKeys.forEach { editor.remove(it) }
        }
        editor.apply()
    }

    companion object {
        const val PREFERENCES_NAME = "theme_settings"
        private const val MAP_PREFIX = "map_theme:"
        private const val SELECTED_THEME = "selected_theme_id"

        private fun isInvalidExternal(
            value: Any?,
            validIds: Set<String>,
        ): Boolean = value is String && value.startsWith("external:") && value !in validIds

        private fun isChoiceKey(key: String): Boolean =
            key == SELECTED_THEME ||
                key == "selected_style" ||
                key.startsWith("selected_style_") ||
                key.startsWith("enabled_overlays_")

        private fun prefix(path: String): String {
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(path.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            return "$MAP_PREFIX$hash:"
        }
    }
}
