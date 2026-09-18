package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context

internal const val DEFAULT_NO_MOVEMENT_ALERT_MINUTES = "10"

internal data class SavedLiveTrackingSettings(
    val group: String = "",
    val participantPassword: String = "",
    val followerPassword: String = "",
    val userName: String = "",
    val notificationEmailAddresses: List<String> = emptyList(),
    val alertRecipients: List<String> = emptyList(),
    val stuckAlarmMinutes: String = DEFAULT_NO_MOVEMENT_ALERT_MINUTES,
    val updateIntervalSeconds: Int = 60,
)

internal data class SavedLiveTrackingDraft(
    val comments: String = "",
    val gpxUri: String = "",
    val gpxName: String = "",
)

@Suppress("TooManyFunctions")
internal object LiveTrackingPreferences {
    private const val PREFS_NAME = "arkluz_live_tracking"
    private const val KEY_GROUP = "group"
    private const val KEY_PARTICIPANT_PASSWORD = "participant_password"
    private const val KEY_FOLLOWER_PASSWORD = "follower_password"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_NOTIFICATION_EMAILS = "notification_emails"
    private const val KEY_ALERT_EMAILS = "alert_emails"
    private const val KEY_STUCK_ALARM_MINUTES = "stuck_alarm_minutes"
    private const val KEY_UPDATE_INTERVAL_SECONDS = "update_interval_seconds"
    private const val KEY_DRAFT_COMMENTS = "draft_comments"
    private const val KEY_DRAFT_GPX_URI = "draft_gpx_uri"
    private const val KEY_DRAFT_GPX_NAME = "draft_gpx_name"
    private const val KEY_TRACKING_ENDPOINT = "tracking_endpoint"
    private const val GROUP_PROFILE_PREFIX = "group_profile"

