package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import com.glancemap.glancemapwearos.core.service.DataLayerListenerService
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferSessionState
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.sync.Mutex

@Suppress("LongParameterList") // Existing handler dependencies are kept explicit at the Data Layer boundary.
internal class DataLayerHandlers(
    private val service: DataLayerListenerService,
    private val notificationHelper: NotificationHelper,
    private val fileOps: WatchFileOps,
    private val transferMutex: Mutex,
    private val sessionState: TransferSessionState,
    private val sendStatus: suspend (sourceNodeId: String, transferId: String, phase: String, detail: String) -> Unit,
    private val sendAck: suspend (sourceNodeId: String, transferId: String, status: String, detail: String) -> Unit,
    private val sendMessage: suspend (sourceNodeId: String, path: String, payload: ByteArray) -> Unit,
) {
    private val messageHandler =
        DataLayerMessageRequestHandler(
            service = service,
            notificationHelper = notificationHelper,
            fileOps = fileOps,
            transferMutex = transferMutex,
            sessionState = sessionState,
            sendStatus = sendStatus,
            sendAck = sendAck,
            sendMessage = sendMessage,
        )

    fun handleMessage(messageEvent: MessageEvent) {
        messageHandler.handleMessage(messageEvent)
    }

    fun popChannelChecksum(transferId: String): String? = messageHandler.popChannelChecksum(transferId)
}
