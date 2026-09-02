package com.glancemap.glancemapcompanionapp.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.map.PhoneCompassDiagnostics
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMapImportDiagnostics
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMapRendererDiagnostics
import com.glancemap.shared.transfer.TransferDataLayerContract
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CompanionDiagnosticsEmailComposer {
    private const val DIAGNOSTICS_DIR_NAME = "diagnostics"
    private const val PHONE_DIAGNOSTICS_PREFIX = "glancemap_phone_diagnostics_"

    private val filenameFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())

    fun composePhoneDiagnosticsEmail(
        context: Context,
        slot: PhoneDebugCaptureSlot = PhoneDebugCaptureSlot.CURRENT,
    ) {
        val appContext = context.applicationContext
        val diagnosticsText =
            PhoneDebugCapture.buildReport(
                context = appContext,
                additionalSections = phoneDiagnosticsAdditionalSections(),
                slot = slot,
            )
        val file = saveLatestPhoneDiagnosticsFile(appContext, diagnosticsText)
        composeEmailWithFile(context, file)
    }

    fun composePreviousPhoneDiagnosticsEmail(context: Context) {
        require(PhoneDebugCapture.hasCapture(PhoneDebugCaptureSlot.PREVIOUS))
        composePhoneDiagnosticsEmail(context, PhoneDebugCaptureSlot.PREVIOUS)
    }

    fun composeLatestPhoneDiagnosticsEmail(context: Context) {
        val appContext = context.applicationContext
        val file =
            latestSavedPhoneDiagnosticsFile(appContext)
                ?: throw IllegalStateException("No saved phone recording available")
        composeEmailWithFile(context, file)
    }

    fun hasSavedPhoneDiagnostics(context: Context): Boolean = latestSavedPhoneDiagnosticsFile(context.applicationContext) != null

    fun latestSavedPhoneDiagnosticsFile(context: Context): File? {
        val dir = diagnosticsDir(context.applicationContext)
        return dir
            .listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith(PHONE_DIAGNOSTICS_PREFIX) &&
                    file.name.endsWith(".txt")
            }?.maxByOrNull { it.lastModified() }
    }

    private fun composeEmailWithFile(
        context: Context,
        file: File,
    ) {
        val appContext = context.applicationContext
        val uri =
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
        val subject =
            "${TransferDataLayerContract.DIAGNOSTICS_SUBJECT_PREFIX} ${file.nameWithoutExtension}"
        val body = "Phone diagnostics attached.\nFile: ${file.name}"

        val emailIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(TransferDataLayerContract.DIAGNOSTICS_SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        val chooserIntent =
            Intent.createChooser(emailIntent, "Send diagnostics").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        try {
            context.startActivity(chooserIntent)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("No email app available", error)
        }
    }

    private fun saveLatestPhoneDiagnosticsFile(
        context: Context,
        text: String,
    ): File {
        val dir = diagnosticsDir(context)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        dir.listFiles()?.forEach { file ->
            if (
                file.isFile &&
                file.name.startsWith(PHONE_DIAGNOSTICS_PREFIX) &&
                file.name.endsWith(".txt")
            ) {
                runCatching { file.delete() }
            }
        }

        val captureState = PhoneDebugCapture.state.value
        val sessionPart =
            captureState.sessionId
                .takeIf { it > 0L }
                ?.let { "_s$it" }
                .orEmpty()
        val devicePart = buildDeviceSlug()
        val fileName =
            "${PHONE_DIAGNOSTICS_PREFIX}${filenameFormatter.format(Instant.now())}_${devicePart}$sessionPart.txt"
        val file = File(dir, fileName)
        file.writeText(text)
        return file
    }

    private fun diagnosticsDir(context: Context): File = File(context.filesDir, DIAGNOSTICS_DIR_NAME)

    private fun buildDeviceSlug(): String {
        val raw = "${Build.MANUFACTURER}_${Build.MODEL}"
        val normalized =
            raw
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "android_phone" }
        return normalized.take(40)
    }
}

internal fun phoneDiagnosticsAdditionalSections(): List<String> =
    listOfNotNull(
        PhoneOfflineMapImportDiagnostics.latestReportSection(),
        PhoneOfflineMapRendererDiagnostics.latestReportSection(),
        PhoneCompassDiagnostics.latestReportSection(),
    )
