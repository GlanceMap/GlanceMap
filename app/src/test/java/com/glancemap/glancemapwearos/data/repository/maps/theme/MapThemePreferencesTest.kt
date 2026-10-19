package com.glancemap.glancemapwearos.data.repository.maps.theme

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapThemePreferencesTest {
    private val disk = MemoryThemePreferences()
    private val preferences = MapThemePreferences(disk)

    @Test
    fun `map A custom and map B built-in restore independently through recreation`() {
        preferences
            .edit()
            .putString(THEME, "elevate")
            .putString(STYLE, "default-style")
            .apply()
        preferences.mapPath = "mapA.map"
        preferences.edit().putString(THEME, CUSTOM).apply()
        preferences.mapPath = "mapB.map"
        assertEquals("elevate", preferences.getString(THEME, null))
        assertEquals("default-style", preferences.getString(STYLE, null))
        preferences
            .edit()
            .putString(THEME, "mapsforge")
            .putString(STYLE, "DARK")
            .apply()
        preferences.mapPath = "mapA.map"
        assertEquals(CUSTOM, preferences.getString(THEME, null))
        val recreated = MapThemePreferences(MemoryThemePreferences(disk.all)).apply { mapPath = "mapB.map" }
        assertEquals("mapsforge", recreated.getString(THEME, null))
        assertEquals("DARK", recreated.getString(STYLE, null))
        recreated.mapPath = "mapA.map"
        assertEquals(CUSTOM, recreated.getString(THEME, null))
        recreated.mapPath = "unassociated.map"
        assertEquals("elevate", recreated.getString(THEME, null))
    }

    @Test
    fun `per-map styles and overlays remain distinct while terrain toggles remain global`() {
        preferences.mapPath = "a.map"
        preferences
            .edit()
            .putString(
                THEME,
                "elevate",
            ).putString(
                STYLE,
                "hiking",
            ).putStringSet(OVERLAYS, setOf("paths"))
            .putBoolean("global_hill_shading_enabled", true)
            .apply()
        preferences.mapPath = "b.map"
        preferences
            .edit()
            .putString(
                THEME,
                "elevate",
            ).putString(STYLE, "cycling")
            .putStringSet(OVERLAYS, setOf("bike"))
            .apply()
        preferences.mapPath = "a.map"
        assertEquals("hiking", preferences.getString(STYLE, null))
        assertEquals(setOf("paths"), preferences.getStringSet(OVERLAYS, null))
        assertTrue(preferences.getBoolean("global_hill_shading_enabled", false))
        preferences.mapPath = "b.map"
        assertEquals("cycling", preferences.getString(STYLE, null))
        assertEquals(setOf("bike"), preferences.getStringSet(OVERLAYS, null))
    }

    @Test
    fun `changing a map style alone captures its current theme association`() {
        preferences.edit().putString(THEME, "mapsforge").apply()
        preferences.mapPath = "a.map"
        preferences.edit().putString(STYLE, "DARK").apply()
        preferences.mapPath = null
        preferences.edit().putString(THEME, "elevate").apply()
        preferences.mapPath = "a.map"
        assertEquals("mapsforge", preferences.getString(THEME, null))
    }

    @Test
    fun `theme removal invalidates associations and restores global built-in defaults`() {
        preferences.edit().putString(THEME, "elevate").apply()
        preferences.mapPath = "a.map"
        preferences
            .edit()
            .putString(THEME, CUSTOM)
            .putString(STYLE, "custom")
            .apply()
        preferences.mapPath = "b.map"
        preferences.edit().putString(THEME, "mapsforge").apply()
        preferences.invalidateExternalThemes(emptySet())
        preferences.mapPath = "a.map"
        assertEquals("elevate", preferences.getString(THEME, null))
        preferences.mapPath = "b.map"
        assertEquals("mapsforge", preferences.getString(THEME, null))
        assertFalse(disk.all.values.contains(CUSTOM))
    }

    @Test
    fun `map rename moves theme and overlay choices and deletion removes them`() {
        preferences.mapPath = "old.map"
        preferences
            .edit()
            .putString(THEME, CUSTOM)
            .putStringSet(OVERLAYS, setOf("paths"))
            .apply()
        preferences.renameMap("old.map", "new.map")
        val recreated = MapThemePreferences(MemoryThemePreferences(disk.all)).apply { mapPath = "new.map" }
        assertEquals(CUSTOM, recreated.getString(THEME, null))
        assertEquals(setOf("paths"), recreated.getStringSet(OVERLAYS, null))
        preferences.deleteMap("new.map")
        assertTrue(disk.all.isEmpty())
        preferences.mapPath = "old.map"
        assertEquals("fallback", preferences.getString(THEME, "fallback"))
    }

    @Test
    fun `valid external association survives catalog invalidation and global preferences stay unscoped`() {
        preferences.edit().putString(THEME, "elevate").apply()
        preferences.mapPath = "a.map"
        preferences.edit().putString(THEME, CUSTOM).apply()
        preferences.invalidateExternalThemes(setOf(CUSTOM))
        assertEquals(CUSTOM, preferences.getString(THEME, null))
        assertEquals("elevate", disk.getString(THEME, null))
    }

    @Test
    fun `global toggle writes keep chained theme choices scoped to their map`() {
        preferences.edit().putString(THEME, "elevate").apply()
        preferences.mapPath = "a.map"
        preferences
            .edit()
            .putBoolean("global_night_mode_enabled", true)
            .putString(THEME, CUSTOM)
            .apply()
        assertEquals(CUSTOM, preferences.getString(THEME, null))
        assertEquals("elevate", disk.getString(THEME, null))
        assertTrue(disk.getBoolean("global_night_mode_enabled", false))
    }

    @Test
    fun `legacy global theme migration preserves inheritance without pinning the active map`() {
        preferences.edit().putString(THEME, "mapsforge:DARK").apply()
        preferences.mapPath = "a.map"
        preferences
            .editLegacyChoice()
            .putString(THEME, "mapsforge")
            .putString(STYLE, "mapsforge:DARK")
            .apply()
        assertEquals("mapsforge", disk.getString(THEME, null))
        assertEquals("mapsforge:DARK", disk.getString(STYLE, null))
        preferences.mapPath = null
        preferences.edit().putString(THEME, "elevate").apply()
        preferences.mapPath = "a.map"
        assertEquals("elevate", preferences.getString(THEME, null))
    }

    @Test
    fun `legacy map-specific theme migration preserves the global default`() {
        preferences.edit().putString(THEME, "elevate").apply()
        preferences.mapPath = "a.map"
        preferences.edit().putString(THEME, "mapsforge:DARK").apply()
        preferences
            .editLegacyChoice()
            .putString(THEME, "mapsforge")
            .putString(STYLE, "mapsforge:DARK")
            .apply()
        assertEquals("mapsforge", preferences.getString(THEME, null))
        assertEquals("elevate", disk.getString(THEME, null))
    }

    companion object {
        private const val THEME = "selected_theme_id"
        private const val STYLE = "selected_style_mapsforge"
        private const val OVERLAYS = "enabled_overlays_for_theme_elevate_style_hiking"
        private const val CUSTOM = "external:package:entry"
    }
}

/** Persists the exact preference keys/values; recreating it simulates reloading the on-disk state. */
private class MemoryThemePreferences(
    initial: Map<String, *> = emptyMap<String, Any>(),
) : SharedPreferences {
    private val values = initial.toMutableMap()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor = apply { changes[key] = value }

        override fun putStringSet(
            key: String,
            values: Set<String>?,
        ): SharedPreferences.Editor =
            apply {
                changes[key] =
                    values?.toSet()
            }

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = apply { changes[key] = value }

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = apply { changes[key] = value }

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = apply { changes[key] = value }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = apply { changes[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
