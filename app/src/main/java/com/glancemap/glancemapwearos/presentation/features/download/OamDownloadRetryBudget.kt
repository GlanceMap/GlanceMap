package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import java.io.IOException

internal class OamDownloadRetryBudget(
    private val maxNoProgressRetries: Int,
) {
    init {
        require(maxNoProgressRetries >= 0) { "maxNoProgressRetries must be non-negative" }
    }

    private var noProgressRetryCount = 0

    fun shouldRetry(
        attemptStartOffset: Long,
        resumeOffsetAfterFailure: Long,
    ): Boolean {
        val madeProgress = resumeOffsetAfterFailure > attemptStartOffset
        if (madeProgress) {
            noProgressRetryCount = 0
        }
        val canRetry = madeProgress || noProgressRetryCount < maxNoProgressRetries
        if (canRetry && !madeProgress) {
            noProgressRetryCount += 1
        }
        return canRetry
    }

    fun currentRetryCount(): Int = noProgressRetryCount
}

internal fun oamNoProgressFailureMessage(
    fileName: String,
    maxRetries: Int,
    offset: Long,
    error: IOException,
    resources: Resources,
): String =
    resources.getQuantityString(
        R.plurals.download_no_payload_progress_for_after_retries_at_offset,
        maxRetries,
        fileName,
        maxRetries,
        offset,
        error.message ?: error.javaClass.simpleName,
    )
