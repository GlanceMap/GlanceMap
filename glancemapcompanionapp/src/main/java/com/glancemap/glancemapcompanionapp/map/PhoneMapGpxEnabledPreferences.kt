package com.glancemap.glancemapcompanionapp.map

import android.content.Context

/**
 * The GPX tracks the user turned on, kept across app sessions.
 *
 * [enabledIds] is null until the user's choice was first saved. [appliedSelectionId] is the Route
 * Library selection already turned on, so that selection is not turned on again at every launch.
 */
internal data class PhoneMapGpxEnabledState(
    val enabledIds: Set<String>? = null,
    val appliedSelectionId: String? = null,
)

/** The Route Library selection turns its track on only when it changed since it was last applied. */
internal fun PhoneMapGpxEnabledState.newSelection(selectedRouteId: String?): String? =
    selectedRouteId?.takeIf { it != appliedSelectionId }

/** Updates the loaded tracks' choices and keeps those of tracks that are temporarily unavailable. */
internal fun PhoneMapGpxEnabledState.withItems(
    items: List<PhoneMapGpxItem>,
    appliedSelectionId: String? = this.appliedSelectionId,
): PhoneMapGpxEnabledState {
    val loadedIds = items.mapTo(mutableSetOf(), PhoneMapGpxItem::id)
    val enabled = items.filter(PhoneMapGpxItem::enabled).mapTo(mutableSetOf(), PhoneMapGpxItem::id)
    return PhoneMapGpxEnabledState(
        enabledIds = enabledIds.orEmpty().filterNotTo(mutableSetOf()) { it in loadedIds } + enabled,
        appliedSelectionId = appliedSelectionId,
    )
}

internal class PhoneMapGpxEnabledPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PHONE_GPX_SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneMapGpxEnabledState =
        PhoneMapGpxEnabledState(
            enabledIds = preferences.getStringSet(KEY_ENABLED_IDS, null)?.toSet(),
            appliedSelectionId = preferences.getString(KEY_APPLIED_SELECTION_ID, null),
        )

    fun save(state: PhoneMapGpxEnabledState) {
        preferences
            .edit()
            .putStringSet(KEY_ENABLED_IDS, state.enabledIds.orEmpty())
            .putString(KEY_APPLIED_SELECTION_ID, state.appliedSelectionId)
            .apply()
    }

    private companion object {
        const val KEY_ENABLED_IDS = "map_enabled_gpx_ids"
        const val KEY_APPLIED_SELECTION_ID = "map_applied_route_selection_id"
    }
}
