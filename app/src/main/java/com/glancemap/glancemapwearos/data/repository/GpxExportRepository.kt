package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import android.net.Uri
import com.glancemap.glancemapwearos.R
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

interface GpxExportRepository {
    suspend fun sendGpxToPhone(
        file: File,
        displayName: String,
    ): Result<Unit>
}

class GpxExportRepositoryImpl(
    context: Context,
) : GpxExportRepository {
    private val appContext = context.applicationContext
    private val channelClient by lazy { Wearable.getChannelClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }

    override suspend fun sendGpxToPhone(
        file: File,
        displayName: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(file.exists() && file.isFile) { appContext.getString(R.string.gpx_gpx_file_not_found) }
                val targetNode =
                    nodeClient.connectedNodes
                        .await()
                        .sortedByDescending { it.isNearby }
                        .firstOrNull()
                        ?: error(appContext.getString(R.string.gpx_no_phone_connected))

                sendFileToNode(
                    nodeId = targetNode.id,
                    file = file,
                    displayName = displayName,
                )
            }
        }

    private suspend fun sendFileToNode(
        nodeId: String,
        file: File,
        displayName: String,
    ) {
        val safeName = sanitizeGpxFileName(displayName.ifBlank { file.name })
        val transferId = UUID.randomUUID().toString()
        val sha256 = file.sha256()
        val path =
            "${TransferDataLayerContract.CHANNEL_WATCH_GPX_EXPORT_PREFIX}/" +
                "$transferId/$sha256/${Uri.encode(safeName)}"
        var channel: ChannelClient.Channel? = null

        try {
            channel =
                withTimeout(OPEN_CHANNEL_TIMEOUT_MS) {
                    channelClient.openChannel(nodeId, path).await()
                }

            val rawOut =
                withTimeout(OPEN_STREAM_TIMEOUT_MS) {
                    channelClient.getOutputStream(channel).await()
                }

            BufferedOutputStream(rawOut, CHANNEL_BUFFER_BYTES).use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output, CHANNEL_BUFFER_BYTES)
                }
            }
        } finally {
            channel?.let { runCatching { channelClient.close(it).await() } }
        }
    }

    private fun sanitizeGpxFileName(name: String): String {
        val clean =
            name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\r\\n\\t]"), " ")
                .trim()
                .ifBlank { "track.gpx" }
        return if (clean.endsWith(".gpx", ignoreCase = true)) clean else "$clean.gpx"
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(CHANNEL_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val OPEN_CHANNEL_TIMEOUT_MS = 20_000L
        private const val OPEN_STREAM_TIMEOUT_MS = 15_000L
        private const val CHANNEL_BUFFER_BYTES = 64 * 1024
    }
}
