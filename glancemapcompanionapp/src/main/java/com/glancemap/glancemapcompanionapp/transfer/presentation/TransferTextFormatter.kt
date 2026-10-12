package com.glancemap.glancemapcompanionapp.transfer.presentation

import com.glancemap.glancemapcompanionapp.R

internal data class NotificationTextPresentation(
    val title: String,
    val contentText: String,
    val expandedText: String,
    val subText: String?,
    val waitingForReconnect: Boolean,
)

internal class TransferTextFormatter(
    private val text: (Int) -> String,
) {
    fun formatCardText(
        rawProgressText: String,
        statusMessage: String,
        isPaused: Boolean,
        canResume: Boolean,
        showTechnicalDetails: Boolean,
    ): String {
        val normalized = normalizedText(rawProgressText, statusMessage)
        if (showTechnicalDetails) {
            return normalized
        }
        val simpleStatus =
            simpleStatus(
                rawText = normalized,
                statusMessage = statusMessage,
                isPaused = isPaused,
                canResume = canResume,
            )
        val fileLine = extractFileLine(normalized)?.let { text(R.string.transfer_file_line).format(it.substring(5)) }
        val progressLine = extractProgressLine(normalized)?.let { localizeProgressPrefix(it) }
        return listOfNotNull(fileLine, simpleStatus, progressLine)
            .joinToString("\n")
            .ifBlank { simpleStatus }
    }

    fun buildNotificationPresentation(
        rawText: String,
        progress: Int,
        isPaused: Boolean,
        showTechnicalDetails: Boolean,
    ): NotificationTextPresentation {
        val normalized = normalizedText(rawText, rawText)
        if (showTechnicalDetails) {
            val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val primaryLine = lines.firstOrNull().orEmpty()
            val detailLine = lines.drop(1).joinToString(" • ").ifBlank { primaryLine.ifBlank { normalized } }
            val expandedText =
                if (primaryLine.isNotBlank() && primaryLine != detailLine) {
                    "$primaryLine\n$detailLine"
                } else {
                    detailLine
                }
            val waitingForReconnect = normalized.contains("Waiting for watch reconnect", ignoreCase = true)
            val title =
                when {
                    waitingForReconnect && progress < 0 -> text(R.string.transfer_status_waiting_for_watch_reconnect)
                    waitingForReconnect ->
                        text(
                            R.string.transfer_status_waiting_for_watch_reconnect_value,
                        ).format(progress.coerceIn(0, 100))
                    progress < 0 -> text(R.string.transfer_status_sending_file_to_watch)
                    else -> text(R.string.transfer_status_sending_file_to_watch_value).format(progress.coerceIn(0, 100))
                }
            return NotificationTextPresentation(
                title = title,
                contentText = detailLine,
                expandedText = expandedText,
                subText = if (primaryLine.isNotBlank() && primaryLine != detailLine) primaryLine else null,
                waitingForReconnect = waitingForReconnect,
            )
        }
        val simpleStatus =
            simpleStatus(
                rawText = normalized,
                statusMessage = normalized,
                isPaused = isPaused,
                canResume = normalized.contains("paused by user", ignoreCase = true),
            )
        val fileLine = extractFileLine(normalized)?.let { text(R.string.transfer_file_line).format(it.substring(5)) }
        val progressLine = extractProgressLine(normalized)?.let { localizeProgressPrefix(it) }
        val title =
            if (progress < 0) {
                simpleStatus
            } else {
                "$simpleStatus ${progress.coerceIn(0, 100)}%"
            }
        val contentText = progressLine ?: fileLine ?: simpleStatus.removeSuffix("…")
        val expandedText =
            listOfNotNull(fileLine, simpleStatus, progressLine)
                .joinToString("\n")
                .ifBlank { simpleStatus }

        return NotificationTextPresentation(
            title = title,
            contentText = contentText,
            expandedText = expandedText,
            subText =
                when {
                    fileLine != null && progressLine != null -> fileLine
                    fileLine != null -> simpleStatus
                    else -> null
                },
            waitingForReconnect = isWaitingText(normalized),
        )
    }

    private fun normalizedText(
        primary: String,
        secondary: String,
    ): String = primary.trim().ifBlank { secondary.trim() }.ifBlank { text(R.string.transfer_status_sending) }

    private fun simpleStatus(
        rawText: String,
        statusMessage: String,
        isPaused: Boolean,
        canResume: Boolean,
    ): String {
        val combined =
            listOf(rawText, statusMessage)
                .joinToString("\n")
                .lowercase()

        return when {
            combined.contains(
                "cancelling",
            ) ||
                combined.contains("stopping current transfer") -> text(R.string.transfer_action_stopping)
            combined.contains("validating checksum") ||
                combined.contains("checksum:") ||
                combined.contains("verifying") ||
                combined.contains("checking partial file") ||
                combined.contains("repairing partial file") ||
                combined.contains("preparing checksum") -> text(R.string.transfer_status_verifying)
            combined.contains("checking existing files on watch") ||
                combined.contains("connecting to phone") ||
                combined.contains("starting") ||
                combined.contains("preparing") -> text(R.string.transfer_status_preparing)
            isWaitingText(combined) -> text(R.string.common_status_waiting)
            isPaused && canResume -> text(R.string.transfer_status_paused)
            isPaused -> text(R.string.common_status_waiting)
            combined.contains("resuming") -> text(R.string.transfer_status_sending)
            else -> text(R.string.transfer_status_sending)
        }
    }

    private fun isWaitingText(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("waiting for watch reconnect") ||
            normalized.contains("waiting to resume") ||
            normalized.contains("waiting for wi-fi") ||
            normalized.contains("network lost") ||
            normalized.contains("connection interrupted")
    }

    private fun localizeProgressPrefix(
        line: String,
    ): String =
        when {
            line.startsWith(
                "Channel:",
                ignoreCase = true,
            ) -> text(R.string.transfer_channel_progress).format(line.substring(8))
            line.startsWith(
                "Message:",
                ignoreCase = true,
            ) -> text(R.string.transfer_message_progress).format(line.substring(8))
            line.startsWith(
                "Checksum:",
                ignoreCase = true,
            ) -> text(R.string.transfer_checksum_progress).format(line.substring(9))
            else -> line
        }

    private fun extractFileLine(text: String): String? =
        text
            .lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("File ", ignoreCase = true) }

    private fun extractProgressLine(text: String): String? =
        text
            .lines()
            .map { it.trim() }
            .lastOrNull {
                it.startsWith("HTTP:", ignoreCase = true) ||
                    it.startsWith("Channel:", ignoreCase = true) ||
                    it.startsWith("Message:", ignoreCase = true) ||
                    it.startsWith("Checksum:", ignoreCase = true)
            }
}
