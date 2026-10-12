package com.glancemap.glancemapcompanionapp

import com.glancemap.glancemapcompanionapp.R
import java.util.Locale
import java.util.concurrent.TimeoutException

internal fun isSelectedWatchReachable(
    selectedWatch: WatchNode?,
    availableWatches: List<WatchNode>,
): Boolean {
    if (selectedWatch == null) return false
    return availableWatches.any { it.id == selectedWatch.id }
}

internal fun selectedWatchDisconnectedStatusMessage(text: (Int) -> String) = text(R.string.watch_status_disconnected)

internal fun selectedWatchTimeoutStatusMessage(text: (Int) -> String) = text(R.string.watch_status_timeout)

internal fun normalizeWatchMapsStatusMessage(
    text: (Int) -> String,
    error: Throwable,
): String {
    if (error is TimeoutException) {
        return selectedWatchTimeoutStatusMessage(text)
    }

    val normalized = buildErrorMessageChain(error)
    return when {
        normalized.contains("target_node_not_connected") ||
            normalized.contains("target node not connected") ||
            normalized.contains("node not connected") ->
            selectedWatchDisconnectedStatusMessage(text)

        normalized.contains("did not answer in time") ||
            normalized.contains("timed out") ||
            normalized.contains("timeout") ->
            selectedWatchTimeoutStatusMessage(text)

        else ->
            error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: text(R.string.watch_connection_failed_to_read_watch_maps)
    }
}

private fun buildErrorMessageChain(error: Throwable): String {
    val parts = ArrayList<String>()
    var current: Throwable? = error
    while (current != null) {
        current.message
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)
        current = current.cause
    }
    return parts.joinToString(" | ").lowercase(Locale.ROOT)
}
