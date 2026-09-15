package com.glancemap.glancemapcompanionapp.transfer.service.internal

import android.net.Uri
import com.glancemap.glancemapcompanionapp.transfer.TransferStrategyFactory
import com.glancemap.glancemapcompanionapp.transfer.TransferStrategyKind
import com.glancemap.glancemapcompanionapp.transfer.strategy.HttpTransferServer
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferResult
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferStrategy
import com.glancemap.shared.transfer.TransferDataLayerContract

internal data class FileItem(
    val uri: Uri,
    val displayName: String,
    val size: Long,
)

internal data class PlannedTransfer(
    val item: FileItem,
    val strategyKind: TransferStrategyKind,
)

internal fun shouldFallbackToChannel(
    strategy: TransferStrategy,
    fileSize: Long,
    result: TransferResult,
): Boolean {
    if (result.success) return false
    if (strategy !is HttpTransferServer) return false
    if (isTerminalForegroundServiceFailure(result.message)) return false

    val msg = result.message.lowercase()
    if (msg.contains("file_exists")) return false
    if (msg.contains("cancelled")) return false
    if (
        fileSize > TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES &&
        !shouldAllowLargeChannelRescueFallback(result)
    ) {
        return false
    }
    return true
}

internal fun fallbackDecisionReason(
    fileSize: Long,
    result: TransferResult,
): String =
    when {
        result.success -> "result_success"
        isTerminalForegroundServiceFailure(result.message) -> "terminal_fgs_failure"
        result.message.contains("file_exists", ignoreCase = true) -> "file_exists"
        result.message.contains("cancelled", ignoreCase = true) -> "cancelled"
        fileSize > TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES &&
            !shouldAllowLargeChannelRescueFallback(result) -> "file_too_large"
        else -> "eligible_http_failure"
    }

internal fun shouldPreferChannelForRemainingBatch(result: TransferResult): Boolean {
    val msg = result.message.lowercase()
    return isLikelyDifferentSubnetHttpFailure(result.message) ||
        msg.contains("no wi-fi network available") ||
        msg.contains(HttpTransferServer.RESULT_HTTP_NO_FIRST_REQUEST_PREFIX.lowercase()) ||
        isExplicitPhoneHttpUnreachableFailure(result.message)
}

internal fun shouldAllowLargeChannelRescueFallback(result: TransferResult): Boolean =
    isLikelyDifferentSubnetHttpFailure(result.message) ||
        isExplicitPhoneHttpUnreachableFailure(result.message)

internal fun isExplicitPhoneHttpUnreachableFailure(message: String): Boolean {
    val msg = message.lowercase()
    return msg.contains("cannot reach phone http server") ||
        msg.contains("failed to connect to phone http server") ||
        msg.contains("failed to connect to /")
}

internal fun isForegroundServiceQuotaExhaustedFailure(message: String): Boolean =
    message.contains(
        TransferDataLayerContract.FGS_DATA_SYNC_QUOTA_EXHAUSTED,
        ignoreCase = true,
    )

internal fun isForegroundServiceStartNotAllowedFailure(message: String): Boolean =
    message.contains(
        TransferDataLayerContract.FGS_START_NOT_ALLOWED,
        ignoreCase = true,
    )

internal fun isForegroundServiceDataSyncTimeoutFailure(message: String): Boolean =
    message.contains(
        TransferDataLayerContract.FGS_DATA_SYNC_TIMEOUT,
        ignoreCase = true,
    )

internal fun isTerminalForegroundServiceFailure(message: String): Boolean =
    isForegroundServiceQuotaExhaustedFailure(message) ||
        isForegroundServiceStartNotAllowedFailure(message) ||
        isForegroundServiceDataSyncTimeoutFailure(message)

internal fun shouldConsiderFreshHttpRetry(result: TransferResult): Boolean =
    !result.success &&
        !isTerminalForegroundServiceFailure(result.message) &&
        !isLikelyDifferentSubnetHttpFailure(result.message)

internal fun isLikelyDifferentSubnetHttpFailure(message: String): Boolean {
    val normalized = message.lowercase()
    if (
        !normalized.contains("cannot reach phone http server") &&
        !normalized.contains("failed to connect to phone http server")
    ) {
        return false
    }

    val phoneIp = PHONE_HTTP_URL_IPV4_REGEX.find(message)?.groupValues?.get(1) ?: return false
    val watchIp = WATCH_SOURCE_IPV4_REGEX.find(message)?.groupValues?.get(1) ?: return false
    return areClearlyDifferentPrivateSubnets(phoneIp, watchIp)
}

internal fun areClearlyDifferentPrivateSubnets(
    lhs: String,
    rhs: String,
): Boolean {
    val left = parsePrivateIpv4(lhs) ?: return false
    val right = parsePrivateIpv4(rhs) ?: return false
    return left[0] != right[0] || left[1] != right[1] || left[2] != right[2]
}

internal fun extractTransferredBytesFromRetryMessage(message: String): Long? =
    HTTP_RETRY_SENT_BYTES_REGEX
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

