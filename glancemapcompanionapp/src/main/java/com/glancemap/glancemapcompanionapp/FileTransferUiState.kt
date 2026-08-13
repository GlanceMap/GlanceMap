package com.glancemap.glancemapcompanionapp

import android.net.Uri

data class WatchNode(
    val id: String,
    val displayName: String,
)

data class FileTransferHistoryItem(
    val id: Long,
    val fileName: String,
    val detail: String,
    val success: Boolean,
)

data class FileTransferUiState(
    val availableWatches: List<WatchNode> = emptyList(),
    val selectedWatch: WatchNode? = null,
    // ✅ Multi-file selection
    val selectedFileUris: List<Uri> = emptyList(),
    val selectedFileDisplayNames: List<String> = emptyList(),
    val statusMessage: String = "",
    val isTransferring: Boolean = false,
    // ✅ Pause/Resume UI state
    val isPaused: Boolean = false,
    val canResume: Boolean = false,
    val pauseReason: String = "",
    val progress: Float = 0f,
    val progressText: String = "",
    val history: List<FileTransferHistoryItem> = emptyList(),
)
