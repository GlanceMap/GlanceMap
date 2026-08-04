package com.glancemap.glancemapcompanionapp.activehike

import com.glancemap.shared.transfer.ActiveHikeSnapshot
import com.glancemap.shared.transfer.ActiveHikeSnapshotCodec

data class PhoneActiveHikeSnapshot(
    val sourceNodeId: String,
    val snapshot: ActiveHikeSnapshot,
)

internal fun decodePhoneActiveHikeSnapshot(
    sourceNodeId: String,
    payload: ByteArray,
): PhoneActiveHikeSnapshot? {
    val snapshot = ActiveHikeSnapshotCodec.decode(payload) ?: return null
    return PhoneActiveHikeSnapshot(
        sourceNodeId = sourceNodeId,
        snapshot = snapshot,
    )
}
