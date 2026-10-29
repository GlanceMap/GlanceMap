package com.glancemap.glancemapwearos.core.service.transfer.http

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.notifications.themePackageFailureMessage
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalOutcome

internal data class HttpTransferTerminalResult(
    val phase: String,
    val ackStatus: String,
    val detail: String,
)

internal class HttpTransferResultNotifier(
    private val resources: Resources,
    private val notificationHelper: NotificationHelper,
    private val claimTerminal: (transferId: String, outcome: TransferTerminalOutcome) -> Boolean,
) {
    fun onSuccess(metadata: ReceiverMetadata): HttpTransferTerminalResult? {
        if (!claimTerminal(metadata.transferId, TransferTerminalOutcome.DONE)) return null
        notificationHelper.showCompletion(
            metadata.notificationId,
            metadata.fileName,
            resources.getString(R.string.gpx_saved),
        )
        return HttpTransferTerminalResult(phase = "DONE", ackStatus = "DONE", detail = "")
    }

    fun onCancelled(metadata: ReceiverMetadata): HttpTransferTerminalResult? {
        if (!claimTerminal(metadata.transferId, TransferTerminalOutcome.CANCELLED)) return null
        notificationHelper.showError(
            metadata.notificationId,
            metadata.fileName,
            resources.getString(R.string.download_cancelled),
        )
        return HttpTransferTerminalResult(phase = "CANCELLED", ackStatus = "ERROR", detail = "Cancelled")
    }

    fun onError(
        metadata: ReceiverMetadata,
        error: Exception,
    ): HttpTransferTerminalResult? {
        if (!claimTerminal(metadata.transferId, TransferTerminalOutcome.ERROR)) return null
        notificationHelper.showError(
            metadata.notificationId,
            metadata.fileName,
            resources.getString(R.string.download_failed, themePackageFailureMessage(error, resources)),
        )
        val detail = error.message ?: "Unknown error"
        return HttpTransferTerminalResult(phase = "ERROR", ackStatus = "ERROR", detail = detail)
    }
}