internal fun hasMeaningfulFreshHttpRetryProgress(
    previousSentBytes: Long?,
    currentSentBytes: Long?,
    totalSize: Long,
): Boolean {
    val previous = previousSentBytes ?: return false
    val current = currentSentBytes ?: return false
    if (current <= previous) return false

    val thresholdBytes =
        when {
            totalSize >= 512L * 1024L * 1024L -> 32L * 1024L * 1024L
            totalSize >= 128L * 1024L * 1024L -> 16L * 1024L * 1024L
            else -> 4L * 1024L * 1024L
        }
    return (current - previous) >= thresholdBytes
}

private fun parsePrivateIpv4(ip: String): IntArray? {
    val octets =
        ip
            .split('.')
            .mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return null

    val first = octets[0]
    val second = octets[1]
    val isPrivate =
        when {
            first == 10 -> true
            first == 192 && second == 168 -> true
            first == 172 && second in 16..31 -> true
            else -> false
        }
    if (!isPrivate) return null
    return intArrayOf(octets[0], octets[1], octets[2], octets[3])
}

private val PHONE_HTTP_URL_IPV4_REGEX = Regex("""http://((?:\d{1,3}\.){3}\d{1,3})""")
private val WATCH_SOURCE_IPV4_REGEX = Regex("""from /((?:\d{1,3}\.){3}\d{1,3})""")
private val HTTP_RETRY_SENT_BYTES_REGEX = Regex("""\bsent=(\d+)""")

internal fun normalizeRetryFileExists(result: TransferResult): TransferResult =
    if (!result.success && result.message.startsWith("FILE_EXISTS:", ignoreCase = true)) {
        TransferResult(
            success = true,
            message = "Transfer complete (already present on watch)",
        )
    } else {
        result
    }

internal fun buildHttpRetryText(result: TransferResult): String {
    val normalized = result.message.lowercase()
    return when {
        normalized.contains(HttpTransferServer.RESULT_HTTP_STALLED_PREFIX.lowercase()) ->
            "Transfer stalled. Refreshing connection and retrying current file…"

        normalized.contains(HttpTransferServer.RESULT_HTTP_SLOW_PREFIX.lowercase()) ->
            "Transfer slowed down too much. Refreshing connection and retrying current file…"

        normalized.contains(HttpTransferServer.RESULT_HTTP_RECONNECT_TIMEOUT_PREFIX.lowercase()) ->
            "Watch reconnect took too long. Refreshing connection and retrying current file…"

        else ->
            "Refreshing HTTP connection and retrying…"
    }
}

internal fun isManualHttpPauseResult(result: TransferResult): Boolean =
    !result.success &&
        result.message.startsWith(HttpTransferServer.RESULT_HTTP_PAUSED_PREFIX, ignoreCase = true)

internal fun buildManualPauseProgressText(
    filePrefix: String,
    existingText: String,
): String {
    val httpLine =
        existingText
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("HTTP:", ignoreCase = true) }

    return listOfNotNull(
        filePrefix.takeIf { it.isNotBlank() },
        "Paused. Ready to resume from partial.",
        httpLine,
    ).joinToString("\n")
}

internal fun terminalTransferErrorProgressText(errorMessage: String): String = "Error: $errorMessage"

internal fun toUserFacingTransferError(result: TransferResult): String {
    val normalized = result.message.lowercase()
    return when {
        isForegroundServiceQuotaExhaustedFailure(result.message) ->
            "The watch cannot start another background file transfer right now. " +
                "Open GlanceMap on the watch and try again."

        isForegroundServiceStartNotAllowedFailure(result.message) ->
            "The watch could not start the background file transfer. Open GlanceMap on the watch and try again."

        isForegroundServiceDataSyncTimeoutFailure(result.message) ->
            "The watch reached Android's background transfer time limit. " +
                "Open GlanceMap on the watch and try the transfer again."

        normalized.contains(HttpTransferServer.RESULT_HTTP_STALLED_PREFIX.lowercase()) ->
            "Transfer stalled while waiting for the watch to reconnect."

        normalized.contains(HttpTransferServer.RESULT_HTTP_SLOW_PREFIX.lowercase()) ->
            "Transfer became too slow and could not recover automatically."

        normalized.contains(HttpTransferServer.RESULT_HTTP_RECONNECT_TIMEOUT_PREFIX.lowercase()) ->
            "The watch did not reconnect in time."

        normalized.contains(HttpTransferServer.RESULT_HTTP_NO_FIRST_REQUEST_PREFIX.lowercase()) ->
            "The watch did not connect to the phone HTTP server. Check watch Wi-Fi or phone hotspot."

        else ->
            result.message
    }
}

internal fun isSupportedTransferFileName(fileName: String): Boolean =
    fileName.endsWith(".gpx", ignoreCase = true) ||
        isMapLikeTransferFile(fileName) ||
        isDemTransferFile(fileName)

internal fun isReplaceableTransferFileName(fileName: String): Boolean = fileName.endsWith(".rd5", ignoreCase = true)

internal fun isMapLikeTransferFile(fileName: String): Boolean =
    fileName.endsWith(".map", ignoreCase = true) ||
        fileName.endsWith(".poi", ignoreCase = true) ||
        fileName.endsWith(".rd5", ignoreCase = true)

internal fun isDemTransferFile(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower.endsWith(".hgt") || lower.endsWith(".hgt.zip") || lower.endsWith(".hgt.gz")
}