    fun load(context: Context): SavedLiveTrackingSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SavedLiveTrackingSettings(
            group = prefs.getString(KEY_GROUP, "").orEmpty(),
            participantPassword = prefs.getString(KEY_PARTICIPANT_PASSWORD, "").orEmpty(),
            followerPassword = prefs.getString(KEY_FOLLOWER_PASSWORD, "").orEmpty(),
            userName = prefs.getString(KEY_USER_NAME, "").orEmpty(),
            notificationEmailAddresses = prefs.getString(KEY_NOTIFICATION_EMAILS, "").orEmpty().toEmailList(),
            alertRecipients = prefs.getString(KEY_ALERT_EMAILS, "").orEmpty().toRecipientList(),
            stuckAlarmMinutes =
                prefs
                    .getString(KEY_STUCK_ALARM_MINUTES, DEFAULT_NO_MOVEMENT_ALERT_MINUTES)
                    .orEmpty()
                    .ifBlank { DEFAULT_NO_MOVEMENT_ALERT_MINUTES },
            updateIntervalSeconds = prefs.getInt(KEY_UPDATE_INTERVAL_SECONDS, 60),
        )
    }

    fun save(
        context: Context,
        settings: SavedLiveTrackingSettings,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GROUP, settings.group)
            .putString(KEY_PARTICIPANT_PASSWORD, settings.participantPassword)
            .putString(KEY_FOLLOWER_PASSWORD, settings.followerPassword)
            .putString(KEY_USER_NAME, settings.userName)
            .putString(KEY_NOTIFICATION_EMAILS, settings.notificationEmailAddresses.joinToString(","))
            .putString(KEY_ALERT_EMAILS, settings.alertRecipients.joinToString(","))
            .putString(KEY_STUCK_ALARM_MINUTES, settings.stuckAlarmMinutes)
            .putInt(KEY_UPDATE_INTERVAL_SECONDS, settings.updateIntervalSeconds)
            .apply()
    }

    fun loadDraft(context: Context): SavedLiveTrackingDraft {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SavedLiveTrackingDraft(
            comments = prefs.getString(KEY_DRAFT_COMMENTS, "").orEmpty(),
            gpxUri = prefs.getString(KEY_DRAFT_GPX_URI, "").orEmpty(),
            gpxName = prefs.getString(KEY_DRAFT_GPX_NAME, "").orEmpty(),
        )
    }

    fun saveDraft(
        context: Context,
        draft: SavedLiveTrackingDraft,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_COMMENTS, draft.comments)
            .putString(KEY_DRAFT_GPX_URI, draft.gpxUri)
            .putString(KEY_DRAFT_GPX_NAME, draft.gpxName)
            .apply()
    }

    fun loadEndpoint(context: Context): ArkluzTrackingEndpoint =
        ArkluzTrackingEndpoint.fromPersistedName(
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRACKING_ENDPOINT, null),
        )

    fun saveEndpoint(
        context: Context,
        endpoint: ArkluzTrackingEndpoint,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRACKING_ENDPOINT, endpoint.name)
            .apply()
    }

    fun clearDraft(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DRAFT_COMMENTS)
            .remove(KEY_DRAFT_GPX_URI)
            .remove(KEY_DRAFT_GPX_NAME)
            .apply()
    }

    fun loadGroupSettings(
        context: Context,
        group: String,
    ): SavedLiveTrackingSettings? {
        val cleanGroup = group.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = groupProfilePrefix(cleanGroup)
        return if (cleanGroup.isBlank() || !prefs.contains("$prefix.$KEY_USER_NAME")) {
            null
        } else {
            SavedLiveTrackingSettings(
                group = cleanGroup,
                userName = prefs.getString("$prefix.$KEY_USER_NAME", "").orEmpty(),
                notificationEmailAddresses =
                    prefs.getString("$prefix.$KEY_NOTIFICATION_EMAILS", "").orEmpty().toEmailList(),
                alertRecipients =
                    prefs.getString("$prefix.$KEY_ALERT_EMAILS", "").orEmpty().toRecipientList(),
                stuckAlarmMinutes =
                    prefs
                        .getString("$prefix.$KEY_STUCK_ALARM_MINUTES", DEFAULT_NO_MOVEMENT_ALERT_MINUTES)
                        .orEmpty()
                        .ifBlank { DEFAULT_NO_MOVEMENT_ALERT_MINUTES },
                updateIntervalSeconds = prefs.getInt("$prefix.$KEY_UPDATE_INTERVAL_SECONDS", 60),
            )
        }
    }

    fun saveGroupSettings(
        context: Context,
        settings: SavedLiveTrackingSettings,
    ) {
        val cleanGroup = settings.group.trim()
        if (cleanGroup.isBlank()) return
        val prefix = groupProfilePrefix(cleanGroup)
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("$prefix.$KEY_USER_NAME", settings.userName)
            .putString("$prefix.$KEY_NOTIFICATION_EMAILS", settings.notificationEmailAddresses.joinToString(","))
            .putString("$prefix.$KEY_ALERT_EMAILS", settings.alertRecipients.joinToString(","))
            .putString("$prefix.$KEY_STUCK_ALARM_MINUTES", settings.stuckAlarmMinutes)
            .putInt("$prefix.$KEY_UPDATE_INTERVAL_SECONDS", settings.updateIntervalSeconds)
            .apply()
    }

    fun clear(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_GROUP)
            .remove(KEY_PARTICIPANT_PASSWORD)
            .remove(KEY_FOLLOWER_PASSWORD)
            .remove(KEY_USER_NAME)
            .remove(KEY_NOTIFICATION_EMAILS)
            .remove(KEY_ALERT_EMAILS)
            .remove(KEY_STUCK_ALARM_MINUTES)
            .remove(KEY_UPDATE_INTERVAL_SECONDS)
            .remove(KEY_DRAFT_COMMENTS)
            .remove(KEY_DRAFT_GPX_URI)
            .remove(KEY_DRAFT_GPX_NAME)
            .apply()
    }

    private fun groupProfilePrefix(group: String): String = "$GROUP_PROFILE_PREFIX.${group.trim().lowercase()}"

    private fun String.toEmailList(): List<String> =
        split(",")
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun String.toRecipientList(): List<String> =
        split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
}
