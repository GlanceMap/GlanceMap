package com.glancemap.glancemapcompanionapp.transfer.strategy

import android.content.Context
import android.net.Uri
import com.glancemap.glancemapcompanionapp.transfer.wifi.WifiUploadClient
import com.glancemap.glancemapcompanionapp.transfer.wifi.WifiUploadRequest
import com.glancemap.glancemapcompanionapp.transfer.wifi.WifiWatchEndpoint
import kotlinx.coroutines.CompletableDeferred

class WifiUploadStrategy :
    TransferStrategy,
    AutoCloseable {
    private val client = WifiUploadClient()

    override suspend fun transfer(
        context: Context,
        fileUri: Uri,
        targetNodeId: String,
        metadata: TransferMetadata,
        ackDeferred: CompletableDeferred<TransferResult>,
        awaitIfPaused: suspend () -> Unit,
        onProgress: (Float, String) -> Unit,
    ): TransferResult {
        val endpoint = WifiWatchEndpoint.parse(targetNodeId)
        val input = endpoint?.let { context.contentResolver.openInputStream(fileUri) }
        if (endpoint == null || input == null) {
            return TransferResult(
                false,
                if (endpoint == null) "无效的 Wi-Fi 手表地址" else "无法读取文件：${metadata.displayFileName}",
            )
        }
        val result =
            client.upload(
                WifiUploadRequest(
                    endpoint = endpoint,
                    displayName = metadata.displayFileName,
                    input = input,
                    totalSize = metadata.totalSize,
                    sha256 = metadata.checksumSha256,
                    transferId = metadata.transferId,
                    awaitIfPaused = awaitIfPaused,
                    onProgress = onProgress,
                ),
            )
        return if (checksumMismatch(metadata, result)) {
            TransferResult(false, "校验失败：手表回传的 SHA-256 不一致")
        } else {
            TransferResult(result.success, result.message)
        }
    }

    override fun close() {
        client.close()
    }

    private fun checksumMismatch(
        metadata: TransferMetadata,
        result: WifiUploadClient.Result,
    ): Boolean {
        val expected = metadata.checksumSha256 ?: return false
        val actual = result.sha256
        return result.success && actual != null && !actual.equals(expected, ignoreCase = true)
    }
}
