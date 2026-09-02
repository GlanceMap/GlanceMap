@file:Suppress("MatchingDeclarationName") // The filename names the user-facing POI source preference feature.

package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import java.io.File

/** Persists the POI folders a user has hidden while new folders remain visible by default. */
internal class PhoneMapPoiSourceVisibilityPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun disabledFileNames(): Set<String> = preferences.getStringSet(KEY_DISABLED_FILE_NAMES, emptySet()).orEmpty()

    fun setEnabled(
        fileName: String,
        enabled: Boolean,
    ) {
        val disabled = disabledFileNames().toMutableSet()
        val safeFileName = File(fileName).name
        if (enabled) {
            disabled.remove(safeFileName)
        } else {
            disabled.add(safeFileName)
        }
        preferences.edit().putStringSet(KEY_DISABLED_FILE_NAMES, disabled).apply()
    }

    fun rename(
        oldFileName: String,
        newFileName: String,
    ) {
        val disabled = disabledFileNames().toMutableSet()
        val oldSafeName = File(oldFileName).name
        if (disabled.remove(oldSafeName)) {
            disabled += File(newFileName).name
            preferences.edit().putStringSet(KEY_DISABLED_FILE_NAMES, disabled).apply()
        }
    }

    fun remove(fileName: String) {
        val disabled = disabledFileNames().toMutableSet()
        if (disabled.remove(File(fileName).name)) {
            preferences.edit().putStringSet(KEY_DISABLED_FILE_NAMES, disabled).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_map_poi_source_visibility"
        const val KEY_DISABLED_FILE_NAMES = "disabled_file_names"
    }
}
