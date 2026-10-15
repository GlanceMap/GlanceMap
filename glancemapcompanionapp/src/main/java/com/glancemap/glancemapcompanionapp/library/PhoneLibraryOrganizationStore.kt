package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException

internal class PhoneLibraryOrganizationStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("phone_library_organization", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): PhoneLibraryOrganization {
        val json = preferences.getString("organization", null) ?: return PhoneLibraryOrganization()
        return try {
            validatePhoneLibraryOrganization(requireNotNull(gson.fromJson(json, PhoneLibraryOrganization::class.java)))
        } catch (failure: JsonParseException) {
            throw IOException("Could not read Library organization", failure)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid Library organization", failure)
        }
    }

    fun save(organization: PhoneLibraryOrganization) {
        val validated = validatePhoneLibraryOrganization(organization)
        if (!preferences.edit().putString("organization", gson.toJson(validated)).commit()) {
            throw IOException("Could not save Library organization")
        }
    }

    val changes =
        callbackFlow {
            fun publish() {
                try {
                    trySend(load())
                } catch (_: IOException) {
                    trySend(PhoneLibraryOrganization())
                }
            }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> publish() }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            publish()
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()
}
