package com.glancemap.shared.transfer

/**
 * Compact companion-to-watch preference controlling optional live-hike dashboard updates.
 *
 * Navigation and recording remain local to the watch. This setting only controls the latest
 * status snapshots that the watch sends to the companion.
 */
object LiveHikeSyncSettingsCodec {
    const val DEFAULT_ENABLED = true

    fun encode(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) ENABLED else DISABLED)

    fun decode(payload: ByteArray): Boolean? =
        when (payload.singleOrNull()) {
            ENABLED -> true
            DISABLED -> false
            else -> null
        }

    private const val ENABLED: Byte = 1
    private const val DISABLED: Byte = 0
}
