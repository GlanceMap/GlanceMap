package com.glancemap.glancemapcompanionapp.transfer

import android.content.Context
import com.glancemap.glancemapcompanionapp.transfer.strategy.ChannelClientStrategy
import com.glancemap.glancemapcompanionapp.transfer.strategy.HttpTransferServer
import com.glancemap.glancemapcompanionapp.transfer.strategy.MessageClientStrategy
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferStrategy
import com.glancemap.glancemapcompanionapp.transfer.strategy.WifiUploadStrategy
import com.glancemap.glancemapcompanionapp.transfer.util.TransferUtils

enum class TransferStrategyKind {
    MESSAGE,
    CHANNEL,
    HTTP,
    WIFI_UPLOAD,
}

data class TransferSelectionContext(
    val wifiAvailable: Boolean,
    val preferSharedHttpForBatch: Boolean,
)

object TransferStrategyFactory {
    // Must stay aligned with watch-side TransferConstants.SMALL_FILE_MAX_BYTES.
    private const val MESSAGE_CLIENT_MAX_BYTES: Long = 80L * 1024L // 80KB
    private const val SHARED_HTTP_BATCH_MIN_BYTES: Long = 1L * 1024L * 1024L // 1MB

    // Prefer Channel only up to 2MB
    const val CHANNEL_PREFERRED_MAX_BYTES: Long = 2L * 1024L * 1024L // 2MB

    // Channel fallback if Wi-Fi is not available (Channel can work over BT)
    const val CHANNEL_FALLBACK_MAX_BYTES: Long = 50L * 1024L * 1024L // 50MB

    fun buildSelectionContext(
        context: Context,
        totalSizesBytes: List<Long>,
    ): TransferSelectionContext {
        val wifiAvailable = !TransferUtils.getWifiIpAddress(context).isNullOrBlank()
        val sharedHttpBatchCandidateCount = totalSizesBytes.count { isSharedHttpBatchEligible(it) }
        return TransferSelectionContext(
            wifiAvailable = wifiAvailable,
            preferSharedHttpForBatch = wifiAvailable && sharedHttpBatchCandidateCount >= 2,
        )
    }

    /**
     * Strategy selection with Wi-Fi awareness:
     * - <= 80KB: MessageClient
     * - >= 1MB files in a Wi-Fi batch: HTTP to avoid reopening a Bluetooth channel per file
     * - <= 2MB otherwise: Channel
     * - > 2MB:
     *      - if Wi-Fi available: HTTP
     *      - else:
     *           - Channel up to 50MB
     *           - otherwise: HTTP (caller will fail without Wi-Fi IP)
     */
    fun decide(
        totalSizeBytes: Long,
        selectionContext: TransferSelectionContext,
    ): TransferStrategyKind {
        if (totalSizeBytes <= 0L) {
            // Unknown size: avoid MessageClient (it buffers whole file in memory).
            return if (selectionContext.wifiAvailable) TransferStrategyKind.HTTP else TransferStrategyKind.CHANNEL
        }

        return when {
            isMessageClientEligible(totalSizeBytes) ->
                TransferStrategyKind.MESSAGE

            selectionContext.preferSharedHttpForBatch &&
                selectionContext.wifiAvailable &&
                isSharedHttpBatchEligible(totalSizeBytes) ->
                TransferStrategyKind.HTTP

            totalSizeBytes in (MESSAGE_CLIENT_MAX_BYTES + 1)..CHANNEL_PREFERRED_MAX_BYTES ->
                TransferStrategyKind.CHANNEL

            totalSizeBytes > CHANNEL_PREFERRED_MAX_BYTES && selectionContext.wifiAvailable ->
                TransferStrategyKind.HTTP

            // If Wi-Fi not available, fallback to Channel up to 50MB
            totalSizeBytes > CHANNEL_PREFERRED_MAX_BYTES && totalSizeBytes <= CHANNEL_FALLBACK_MAX_BYTES ->
                TransferStrategyKind.CHANNEL

            // Too large for Channel fallback and no Wi-Fi
            else ->
                TransferStrategyKind.HTTP
        }
    }

    fun create(kind: TransferStrategyKind): TransferStrategy =
        when (kind) {
            TransferStrategyKind.MESSAGE -> MessageClientStrategy()
            TransferStrategyKind.CHANNEL -> ChannelClientStrategy()
            TransferStrategyKind.HTTP -> HttpTransferServer()
            TransferStrategyKind.WIFI_UPLOAD -> WifiUploadStrategy()
        }

    fun create(
        context: Context,
        totalSizeBytes: Long,
    ): TransferStrategy {
        val selectionContext = buildSelectionContext(context, listOf(totalSizeBytes))
        return create(decide(totalSizeBytes, selectionContext))
    }

    fun isMessageClientEligible(totalSizeBytes: Long): Boolean = totalSizeBytes > 0L && totalSizeBytes <= MESSAGE_CLIENT_MAX_BYTES

    fun isSharedHttpBatchEligible(totalSizeBytes: Long): Boolean = totalSizeBytes >= SHARED_HTTP_BATCH_MIN_BYTES

    fun usesWifi(kind: TransferStrategyKind): Boolean {
        val isWifiUpload = kind == TransferStrategyKind.HTTP || kind == TransferStrategyKind.WIFI_UPLOAD
        return isWifiUpload
    }

    /**
     * Do we require Wi-Fi for this size?
     * Only files > 50MB (or unknown size) require Wi-Fi,
     * because Channel fallback is allowed up to 50MB without Wi-Fi.
     */
    fun requiresWifi(totalSizeBytes: Long): Boolean = totalSizeBytes <= 0L || totalSizeBytes > CHANNEL_FALLBACK_MAX_BYTES
}
