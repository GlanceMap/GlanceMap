package com.glancemap.glancemapcompanionapp.livetracking

import com.glancemap.glancemapcompanionapp.R

/** Translate only for display; callers continue to store and compare the existing stable statuses. */
internal fun liveTrackingMessageResource(message: String): Int? =
    when (message) {
        "Missing live tracking settings" -> R.string.tracking_notification_missing_live_tracking_settings
        "Starting live tracking" -> R.string.tracking_notification_starting_live_tracking
        "Live tracking paused" -> R.string.tracking_notification_live_tracking_paused
        "Restoring live tracking" -> R.string.tracking_notification_restoring_live_tracking
        "Restoring GPS tracking" -> R.string.tracking_notification_restoring_gps_tracking
        "Location permission is required" -> R.string.tracking_notification_location_permission_is_required
        "Waiting for GPS fix" -> R.string.tracking_notification_waiting_for_gps_fix
        "Live tracking start failed" -> R.string.tracking_notification_live_tracking_start_failed
        "Live tracking error" -> R.string.tracking_notification_live_tracking_error
        "Waiting for GPS confirmation" -> R.string.tracking_notification_waiting_for_gps_confirmation
        "Position sent" -> R.string.tracking_notification_position_sent
        "Waiting for network to start tracking" -> R.string.tracking_notification_waiting_for_network_to_start_tracking
        "Wait for the first GPS position before pausing" -> R.string.tracking_notification_wait_for_the_first_gps_position_before
        "Pause pending; a no-movement alert may still be sent" -> R.string.tracking_notification_pause_pending_a_no_movement_alert_may
        "Tracking resumed; notifying Arkluz" -> R.string.tracking_notification_tracking_resumed_notifying_arkluz
        "Tracking active" -> R.string.tracking_notification_tracking_active
        "Live tracking active" -> R.string.tracking_notification_live_tracking_active
        "Tracking active; Arkluz notification pending" -> R.string.tracking_notification_tracking_active_arkluz_notification_pending
        "Live tracking stopped, waiting for server confirmation" -> R.string.tracking_notification_live_tracking_stopped_waiting_for_server_confirmation
        "Waiting to confirm stop" -> R.string.phone_tracking_waiting_confirm_stop
        "GPS stored for retry" -> R.string.phone_tracking_gps_stored_retry
        "Stop pending: no saved GPS position" -> R.string.phone_tracking_stop_no_position
        "Stop pending; tracking data retained" -> R.string.phone_tracking_stop_data_retained
        "Unable to register GPS updates" -> R.string.phone_tracking_gps_registration_failed
        "Stop confirmation pending" -> R.string.phone_tracking_stop_confirmation_pending
        "Arkluz rejected the request; pending tracking data is retained" -> R.string.phone_tracking_rejected_data_retained
        "Stopped; server confirmation pending" -> R.string.phone_tracking_stopped_confirmation_pending
        "Arkluz is temporarily unreachable. Check your internet connection and try again." -> R.string.phone_arkluz_unreachable
        "Arkluz did not respond in time. Try again." -> R.string.phone_arkluz_timeout
        "GlanceMap could not authenticate with Arkluz. Update the app, or contact support if it is already up to date." -> R.string.phone_arkluz_authentication
        "Server error" -> R.string.phone_arkluz_server_error
        "Network connection to Arkluz was interrupted. Try again." -> R.string.phone_arkluz_network_interrupted
        "unknown error" -> R.string.phone_arkluz_unknown_error
        else -> null
    }

internal fun localizedLiveTrackingMessage(message: String, text: (Int) -> String): String {
    liveTrackingMessageResource(message)?.let { return text(it) }
    val pending = Regex("^GPS stored for retry \\((\\d+) waiting\\)$").matchEntire(message)
    if (pending != null) return text(R.string.tracking_notification_gps_stored_for_retry_value_waiting).format(pending.groupValues[1].toInt())
    val retained = Regex("^Stopped; (\\d+) positions retained for retry$").matchEntire(message)
    if (retained != null) return text(R.string.phone_tracking_stopped_positions_retained).format(retained.groupValues[1].toInt())
    val processingPrefix = "Tracking data could not be processed: "
    if (message.startsWith(processingPrefix)) {
        val reason = localizedLiveTrackingMessage(message.removePrefix(processingPrefix), text)
        return text(R.string.phone_tracking_processing_error).format(reason)
    }
    val storedPrefix = "GPS stored for retry: "
    if (message.startsWith(storedPrefix)) {
        val reason = localizedLiveTrackingMessage(message.removePrefix(storedPrefix), text)
        return text(R.string.phone_tracking_stored_error).format(reason)
    }
    return message
}
