package com.glancemap.glancemapcompanionapp.transfer.wifi

import com.glancemap.shared.transfer.WifiTransferContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class WifiUploadRequest(
    val endpoint: WifiWatchEndpoint,
    val displayName: String,
    val input: InputStream,
    val totalSize: Long,
    val sha256: String?,
    val transferId: String,
    val awaitIfPaused: suspend () -> Unit,
    val onProgress: (Float, String) -> Unit,
)

class WifiUploadClient : AutoCloseable {
    @Volatile
    private var connection: HttpURLConnection? = null

    @Suppress("TooGenericExceptionCaught") // Any network or stream failure becomes one transfer result.
    suspend fun upload(request: WifiUploadRequest): Result =
        withContext(Dispatchers.IO) {
            var current: HttpURLConnection? = null
            try {
                val active = openConnection(request)
                current = active
                connection = active
                sendBody(request, active)
                readResult(active)
            } catch (error: Exception) {
                Result(
                    false,
                    "无法连接手表：${error.message ?: "连接失败"}。请确认两边在同一 Wi-Fi，且手表端 GlanceMap 已打开。",
                    null,
                )
            } finally {
                connection = null
                current?.disconnect()
            }
        }

    private fun openConnection(request: WifiUploadRequest): HttpURLConnection =
        (URL(WifiUploadProtocol.uploadUrl(request.endpoint, request.displayName)).openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = WifiTransferContract.HTTP_CONNECT_TIMEOUT_MS
                readTimeout = WifiTransferContract.HTTP_READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/gpx+xml")
                setRequestProperty("Connection", "close")
                setRequestProperty(WifiTransferContract.HEADER_TRANSFER_ID, request.transferId)
                request.sha256?.let { setRequestProperty(WifiTransferContract.HEADER_SHA256, it) }
                if (request.totalSize in 1L..Int.MAX_VALUE.toLong()) {
                    setFixedLengthStreamingMode(request.totalSize.toInt())
                }
            }

    private suspend fun sendBody(
        request: WifiUploadRequest,
        connection: HttpURLConnection,
    ) {
        connection.outputStream.use { output ->
            request.input.use { raw ->
                copyWithProgress(raw, output, request)
            }
        }
    }

    private suspend fun copyWithProgress(
        raw: InputStream,
        output: OutputStream,
        request: WifiUploadRequest,
    ) {
        val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
        var sent = 0L
        while (true) {
            request.awaitIfPaused()
            val read = raw.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            sent += read
            request.onProgress(progressFor(sent, request.totalSize), "Wi-Fi: $sent / ${request.totalSize}")
        }
    }

    private fun readResult(connection: HttpURLConnection): Result {
        val code = connection.responseCode
        val body =
            (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
        val response = WifiUploadProtocol.parseResponse(body)
        return if (code in 200..299 && response?.ok == true) {
            Result(true, "Wi-Fi 上传完成", response.sha256)
        } else {
            Result(false, mapError(code, response?.error), response?.sha256)
        }
    }

    private fun progressFor(
        sent: Long,
        totalSize: Long,
    ): Float =
        if (totalSize > 0L) {
            (sent.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    override fun close() {
        runCatching { connection?.disconnect() }
        connection = null
    }

    data class Result(
        val success: Boolean,
        val message: String,
        val sha256: String?,
    )

    private fun mapError(
        code: Int,
        error: String?,
    ): String =
        when {
            code == 401 -> "手表拒绝了请求：token 不匹配"
            code == 413 -> "文件太大"
            code == 409 || error == "checksum_mismatch" -> "校验失败，文件可能已损坏"
            code == 400 -> "文件名或 GPX 内容无效"
            else -> "手表返回错误：${error ?: "HTTP $code"}"
        }

    private companion object {
        const val UPLOAD_BUFFER_SIZE = 256 * 1024
    }
}
