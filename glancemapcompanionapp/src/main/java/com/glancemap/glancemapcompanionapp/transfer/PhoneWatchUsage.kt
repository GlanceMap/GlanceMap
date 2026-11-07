package com.glancemap.glancemapcompanionapp.transfer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.glancemap.glancemapcompanionapp.diagnostics.DiagnosticsEmailListenerService

/**
 * Whether the user has a watch to use with the companion. On by default; when off the phone app
 * runs alone: no watch search, no transfer service, and no background wake-ups from the watch.
 */
internal object PhoneWatchUsage {
    private const val PREFS_NAME = "companion_watch"
    private const val KEY_ENABLED = "enabled"
    const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val appContext = context.applicationContext
        appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        applyWatchListeners(appContext, enabled)
    }

    /**
     * Disabled listener components are not bound by Play services, so watch exports and diagnostics
     * requests no longer wake the app. "Default" restores the manifest state, which is enabled.
     */
    fun applyWatchListeners(
        context: Context,
        enabled: Boolean,
    ) {
        val packageManager = context.packageManager
        val state =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        watchListenerComponents(context).forEach { component ->
            if (packageManager.getComponentEnabledSetting(component) != state) {
                packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    internal fun watchListenerComponents(context: Context): List<ComponentName> =
        listOf(
            ComponentName(context, WatchGpxExportListenerService::class.java),
            ComponentName(context, DiagnosticsEmailListenerService::class.java),
        )
}
