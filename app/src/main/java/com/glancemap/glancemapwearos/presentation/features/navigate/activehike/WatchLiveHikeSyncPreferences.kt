package com.glancemap.glancemapwearos.presentation.features.navigate.activehike

import android.content.Context
import com.glancemap.shared.transfer.LiveHikeSyncSettingsCodec

/** Persists the companion-controlled Live Hike sync preference on the watch. */
internal object WatchLiveHikeSyncPreferences {
    private const val PREFS_NAME = "live_hike_sync"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, LiveHikeSyncSettingsCodec.DEFAULT_ENABLED)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
