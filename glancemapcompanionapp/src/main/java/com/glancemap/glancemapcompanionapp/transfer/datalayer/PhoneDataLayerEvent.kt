package com.glancemap.glancemapcompanionapp.transfer.datalayer

internal sealed interface PhoneDataLayerEvent {
    data class TransferStatus(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class TransferAck(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class PingResult(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class WifiStatusResult(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class ExistsResult(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class BatchExistsResult(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class DeleteFileResult(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class MapListResult(
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class ActiveHikeSnapshot(
        val sourceNodeId: String,
        val payload: ByteArray,
    ) : PhoneDataLayerEvent

    data class Error(
        val message: String,
    ) : PhoneDataLayerEvent
}
