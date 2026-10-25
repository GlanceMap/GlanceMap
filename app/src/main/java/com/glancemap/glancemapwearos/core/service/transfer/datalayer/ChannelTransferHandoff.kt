package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import com.google.android.gms.wearable.ChannelClient
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

internal const val CHANNEL_HANDOFF_MISSING = "CHANNEL_HANDOFF_MISSING"

internal data class ChannelTransferPrewarmMetadata(
    val expectedSize: Long?,
    val expectedChecksum: String?,
)

internal typealias ChannelPrewarmMetadata = ChannelTransferPrewarmMetadata

internal data class ChannelTransferHandoff(
    val channel: ChannelClient.Channel,
    val expectedChecksum: String?,
    val expectedSize: Long? = null,
)

internal fun channelTransferIntegrityFailureReason(
    fileName: String,
    expectedSize: Long?,
    expectedChecksum: String?,
): String? =
    when {
        fileName.endsWith(".map", ignoreCase = true) && (expectedSize == null || expectedSize <= 0L) ->
            "missing_expected_size"
        expectedChecksum.isNullOrBlank() -> "missing_expected_checksum"
        else -> null
    }

internal fun channelTransferFailureReason(detail: String?): String =
    when {
        detail.isNullOrBlank() -> "transfer_failed"
        detail.startsWith("unexpected_eof") || detail.startsWith("INCOMPLETE_FILE:") -> "unexpected_eof"
        detail.startsWith("unexpected_extra_data") || detail.startsWith("TOO_MUCH_DATA:") -> "unexpected_extra_data"
        detail.startsWith("missing_expected_size") -> "missing_expected_size"
        detail.startsWith("missing_expected_checksum") -> "missing_expected_checksum"
        detail.startsWith("checksum_compute_failed") -> "checksum_compute_failed"
        detail.startsWith("checksum_mismatch") || detail.startsWith("CHECKSUM_MISMATCH") -> "checksum_mismatch"
        else -> "transfer_failed"
    }

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
