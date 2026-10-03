package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import com.google.android.gms.wearable.ChannelClient
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

internal const val CHANNEL_HANDOFF_MISSING = "CHANNEL_HANDOFF_MISSING"

internal data class ChannelTransferHandoff(
    val channel: ChannelClient.Channel,
    val expectedChecksum: String?,
)

internal data class ChannelTransferAckOutcome(
    val sourceNodeId: String,
    val transferId: String,
    val status: String,
    val detail: String,
)

internal fun missingChannelHandoffAck(
    sourceNodeId: String,
    transferId: String,
): ChannelTransferAckOutcome =
    ChannelTransferAckOutcome(
        sourceNodeId = sourceNodeId,
        transferId = transferId,
        status = "ERROR",
        detail = CHANNEL_HANDOFF_MISSING,
    )

internal class ChannelTransferAdmissionGate(
    private val transferMutex: Mutex,
) {
    suspend fun acquire(): Any {
        val owner = Any()
        transferMutex.lock(owner)
        return owner
    }

    fun release(owner: Any) {
        transferMutex.unlock(owner)
    }
}

internal object ChannelTransferHandoffRegistry {
    private val pending = ConcurrentHashMap<String, ChannelTransferHandoff>()
    private val admitted = ConcurrentHashMap<String, Any>()

    fun offer(
        transferId: String,
        handoff: ChannelTransferHandoff,
    ): Boolean = pending.putIfAbsent(transferId, handoff) == null

    fun take(transferId: String): ChannelTransferHandoff? = pending.remove(transferId)

    fun remove(transferId: String): ChannelTransferHandoff? = pending.remove(transferId)

    fun offerAdmission(
        transferId: String,
        owner: Any,
    ): Boolean = admitted.putIfAbsent(transferId, owner) == null

    fun takeAdmission(transferId: String): Any? = admitted.remove(transferId)

    fun removeAdmission(transferId: String): Any? = admitted.remove(transferId)
}
