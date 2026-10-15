package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

private const val MAX_NO_PROGRESS_RETRIES = 3

internal suspend fun retryPhoneOfflineBundleTransfer(
    temporary: File,
    onRetry: suspend (Int, Long, IOException) -> Unit,
    transfer: suspend () -> Long,
): Long {
    var noProgressRetries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val attemptStartOffset = temporary.length()
        try {
            return transfer()
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (!isRetriablePhoneOfflineBundleTransferFailure(error)) throw error
            val resumeOffset = temporary.length()
            // Match the watch: payload progress resets the consecutive failure budget.
            noProgressRetries = if (resumeOffset > attemptStartOffset) 0 else noProgressRetries + 1
            if (noProgressRetries > MAX_NO_PROGRESS_RETRIES) throw error
            onRetry(noProgressRetries.coerceAtLeast(1), resumeOffset, error)
        }
    }
}

private fun isRetriablePhoneOfflineBundleTransferFailure(error: IOException): Boolean =
    error !is PhoneOfflineHttpException &&
        error !is PhoneOfflineBundleDownloadException &&
        error !is FileNotFoundException
