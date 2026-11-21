package com.glancemap.glancemapcompanionapp.transfer.wifi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WifiUploadClientTest {
    @Test
    fun streamsBodyAndParsesResponse() =
        runTest {
            val received = AtomicReference<String>()
            val server = ServerSocket(0).apply { soTimeout = 5_000 }
            thread(isDaemon = true, name = "wifi-upload-test") {
                server.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    readLine(input)
                    val headers = readHeaders(input)
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        if (read < 0) break
                        offset += read
                    }
                    received.set(body.toString(Charsets.UTF_8))
                    val response = """{"ok":true,"sha256":"abc"}""".toByteArray()
                    val output = socket.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${response.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(),
                    )
                    output.write(response)
                    output.flush()
                }
            }
            try {
                val endpoint = WifiWatchEndpoint("127.0.0.1", server.localPort, "123456")
                val client = WifiUploadClient()
                val result =
                    client.upload(
                        WifiUploadRequest(
                            endpoint = endpoint,
                            displayName = "route.gpx",
                            input = "hello-gpx".byteInputStream(),
                            totalSize = 9,
                            sha256 = "abc",
                            transferId = "t1",
                            awaitIfPaused = {},
                            onProgress = { _, _ -> },
                        ),
                    )
                assertEquals("hello-gpx", received.get())
                assertTrue(result.success)
                client.close()
            } finally {
                server.close()
            }
        }

    private fun readLine(input: InputStream): String {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(value.toChar())
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        return headers
    }
}
