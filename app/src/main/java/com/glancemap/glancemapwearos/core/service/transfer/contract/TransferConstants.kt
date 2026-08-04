package com.glancemap.glancemapwearos.core.service.transfer.contract
import com.glancemap.shared.transfer.TransferDataLayerContract

internal object TransferConstants {
    const val CHANNEL_PREFIX = TransferDataLayerContract.CHANNEL_PREFIX
    const val PATH_START_WIFI_TRANSFER = TransferDataLayerContract.PATH_START_WIFI_TRANSFER
    const val PATH_SMALL_FILE_PREFIX = TransferDataLayerContract.PATH_SMALL_FILE_PREFIX
    const val PATH_PREPARE_CHANNEL = TransferDataLayerContract.PATH_PREPARE_CHANNEL
    const val PATH_PING = TransferDataLayerContract.PATH_PING
    const val PATH_PING_RESULT = TransferDataLayerContract.PATH_PING_RESULT
    const val PATH_CHECK_WIFI_STATUS = TransferDataLayerContract.PATH_CHECK_WIFI_STATUS
    const val PATH_CHECK_WIFI_STATUS_RESULT = TransferDataLayerContract.PATH_CHECK_WIFI_STATUS_RESULT
    const val PATH_DIAGNOSTICS_EMAIL_REQUEST = TransferDataLayerContract.PATH_DIAGNOSTICS_EMAIL_REQUEST

    const val PATH_CHECK_EXISTS = TransferDataLayerContract.PATH_CHECK_EXISTS
    const val PATH_CHECK_EXISTS_RESULT = TransferDataLayerContract.PATH_CHECK_EXISTS_RESULT
    const val PATH_CHECK_EXISTS_BATCH = TransferDataLayerContract.PATH_CHECK_EXISTS_BATCH
    const val PATH_CHECK_EXISTS_BATCH_RESULT = TransferDataLayerContract.PATH_CHECK_EXISTS_BATCH_RESULT
    const val PATH_DELETE_FILE = TransferDataLayerContract.PATH_DELETE_FILE
    const val PATH_DELETE_FILE_RESULT = TransferDataLayerContract.PATH_DELETE_FILE_RESULT
    const val PATH_LIST_MAPS = TransferDataLayerContract.PATH_LIST_MAPS
    const val PATH_LIST_MAPS_RESULT = TransferDataLayerContract.PATH_LIST_MAPS_RESULT

    const val PATH_TRANSFER_STATUS = TransferDataLayerContract.PATH_TRANSFER_STATUS
    const val PATH_CANCEL_TRANSFER = TransferDataLayerContract.PATH_CANCEL_TRANSFER
    const val PATH_ACTIVE_HIKE_SNAPSHOT = TransferDataLayerContract.PATH_ACTIVE_HIKE_SNAPSHOT

    const val SMALL_FILE_MAX_BYTES = 80 * 1024

    const val WAKELOCK_MAX_MS = 30 * 60 * 1000L // 30 min (was 2 h; WiFi worst-case 50 MB ≈ 17 min)
    const val PREWARM_WAKELOCK_MS = 25_000L
    const val SMALL_WAKELOCK_MS = 60_000L
}
