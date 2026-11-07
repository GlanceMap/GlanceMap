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

    /** Hidden categories by file; stored as `file#categoryId` entries. */
    fun disabledCategoryIds(): Map<String, Set<Int>> =
        preferences
            .getStringSet(KEY_DISABLED_CATEGORIES, emptySet())
            .orEmpty()
            .mapNotNull(::parseCategoryEntry)
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.toSet() }

    fun setCategoriesEnabled(
        fileName: String,
        categoryIds: Set<Int>,
        enabled: Boolean,
    ) {
        val safeFileName = File(fileName).name
        val entries = categoryIds.map { categoryEntry(safeFileName, it) }.toSet()
        val stored = preferences.getStringSet(KEY_DISABLED_CATEGORIES, emptySet()).orEmpty()
        val updated = if (enabled) stored - entries else stored + entries
        preferences.edit().putStringSet(KEY_DISABLED_CATEGORIES, updated).apply()
    }

    fun rename(
        oldFileName: String,
        newFileName: String,
    ) {
        val disabled = disabledFileNames().toMutableSet()
        val oldSafeName = File(oldFileName).name
        val newSafeName = File(newFileName).name
        if (disabled.remove(oldSafeName)) disabled += newSafeName
        val categories =
            disabledCategoryIds().flatMap { (file, ids) ->
                ids.map { categoryEntry(if (file == oldSafeName) newSafeName else file, it) }
            }
        preferences
            .edit()
            .putStringSet(KEY_DISABLED_FILE_NAMES, disabled)
            .putStringSet(KEY_DISABLED_CATEGORIES, categories.toSet())
            .apply()
    }

    fun remove(fileName: String) {
        val safeName = File(fileName).name
        val disabled = disabledFileNames() - safeName
        val categories =
            disabledCategoryIds()
                .filterKeys { it != safeName }
                .flatMap { (file, ids) -> ids.map { categoryEntry(file, it) } }
        preferences
            .edit()
            .putStringSet(KEY_DISABLED_FILE_NAMES, disabled)
            .putStringSet(KEY_DISABLED_CATEGORIES, categories.toSet())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_map_poi_source_visibility"
        const val KEY_DISABLED_FILE_NAMES = "disabled_file_names"
        const val KEY_DISABLED_CATEGORIES = "disabled_categories"
    }
}

private fun categoryEntry(
    fileName: String,
    categoryId: Int,
): String = "$fileName#$categoryId"

private fun parseCategoryEntry(entry: String): Pair<String, Int>? {
    val separator = entry.lastIndexOf('#')
    if (separator <= 0) return null
    val id = entry.substring(separator + 1).toIntOrNull() ?: return null
    return entry.substring(0, separator) to id
}
