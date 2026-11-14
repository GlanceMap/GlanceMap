package com.glancemap.glancemapwearos.core.service.wifi

import android.util.Log
import com.glancemap.glancemapwearos.data.repository.GpxRepositoryImpl
import com.glancemap.shared.transfer.WifiTransferContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class GpxWifiUploadServer(
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val gpxRepository: GpxRepositoryImpl,
    private val token: String,
    private val onSaved: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    private var acceptJob: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        port = socket.localPort
        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isActive && running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    activeSockets += client
                    launch {
                        runCatching { handleClient(client) }
                            .onFailure { Log.w(TAG, "Client handling failed", it) }
                        activeSockets -= client
                    }
                }
            }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        acceptJob?.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val request = readLine(input)?.let(GpxHttpRequest::parse)
            if (request == null) {
                respond(output, 400, errorJson("bad_request"))
                return
            }
            val headers = readHeaders(input)
            if (!GpxWifiProtocol.constantTimeEquals(request.query[WifiTransferContract.QUERY_TOKEN], token)) {
                respond(output, 401, errorJson("invalid_token"))
                return
            }
            when {
                request.method == "GET" && request.path == WifiTransferContract.PATH_INDEX ->
                    respondHtml(output, GpxWifiHtml.page(token))

                request.method == "POST" && request.path == WifiTransferContract.PATH_UPLOAD ->
                    handleUpload(input, output, headers, request)

                else -> respond(output, 404, errorJson("not_found"))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any stream or repository failure must become one HTTP error.
    private suspend fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        request: GpxHttpRequest,
    ) {
        val temp =
            try {
                File.createTempFile("gpx-upload-", ".part", cacheDir)
            } catch (error: Exception) {
                Log.w(TAG, "Could not create upload temp file", error)
                respond(output, 500, errorJson("save_failed"))
                return
            }
        try {
            val response = buildUploadResponse(input, headers, request, temp)
            respond(output, response.status, response.body)
        } catch (rejected: UploadRejected) {
            respond(output, rejected.status, errorJson(rejected.code))
        } catch (error: Exception) {
            Log.w(TAG, "Upload failed", error)
            respond(output, 500, errorJson("save_failed"))
        } finally {
            temp.delete()
        }
    }

    @Suppress("ThrowsCount") // Each validation failure maps to a distinct HTTP status.
    private suspend fun buildUploadResponse(
        input: InputStream,
        headers: Map<String, String>,
        request: GpxHttpRequest,
        temp: File,
    ): UploadResponse {
        val fileName =
            GpxWifiProtocol.sanitizeFileName(request.query[WifiTransferContract.QUERY_NAME])
                ?: throw UploadRejected(400, "invalid_name")
        val length = headers["content-length"]?.toLongOrNull()
        if (length != null && (length <= 0L || length > WifiTransferContract.MAX_UPLOAD_BYTES)) {
            throw UploadRejected(413, "too_large")
        }
        val copied =
            temp.outputStream().use { out ->
                copyBody(input, out, headers, length)
            }
        if (length != null && copied != length) throw UploadRejected(400, "incomplete_body")
        if (copied <= 0L || copied > WifiTransferContract.MAX_UPLOAD_BYTES) {
            throw UploadRejected(413, "too_large")
        }
        if (!GpxWifiProtocol.looksLikeGpx(readPrefix(temp))) {
            throw UploadRejected(400, "invalid_gpx")
        }
        val expectedSha =
            headers[WifiTransferContract.HEADER_SHA256.lowercase()]?.takeIf { it.isNotBlank() }
        val sha =
            gpxRepository.saveGpxFileAtomic(
                fileName = fileName,
                inputStream = temp.inputStream(),
                onProgress = { _ -> },
                expectedSize = temp.length(),
            ) ?: throw UploadRejected(500, "save_failed")
        if (expectedSha != null && !expectedSha.equals(sha, ignoreCase = true)) {
            gpxRepository.deleteGpxFile(gpxRepository.absolutePathForFileName(fileName))
            throw UploadRejected(409, "checksum_mismatch")
        }
        onSaved(fileName)
        return UploadResponse(
            status = 200,
            body =
                JSONObject()
                    .put("ok", true)
                    .put("name", fileName)
                    .put("sha256", sha)
                    .put("size", temp.length())
                    .toString(),
        )
    }

    private class UploadRejected(
        val status: Int,
        val code: String,
    ) : Exception()

    private data class UploadResponse(
        val status: Int,
        val body: String,
    )

    private companion object {
        const val TAG = "GpxWifiUploadServer"
    }
}

private fun errorJson(code: String): String =
    JSONObject()
        .put("ok", false)
        .put("error", code)
        .toString()

private fun respond(
    output: OutputStream,
    status: Int,
    body: String,
    contentType: String = "application/json; charset=utf-8",
) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val header =
        "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
    output.write(header.toByteArray(Charsets.US_ASCII))
    output.write(bytes)
    output.flush()
}

private fun respondHtml(
    output: OutputStream,
    html: String,
) = respond(output, 200, html, "text/html; charset=utf-8")

private fun statusText(status: Int): String =
    when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        else -> "Internal Server Error"
    }
