package com.glancemap.glancemapwearos.core.service.transfer.runtime

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

internal class ForegroundTransferSessionOwner {
    data class Session(
        val startId: Int,
        val transferId: String,
        val fileName: String,
        val sourceNodeId: String,
        val notificationId: Int,
        val job: Job,
        val terminalState: TransferTerminalState = TransferTerminalState(transferId),
    )

    private val sessionsByStartId = ConcurrentHashMap<Int, Session>()

    @Suppress("LongParameterList")
    fun begin(
        startId: Int,
        transferId: String,
        fileName: String,
        sourceNodeId: String,
        notificationId: Int,
        job: Job,
    ): Session {
        val session =
            Session(
                startId = startId,
                transferId = transferId,
                fileName = fileName,
                sourceNodeId = sourceNodeId,
                notificationId = notificationId,
                job = job,
            )
        sessionsByStartId[startId] = session
        return session
    }

    fun activeForStartId(startId: Int): Session? = sessionsByStartId[startId]

    fun activeForTransferId(transferId: String): Session? =
        sessionsByStartId.values
            .firstOrNull { it.transferId == transferId }

    fun claim(
        startId: Int,
        outcome: TransferTerminalOutcome,
    ): Session? {
        val session = activeForStartId(startId) ?: return null
        return session.takeIf { it.terminalState.tryClaim(outcome) }
    }

    fun claimForTransferId(
        transferId: String,
        outcome: TransferTerminalOutcome,
    ): Boolean =
        activeForTransferId(transferId)
            ?.terminalState
            ?.tryClaim(outcome)
            ?: false

    fun outcomeForTransferId(transferId: String): TransferTerminalOutcome? =
        activeForTransferId(transferId)
            ?.terminalState
            ?.current()

    fun end(
        startId: Int,
        transferId: String,
    ): Boolean {
        val session = sessionsByStartId[startId] ?: return false
        return session.transferId == transferId && sessionsByStartId.remove(startId, session)
    }

    fun all(): List<Session> = sessionsByStartId.values.toList()
}
