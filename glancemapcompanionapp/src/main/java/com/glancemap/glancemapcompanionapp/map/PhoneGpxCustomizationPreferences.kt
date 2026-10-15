package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class PhoneGpxCustomizationPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PHONE_GPX_SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): PhoneGpxCustomizationState =
        try {
            val stored = preferences.getString(PHONE_GPX_CUSTOMIZATION_KEY, null)
            val parsed = stored?.let { gson.fromJson(it, PhoneGpxCustomizationState::class.java) }
            parsed?.copy(fileStyles = parsed.fileStyles.orEmpty(), folderStyles = parsed.folderStyles.orEmpty())
                ?: PhoneGpxCustomizationState()
        } catch (_: JsonParseException) {
            PhoneGpxCustomizationState()
        }

    fun save(state: PhoneGpxCustomizationState) {
        preferences.edit().putString(PHONE_GPX_CUSTOMIZATION_KEY, gson.toJson(state)).apply()
    }

    val changes =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == PHONE_GPX_CUSTOMIZATION_KEY || key == null) trySend(load())
                }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(load())
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()
}
