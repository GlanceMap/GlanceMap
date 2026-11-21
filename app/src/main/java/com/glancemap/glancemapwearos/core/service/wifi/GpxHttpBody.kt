package com.glancemap.glancemapwearos.core.service.wifi

import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal fun readLine(input: InputStream): String? {
    val buffer = StringBuilder()
    var result: String? = null
    var done = false
    while (!done) {
        when (val value = input.read()) {
            -1 -> {
                result = buffer.takeIf { it.isNotEmpty() }?.toString()
                done = true
            }

            '\n'.code -> {
                result = buffer.toString().trimEnd('\r')
                done = true
            }

            else -> {
                if (buffer.length > MAX_HEADER_LINE_LENGTH) {
                    done = true
                } else {
                    buffer.append(value.toChar())
                }
            }
        }
    }
    return result
}

internal fun readHeaders(input: InputStream): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    var line = readLine(input)
    while (line != null && line.isNotBlank()) {
        val separator = line.indexOf(':')
        if (separator > 0) {
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        line = readLine(input)
    }
    return headers
}

internal fun readPrefix(file: File): ByteArray {
    val buffer = ByteArray(PREFIX_BUFFER_SIZE)
    val length = file.inputStream().use { it.read(buffer) }.coerceAtLeast(0)
    return buffer.copyOf(length)
}

internal fun copyBody(
    input: InputStream,
    output: OutputStream,
    headers: Map<String, String>,
    length: Long?,
): Long =
    when {
        length != null -> copyFixedLength(input, output, length)
        headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
            copyChunked(input, output)

        else -> 0L
    }

private fun copyFixedLength(
    input: InputStream,
    output: OutputStream,
    length: Long,
): Long {
    var remaining = length
    var total = 0L
    val buffer = ByteArray(BODY_BUFFER_SIZE)
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
        total += read
    }
    return total
}

private fun copyChunked(
    input: InputStream,
    output: OutputStream,
): Long {
    var total = 0L
    var size = readChunkSize(input)
    while (size != null && size > 0L) {
        total += copyExactly(input, output, size)
        readLine(input)
        size = readChunkSize(input)
    }
    if (size == 0L) readLine(input)
    return total
}

private fun readChunkSize(input: InputStream): Long? = readLine(input)?.substringBefore(';')?.trim()?.toLongOrNull(16)

private fun copyExactly(
    input: InputStream,
    output: OutputStream,
    length: Long,
): Long {
    var remaining = length
    var total = 0L
    val buffer = ByteArray(BODY_BUFFER_SIZE)
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
        total += read
    }
    return total
}

private const val MAX_HEADER_LINE_LENGTH = 8192
private const val PREFIX_BUFFER_SIZE = 8192
private const val BODY_BUFFER_SIZE = 64 * 1024
