package com.glancemap.glancemapcompanionapp.refuges

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class PoiFileMerger(
    private val context: Context,
) {
    suspend fun mergeImportedFiles(
        outputFileName: String,
        sourceFileNamesInPriorityOrder: List<String>,
        bboxQuery: String,
    ): RefugesImportResult =
        withContext(Dispatchers.IO) {
            if (sourceFileNamesInPriorityOrder.isEmpty()) {
                throw IllegalArgumentException("No source POI files provided for merge.")
            }

            val outputDir = PhoneOfflineStorage(context).poiDirectory().apply { mkdirs() }
            val sources =
                sourceFileNamesInPriorityOrder
                    .map { File(outputDir, File(it).name) }
                    .filter { it.exists() && it.isFile }

            if (sources.isEmpty()) {
                throw IllegalStateException("No source POI files found for merge.")
            }

            val safeOutputFileName = normalizeFileName(outputFileName)
            val outputFile = File(outputDir, safeOutputFileName)
            val summary =
                PoiSqliteCodec
                    .openStreamingWriter(
                        file = outputFile,
                        options =
                            PoiSqliteWriteOptions(
                                comment = "Data sources: refuges.info + openstreetmap.org",
                                writer = "glancemap-poi-merge-1",
                                extraMetadata =
                                    linkedMapOf(
                                        "refuges_bbox_query" to bboxQuery,
                                        "enriched_with_osm" to "true",
                                    ),
                            ),
                    ).use { writer ->
                        sources.forEach { file ->
                            writer.append(PoiSqliteCodec.read(file))
                        }
                        writer.finish()
                    }

            val uri: Uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outputFile,
                )

            RefugesImportResult(
                poiUri = uri,
                fileName = outputFile.name,
                pointCount = summary.pointCount,
                categoryCount = summary.categoryCount,
                bbox = bboxQuery,
            )
        }

    private fun normalizeFileName(input: String): String {
        val base =
            input
                .trim()
                .ifBlank { "poi-merged.poi" }
                .replace("\\", "_")
                .replace("/", "_")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "poi-merged.poi" }
        return if (base.lowercase(Locale.ROOT).endsWith(".poi")) base else "$base.poi"
    }
}
