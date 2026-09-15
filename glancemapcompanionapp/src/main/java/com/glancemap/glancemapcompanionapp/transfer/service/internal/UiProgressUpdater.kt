package com.glancemap.glancemapcompanionapp.transfer.service.internal

import android.os.SystemClock
import com.glancemap.glancemapcompanionapp.FileTransferUiState
import com.glancemap.glancemapcompanionapp.transfer.util.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt

internal class UiProgressUpdater(
    private val uiState: MutableStateFlow<FileTransferUiState>,
    private val notificationHelper: NotificationHelper,
) {
    private var lastUiUpdateMs: Long = 0L
    private var lastUiProgressInt: Int = -1
    private var lastUiText: String = ""

    fun reset() {
        lastUiUpdateMs = 0L
        lastUiProgressInt = -1
        lastUiText = ""
    }

    fun update(
        progress: Float,
        text: String,
    ) {
        val p = progress.coerceIn(0f, 1f)
        val pInt = (p * 100).roundToInt().coerceIn(0, 100)

        if (!shouldUpdateUi(pInt, text)) return

        lastUiUpdateMs = SystemClock.elapsedRealtime()
        lastUiProgressInt = pInt
        lastUiText = text

        val paused = uiState.value.isPaused
        uiState.update { it.copy(progress = p, progressText = text, statusMessage = text) }
        notificationHelper.updateProgress(pInt, text, paused)
    }

    private fun shouldUpdateUi(
        progressInt: Int,
        text: String,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (progressInt <= 0 || progressInt >= 100) return true

        val textChanged = text != lastUiText
        val timeOk = (now - lastUiUpdateMs) >= 500L
        val progressOk = abs(progressInt - lastUiProgressInt) >= 1 || lastUiProgressInt < 0

        return textChanged || timeOk || progressOk
    }
}
