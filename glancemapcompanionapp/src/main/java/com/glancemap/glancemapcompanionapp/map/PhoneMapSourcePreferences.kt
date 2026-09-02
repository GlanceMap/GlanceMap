package com.glancemap.glancemapcompanionapp.map

import android.content.Context

internal enum class PhoneMapSourcePreferenceMode {
    ONLINE,
    OFFLINE,
}

internal data class PhoneMapSourcePreference(
    val mode: PhoneMapSourcePreferenceMode = PhoneMapSourcePreferenceMode.ONLINE,
    val offlineMapName: String? = null,
    val onlineSource: PhoneOnlineMapSource = PhoneOnlineMapSource.OPEN_TOPO,
)

/** Remembers the map source toggle plus the last selected map for each renderer. */
internal class PhoneMapSourcePreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneMapSourcePreference =
        PhoneMapSourcePreference(
            mode =
                if (preferences.getBoolean(KEY_OFFLINE, false)) {
                    PhoneMapSourcePreferenceMode.OFFLINE
                } else {
                    PhoneMapSourcePreferenceMode.ONLINE
                },
            offlineMapName = preferences.getString(KEY_OFFLINE_MAP_NAME, null),
            onlineSource =
                PhoneOnlineMapSource.fromStorageValue(
                    preferences.getString(KEY_ONLINE_SOURCE, null),
                ),
        )

    fun saveOnline(
        source: PhoneOnlineMapSource = load().onlineSource,
    ): PhoneMapSourcePreference =
        load()
            .copy(mode = PhoneMapSourcePreferenceMode.ONLINE, onlineSource = source)
            .also(::save)

    fun saveOffline(map: PhoneOfflineMap): PhoneMapSourcePreference =
        load()
            .copy(
                mode = PhoneMapSourcePreferenceMode.OFFLINE,
                offlineMapName = map.displayName,
            ).also(::save)

    fun replaceOfflineMapName(
        oldName: String,
        newName: String,
    ): PhoneMapSourcePreference {
        val selection = load()
        return selection
            .takeIf { it.offlineMapName == oldName }
            ?.copy(offlineMapName = newName)
            ?.also(::save)
            ?: selection
    }

    fun forgetOfflineMapName(name: String): PhoneMapSourcePreference {
        val selection = load()
        return selection
            .takeIf { it.offlineMapName == name }
            ?.copy(mode = PhoneMapSourcePreferenceMode.ONLINE, offlineMapName = null)
            ?.also(::save)
            ?: selection
    }

    private fun save(selection: PhoneMapSourcePreference) {
        preferences
            .edit()
            .putBoolean(KEY_OFFLINE, selection.mode == PhoneMapSourcePreferenceMode.OFFLINE)
            .putString(KEY_OFFLINE_MAP_NAME, selection.offlineMapName)
            .putString(KEY_ONLINE_SOURCE, selection.onlineSource.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_map_source"
        const val KEY_OFFLINE = "offline"
        const val KEY_OFFLINE_MAP_NAME = "offline_map_name"
        const val KEY_ONLINE_SOURCE = "online_source"
    }
}
