package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import java.util.concurrent.atomic.AtomicReference

internal enum class PhoneOfflineMapImportStage {
    DOCUMENT_SELECTED,
    METADATA_READ,
    STREAM_OPEN,
    COPY,
    PROMOTION,
    VALIDATION,
    MAPFILE_OPEN,
    MAP_METADATA,
    COMPLETE,
}

internal enum class PhoneOfflineMapImportOutcome {
    SUCCESS,
    FAILED,
}

internal data class PhoneOfflineMapsforgeMetadata(
    val boundingBoxAvailable: Boolean,
    val minZoom: Int?,
    val maxZoom: Int?,
    val startPositionAvailable: Boolean,
)

internal data class PhoneOfflineMapImportAttempt(
    val outcome: PhoneOfflineMapImportOutcome,
    val failureStage: PhoneOfflineMapImportStage?,
    val displayName: String?,
    val mimeType: String?,
    val sourceSizeBytes: Long?,
    val streamOpened: Boolean?,
    val bytesCopied: Long?,
    val destinationSizeBytes: Long?,
    val candidateValid: Boolean?,
    val mapFileOpened: Boolean?,
    val metadata: PhoneOfflineMapsforgeMetadata?,
    val finalError: PhoneOfflineMapError?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
) {
    @Suppress("CyclomaticComplexMethod") // This is a linear, intentionally readable diagnostic report.
    fun toReportSection(): String =
        buildString {
            appendLine("Latest offline map import")
            appendLine("Outcome: $outcome")
            appendLine("Stage: ${failureStage ?: PhoneOfflineMapImportStage.COMPLETE}")
            appendLine("File: ${displayName ?: "unknown"}")
            appendLine("MIME type: ${mimeType ?: "unknown"}")
            appendLine("Source size: ${sourceSizeBytes ?: "unknown"}")
            appendLine("Stream opened: ${streamOpened ?: "unknown"}")
            appendLine("Bytes copied: ${bytesCopied ?: "unknown"}")
            appendLine("Destination size: ${destinationSizeBytes ?: "unknown"}")
            appendLine("Candidate validation: ${candidateValid ?: "unknown"}")
            appendLine("Mapsforge MapFile opened: ${mapFileOpened ?: "unknown"}")
            metadata?.let {
                appendLine("Bounding box available: ${it.boundingBoxAvailable}")
                appendLine("Min zoom: ${it.minZoom ?: "unknown"}")
                appendLine("Max zoom: ${it.maxZoom ?: "unknown"}")
                appendLine("Start position available: ${it.startPositionAvailable}")
            }
            appendLine("Final error: ${finalError ?: "none"}")
            exceptionClass?.let { appendLine("Exception: $it") }
            exceptionMessage?.let { appendLine("Exception message: $it") }
            append("Destination: companion private maps")
        }

    fun toCaptureLine(): String =
        "event=offline_map_import outcome=$outcome stage=${failureStage ?: PhoneOfflineMapImportStage.COMPLETE} " +
            "file=${displayName ?: "unknown"} sourceBytes=${sourceSizeBytes ?: "unknown"} " +
            "copied=${bytesCopied ?: "unknown"} destinationBytes=${destinationSizeBytes ?: "unknown"} " +
            "candidate=${candidateValid ?: "unknown"} mapFile=${mapFileOpened ?: "unknown"} " +
            "error=${finalError ?: "none"} exception=${exceptionClass ?: "none"}"
}

internal data class PhoneOfflineMapImportException(
    val className: String,
    val message: String?,
)

internal fun Throwable.toPhoneOfflineMapImportException(): PhoneOfflineMapImportException =
    PhoneOfflineMapImportException(
        className = javaClass.simpleName.ifBlank { javaClass.name },
        message = message?.redactPhoneOfflineMapDiagnosticMessage(),
    )

/** Uses the existing local phone debug capture and retains only one safe latest-attempt summary. */
internal object PhoneOfflineMapImportDiagnostics {
    const val TAG = "PhoneOfflineMapImport"

    private val latestAttempt = AtomicReference<PhoneOfflineMapImportAttempt?>(null)

    fun record(attempt: PhoneOfflineMapImportAttempt) {
        latestAttempt.set(attempt)
        PhoneDebugCapture.log(TAG, attempt.toCaptureLine())
    }

    fun latestReportSection(): String? = latestAttempt.get()?.toReportSection()

    internal fun latestAttempt(): PhoneOfflineMapImportAttempt? = latestAttempt.get()

    internal fun clear() {
        latestAttempt.set(null)
    }
}

internal fun String.redactPhoneOfflineMapDiagnosticMessage(): String =
    replace(Regex("content://\\S+", RegexOption.IGNORE_CASE), "[content-uri]")
        .replace(Regex("file://\\S+", RegexOption.IGNORE_CASE), "[file-uri]")
        .replace(Regex("(?<![A-Za-z0-9_])/(?:[^\\s/]+/)+[^\\s]*"), "[path]")
        .take(180)
