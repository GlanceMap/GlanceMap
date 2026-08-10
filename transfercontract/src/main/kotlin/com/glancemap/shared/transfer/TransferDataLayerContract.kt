package com.glancemap.shared.transfer

object TransferDataLayerContract {
    const val WEAR_CAPABILITY = "glancemap_wear_app"

    const val DIAGNOSTICS_SUPPORT_EMAIL = "Glancemap@protonmail.com"
    const val DIAGNOSTICS_SUBJECT_PREFIX = "[GlanceMap-Diagnostics]"

    const val CHANNEL_PREFIX = "/glancemap/file"
    const val CHANNEL_WATCH_GPX_EXPORT_PREFIX = "/glancemap/watch_gpx_export"
    const val PATH_START_WIFI_TRANSFER = "/glancemap/start_wifi_transfer"
    const val PATH_SMALL_FILE_PREFIX = "/glancemap/small_file"
    const val PATH_PREPARE_CHANNEL = "/glancemap/prepare_channel"
    const val PATH_PING = "/glancemap/ping"
    const val PATH_PING_RESULT = "/glancemap/ping_result"
    const val PATH_CHECK_WIFI_STATUS = "/glancemap/check_wifi_status"
    const val PATH_CHECK_WIFI_STATUS_RESULT = "/glancemap/check_wifi_status_result"
    const val PATH_DIAGNOSTICS_EMAIL_REQUEST = "/glancemap/diagnostics_email_request"

    const val PATH_CHECK_EXISTS = "/glancemap/check_exists"
    const val PATH_CHECK_EXISTS_RESULT = "/glancemap/check_exists_result"
    const val PATH_CHECK_EXISTS_BATCH = "/glancemap/check_exists_batch"
    const val PATH_CHECK_EXISTS_BATCH_RESULT = "/glancemap/check_exists_batch_result"
    const val PATH_DELETE_FILE = "/glancemap/delete_file"
    const val PATH_DELETE_FILE_RESULT = "/glancemap/delete_file_result"
    const val PATH_LIST_MAPS = "/glancemap/list_maps"
    const val PATH_LIST_MAPS_RESULT = "/glancemap/list_maps_result"

    const val PATH_TRANSFER_STATUS = "/glancemap/transfer_status"
    const val PATH_TRANSFER_ACK = "/glancemap/transfer_ack"
    const val PATH_CANCEL_TRANSFER = "/glancemap/cancel_transfer"
    const val PATH_ACTIVE_HIKE_SNAPSHOT = "/glancemap/active_hike_snapshot"
    const val PATH_LIVE_HIKE_SYNC_SETTINGS = "/glancemap/live_hike_sync_settings"

    // HTTP transfer hardening
    const val HTTP_AUTH_HEADER = "X-GlanceMap-Transfer-Token"
    const val HTTP_AUTH_TOKEN_JSON_KEY = "http_token"
}
