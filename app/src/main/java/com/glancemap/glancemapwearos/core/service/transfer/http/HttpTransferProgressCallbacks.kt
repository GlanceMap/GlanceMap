package com.glancemap.glancemapwearos.core.service.transfer.http

import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.ProgressTracker
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferRuntimeHost
import com.glancemap.glancemapwearos.core.service.transfer.runtime.UiUpdateThrottler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class HttpTransferProgressCallbacks(
    private val host: TransferRuntimeHost,
    private val notificationHelper: NotificationHelper,
    private val metadata: ReceiverMetadata,
    private val sendStatus: suspend (sourceNodeId: String, transferId: String, phase: String, detail: String) -> Unit,
) {
    private val appScope get() = host.appScope
    private val tracker = ProgressTracker(metadata.totalSize, host.context.resources)
    private val uiThrottler =
        UiUpdateThrottler(
            minIntervalMs = 500L,
            minStepBytes =
                if (isMapLikeTransferFile(metadata.fileName)) {
                    8L * 1024 * 1024
                } else {
                    1L * 1024 * 1024
                },
        )

    private var firstBytes = true
    private var lastBytesCopied = 0L

    fun onTransferState(
        phase: String,
        detail: String,
    ) {
        if (detail.isNotBlank()) {
            val progress =
                if (metadata.totalSize > 0L) {
                    (lastBytesCopied * 100 / metadata.totalSize).toInt()
                } else {
                    -1
                }
            notificationHelper.updateForeground(
                metadata.notificationId,
                metadata.fileName,
                detail,
                progress,
            )
        }

        appScope.launch(Dispatchers.IO) {
            sendStatus(metadata.sourceNodeId, metadata.transferId, phase, detail)
        }
    }

    fun onProgress(bytesCopied: Long) {
        lastBytesCopied = bytesCopied

        if (firstBytes && bytesCopied > 0L) {
            firstBytes = false
            appScope.launch(Dispatchers.IO) {
                sendStatus(metadata.sourceNodeId, metadata.transferId, "DOWNLOADING", "")
            }
        }

        if (uiThrottler.shouldUpdate(bytesCopied)) {
            val progress =
                if (metadata.totalSize > 0) {
                    (bytesCopied * 100 / metadata.totalSize).toInt()
                } else {
                    -1
                }

            notificationHelper.updateForeground(
                metadata.notificationId,
                metadata.fileName,
                tracker.formatStatus(bytesCopied),
                progress,
            )
        }
    }

    private fun isMapLikeTransferFile(fileName: String): Boolean =
        fileName.endsWith(".map", ignoreCase = true) ||
            fileName.endsWith(".poi", ignoreCase = true) ||
            fileName.endsWith(".rd5", ignoreCase = true) ||
            fileName.endsWith(".hgt", ignoreCase = true) ||
            fileName.endsWith(".hgt.zip", ignoreCase = true) ||
            fileName.endsWith(".hgt.gz", ignoreCase = true)
}
