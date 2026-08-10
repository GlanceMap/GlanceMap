package com.glancemap.glancemapcompanionapp.activehike

import android.content.Context
import com.glancemap.shared.transfer.LiveHikeSyncSettingsCodec

/** Stores the user's Live Hike dashboard sync preference on the companion. */
internal object CompanionLiveHikeSyncPreferences {
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
