@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapwearos.presentation.features.maps.theme

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.sqrt

internal const val DEM_NO_INTERNET_MESSAGE =
    "No internet on watch. Connect Wi-Fi or phone internet, then retry DEM download."

internal class DemInvalidTileException(
    message: String,
    @StringRes val messageRes: Int? = null,
    val messageArguments: Array<out Any> = emptyArray(),
    @PluralsRes val messagePluralRes: Int? = null,
    val messageQuantity: Int = 0,
) : IOException(message)

internal class DemResumeRejectedException(
    message: String,
) : IOException(message)

internal fun classifyDemFailureAsNetworkUnavailable(
    throwable: Throwable,
    internetAvailableNow: Boolean,
): Boolean {
    if (hasDirectDemOfflineCause(throwable)) return true
    if (hasDemTimeoutCause(throwable)) return !internetAvailableNow

    val message = throwable.message.orEmpty().lowercase(Locale.ROOT)
    if (
        message.contains("unable to resolve host") ||
        message.contains("failed to connect") ||
        message.contains("network is unreachable") ||
        message.contains("enetunreach") ||
        message.contains("ehostunreach")
    ) {
        return true
    }

    if (message.contains("timed out") || message.contains("timeout")) {
        return !internetAvailableNow
    }

    return false
}

// Preserve failure precedence; Android resource formatting requires varargs.
@Suppress("ReturnCount", "SpreadOperator")
internal fun buildDemFailureMessage(
    throwable: Throwable,
    networkUnavailable: Boolean,
    resources: Resources,
): String {
    if (networkUnavailable) {
        return resources.getString(
            R.string.maps_dem_internet_required,
        )
    }
    if (throwable is DemInvalidTileException &&
        throwable.messagePluralRes != null
    ) {
        return resources.getQuantityString(
            throwable.messagePluralRes,
            throwable.messageQuantity,
            *throwable.messageArguments,
        )
    }
    if (throwable is DemInvalidTileException &&
        throwable.messageRes != null
    ) {
        return resources.getString(throwable.messageRes, *throwable.messageArguments)
    }
    if (hasDemTimeoutCause(throwable)) {
        return resources.getString(R.string.dem_dem_download_timed_out_retry_when_the_watch_internet_connection_is_sta)
    }
    return throwable.message?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.dem_dem_download_failed)
}

internal fun isRetryableDemDownloadFailure(throwable: Throwable?): Boolean {
    if (throwable == null) return false
    if (isPermanentDemDownloadFailure(throwable)) return false
    if (throwable is DemResumeRejectedException || throwable is SocketException) return true
    if (hasDirectDemOfflineCause(throwable) || hasDemTimeoutCause(throwable)) return true

    val message = throwable.message.orEmpty().lowercase(Locale.ROOT)
    return DEM_RETRYABLE_MESSAGE_PARTS.any(message::contains)
}

internal fun validateDemTileFile(file: File) {
    if (!file.exists() || !file.isFile) {
        invalidDemTile("DEM tile was not saved.", R.string.dem_dem_tile_was_not_saved)
    }
    if (file.length() <= 0L) {
        invalidDemTile("DEM tile is empty.", R.string.dem_dem_tile_is_empty)
    }

    when {
        file.name.endsWith(".zip", ignoreCase = true) -> validateDemZip(file)
        file.name.endsWith(".gz", ignoreCase = true) -> validateDemGzip(file)
        !isPlausibleHgtByteSize(file.length()) ->
            invalidDemSize(
                "DEM tile has invalid HGT size: ${file.length()} bytes.",
                R.plurals.dem_dem_tile_has_invalid_hgt_size_bytes,
                file.length(),
            )
    }
}

private fun validateDemGzip(file: File) {
    val uncompressedSize =
        GZIPInputStream(file.inputStream().buffered()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) total += read
            }
            total
        }
    if (!isPlausibleHgtByteSize(uncompressedSize)) {
        invalidDemSize(
            "DEM GZIP contains invalid HGT size: $uncompressedSize bytes.",
            R.plurals.dem_dem_gzip_contains_invalid_hgt_size_bytes,
            uncompressedSize,
        )
    }
}

private fun validateDemZip(file: File) {
    val hgtEntries =
        ZipFile(file).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.name.endsWith(".hgt", ignoreCase = true)
                }.toList()
        }

    if (hgtEntries.isEmpty()) {
        invalidDemTile("DEM ZIP does not contain an HGT file.", R.string.dem_dem_zip_does_not_contain_an_hgt_file)
    }

    val firstInvalidSize =
        hgtEntries
            .mapNotNull { entry -> entry.size.takeIf { it > 0L } }
            .firstOrNull { size -> !isPlausibleHgtByteSize(size) }
    if (firstInvalidSize != null) {
        invalidDemSize(
            "DEM ZIP contains invalid HGT size: $firstInvalidSize bytes.",
            R.plurals.dem_dem_zip_contains_invalid_hgt_size_bytes,
            firstInvalidSize,
        )
    }
}

private fun isPermanentDemDownloadFailure(throwable: Throwable): Boolean =
    throwable is FileNotFoundException ||
        throwable is DemInvalidTileException ||
        throwable is ZipException

private fun invalidDemTile(
    message: String,
    @StringRes messageRes: Int = R.string.dem_invalid_tile,
    vararg arguments: Any,
): Nothing = throw DemInvalidTileException(message, messageRes, arguments)

private fun isPlausibleHgtByteSize(size: Long): Boolean {
    if (size <= 0L || size % Short.SIZE_BYTES != 0L) return false
    val sampleCount = size / Short.SIZE_BYTES
    val rowLen = sqrt(sampleCount.toDouble()).toInt()
    return rowLen * rowLen.toLong() == sampleCount && rowLen in 1201..3601
}

private val DEM_RETRYABLE_MESSAGE_PARTS =
    listOf(
        "http 408",
        "http 429",
        "http 500",
        "http 502",
        "http 503",
        "http 504",
        "unexpected end of stream",
        "connection reset",
        "broken pipe",
        "connection closed",
        "connection refused",
        "software caused connection abort",
        "timed out",
        "timeout",
    )

private fun hasDirectDemOfflineCause(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    while (current != null) {
        when (current) {
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            -> return true
        }
        current = current.cause
    }
    return false
}

private fun hasDemTimeoutCause(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    while (current != null) {
        if (current is SocketTimeoutException) return true
        current = current.cause
    }
    return false
}

private fun invalidDemSize(
    message: String,
    @PluralsRes messageRes: Int,
    bytes: Long,
): Nothing =
    throw DemInvalidTileException(
        message,
        messageArguments = arrayOf(bytes),
        messagePluralRes = messageRes,
        messageQuantity = bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
