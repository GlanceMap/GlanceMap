package com.glancemap.glancemapcompanionapp.transfer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import com.glancemap.glancemapcompanionapp.isGpxMimeType
import com.glancemap.shared.transfer.MapsforgeThemePackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class CopyWithProgressOptions(
    val totalBytes: Long,
    val bufferBytes: Int,
    val awaitIfPaused: (suspend () -> Unit)? = null,
    val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    val currentTimeMs: () -> Long = System::currentTimeMillis,
)

internal data class Sha256Options(
    val knownSize: Long? = null,
    val awaitIfPaused: (suspend () -> Unit)? = null,
    val onProgress: ((Float, String) -> Unit)? = null,
    val onBytesHashed: (Long) -> Unit = {},
    val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    val diagnosticContext: String? = null,
)

object TransferUtils {
    const val TAG = "TransferUtils"

    internal data class LocalIpCandidate(
        val ifName: String,
        val ip: String,
        val source: String,
        val score: Int,
    )

    // ✅ Increased buffer sizes for better throughput
    const val WIFI_BUFFER_BYTES = 2 * 1024 * 1024 // 2MB
    const val CHANNEL_BUFFER_BYTES = 64 * 1024 // 64KB

    // Progress throttling
    private const val UPDATE_INTERVAL_MS = 1000L
    private const val UPDATE_INTERVAL_BYTES = 2_097_152L // 2MB
    private const val SPEED_WARMUP_MIN_MS = 1000L

    // ✅ NEW: Detect "stuck" transfers (blocked socket write / stalled network)
    // If no forward progress for this duration -> close output to break the write, then cancel.
    private const val STALL_TIMEOUT_MS = 30_000L // 30s

    fun getFileDetails(
        context: Context,
        uri: Uri,
    ): Pair<String, Long>? {
        val queried =
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex == -1) return@use null
                val size =
                    if (sizeIndex == -1 || cursor.isNull(sizeIndex)) {
                        -1L
                    } else {
                        cursor.getLong(sizeIndex)
                    }
                cursor.getString(nameIndex).orEmpty() to size
            }
        if (queried != null) return queried

        val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        if (scheme == "file" || scheme.isBlank()) {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists() && file.isFile) {
                return file.name to file.length()
            }
        }
        return null
    }

    fun getTransferFileDetails(
        context: Context,
        uri: Uri,
    ): Pair<String, Long>? {
        val (rawName, size) = getFileDetails(context, uri) ?: return null
        val name = resolveTransferDisplayName(rawName, context.contentResolver.getType(uri))
        val transferName =
            if (MapsforgeThemePackage.isThemeZipCandidate(name)) {
                check(size <= MapsforgeThemePackage.MAX_ARCHIVE_BYTES) { "Theme package is too large" }
                val valid =
                    context.contentResolver
                        .openInputStream(uri)
                        ?.use { input -> MapsforgeThemePackage.probe(input) } == true
                check(valid) { "Not a valid Mapsforge theme package" }
                Log.i(TAG, "theme_import_requested")
                MapsforgeThemePackage.transferName(name)
            } else {
                name
            }
        return transferName to size
    }

    internal fun resolveTransferDisplayName(
        rawName: String,
        mimeType: String?,
    ): String {
        val name = rawName.trim()
        val lowerName = name.lowercase(Locale.ROOT)
        if (isGpxMimeType(mimeType) && !lowerName.endsWith(".gpx")) {
            return "${name.ifBlank { "shared-route" }}.gpx"
        }
        return name
    }

    /**
     * High-performance stream copy with progress + speed.
     * Safe for very large files (streaming).
     *
     * ✅ Adds a stall watchdog that *closes the output stream* to break blocked writes.
     * Keep the stream loop together so watchdog, cancellation, and progress baselines stay
     * auditable in their current order.
     */
    @Suppress("LongMethod")
    internal suspend fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        options: CopyWithProgressOptions,
        onProgress: (Float, String) -> Unit,
    ): Long =
        coroutineScope {
            val buffer = ByteArray(options.bufferBytes)

            val copied = AtomicLong(0L)
            val lastProgressAt = AtomicLong(options.elapsedRealtimeMs())

            var lastProgressBytes = 0L
            var lastProgressAtMs = options.currentTimeMs()
            var lastBytesForSpeed = 0L
            var lastTimeForSpeed = lastProgressAtMs

            val watchdog =
                launch {
                    while (isActive) {
                        delay(1000L)
                        val now = options.elapsedRealtimeMs()
                        val since = now - lastProgressAt.get()
                        if (since > STALL_TIMEOUT_MS) {
                            runCatching { output.close() } // breaks blocked write
                            this@coroutineScope.cancel(
                                CancellationException("Transfer stalled for ${STALL_TIMEOUT_MS}ms"),
                            )
                        }
                    }
                }

            try {
                while (true) {
                    coroutineContext.ensureActive()
                    options.awaitIfPaused?.invoke()

                    val read = input.read(buffer)
                    if (shouldStopCopyAfterRead(options.totalBytes, copied.get(), read)) break

                    output.write(buffer, 0, read)

                    val newTotal = copied.addAndGet(read.toLong())
                    lastProgressAt.set(options.elapsedRealtimeMs())

                    val nowMs = options.currentTimeMs()
                    val progressTimeDelta = nowMs - lastProgressAtMs
                    val progressBytesDelta = newTotal - lastProgressBytes

                    val progressThresholdReached =
                        progressTimeDelta >= UPDATE_INTERVAL_MS ||
                            progressBytesDelta >= UPDATE_INTERVAL_BYTES ||
                            (options.totalBytes > 0 && newTotal >= options.totalBytes)
                    if (progressThresholdReached) {
                        val speedTimeDelta = nowMs - lastTimeForSpeed
                        val speedBytesDelta = newTotal - lastBytesForSpeed
                        val speedMiBps =
                            if (speedTimeDelta >= SPEED_WARMUP_MIN_MS) {
                                val speedBytesPerSec = (speedBytesDelta * 1000L) / speedTimeDelta
                                speedBytesPerSec.toDouble() / (1024.0 * 1024.0)
                            } else {
                                null
                            }

                        val progress =
                            if (options.totalBytes > 0) {
                                (newTotal.toDouble() / options.totalBytes.toDouble()).coerceIn(0.0, 1.0)
                            } else {
                                0.0
                            }

                        val text = buildProgressText(newTotal, options.totalBytes, speedMiBps)

                        onProgress(progress.toFloat(), text)
                        lastProgressBytes = newTotal
                        lastProgressAtMs = nowMs
                        if (speedMiBps != null) {
                            lastBytesForSpeed = newTotal
                            lastTimeForSpeed = nowMs
                        }
                    }
                }

                output.flush()
                copied.get()
            } finally {
                watchdog.cancel()
            }
        }

    private fun shouldStopCopyAfterRead(
        expectedBytes: Long,
        bytesCopied: Long,
        bytesRead: Int,
    ): Boolean {
        if (bytesRead < 0) {
            if (expectedBytes >= 0L && bytesCopied != expectedBytes) {
                throw IOException("unexpected_eof expectedBytes=$expectedBytes bytesSent=$bytesCopied")
            }
            return true
        }
        if (expectedBytes >= 0L && bytesRead.toLong() > expectedBytes - bytesCopied) {
            throw IOException("unexpected_extra_data expectedBytes=$expectedBytes bytesSent=$bytesCopied")
        }
        return false
    }

    internal fun formatBytes(bytes: Long): String {
        val b = max(bytes, 0L).toDouble()
        val kib = 1024.0
        val mib = kib * 1024.0
        val gib = mib * 1024.0
        return when {
            b >= gib -> String.format(Locale.US, "%.2f GiB", b / gib)
            b >= mib -> String.format(Locale.US, "%.2f MiB", b / mib)
            b >= kib -> String.format(Locale.US, "%.0f KiB", b / kib)
            else -> "$bytes B"
        }
    }

    private fun buildProgressText(
        copiedBytes: Long,
        totalBytes: Long,
        speedMiBps: Double?,
    ): String {
        val base =
            if (totalBytes > 0) {
                "${formatBytes(copiedBytes)} / ${formatBytes(totalBytes)}"
            } else {
                formatBytes(copiedBytes)
            }
        val speedSuffix =
            speedMiBps
                ?.let {
                    " (${String.format(Locale.US, "%.2f", it)} MiB/s)"
                }.orEmpty()
        return base + speedSuffix
    }

    /**
     * Returns an IPv4 address reachable by the watch on:
     * - same Wi-Fi network, OR
     * - watch connected to this phone hotspot.
     */
    fun getWifiIpAddress(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        if (cm != null) {
            runCatching {
                val active = cm.activeNetwork ?: return@runCatching
                val caps = cm.getNetworkCapabilities(active) ?: return@runCatching
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@runCatching

                val props = cm.getLinkProperties(active) ?: return@runCatching
                buildLocalIpCandidate(
                    ifName = props.interfaceName.orEmpty(),
                    ip =
                        props.linkAddresses
                            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                            ?.address
                            ?.hostAddress,
                    source = "active",
                )?.let {
                    Log.d(TAG, "Resolved Wi-Fi IP from active network candidate=$it")
                    return it.ip
                }
            }

            @Suppress("DEPRECATION")
            runCatching {
                val connectivityCandidates = mutableListOf<LocalIpCandidate>()
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

                    val props = cm.getLinkProperties(net) ?: continue
                    buildLocalIpCandidate(
                        ifName = props.interfaceName.orEmpty(),
                        ip =
                            props.linkAddresses
                                .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                                ?.address
                                ?.hostAddress,
                        source = "allNetworks",
                    )?.let(connectivityCandidates::add)
                }
                selectBestLocalIpCandidate(connectivityCandidates)?.let {
                    Log.d(
                        TAG,
                        "Resolved Wi-Fi IP from ConnectivityManager candidates=$connectivityCandidates best=$it",
                    )
                    return it.ip
                }
            }.onFailure { e ->
                Log.e(TAG, "Error resolving Wi-Fi IP via allNetworks fallback", e)
            }
        }

        return try {
            val candidates = mutableListOf<LocalIpCandidate>()

            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val ifName = iface.name ?: continue
                if (!isLocalPeerInterfaceName(ifName)) continue

                val addrs = iface.inetAddresses ?: continue
                for (addr in addrs) {
                    val ipv4 = addr as? Inet4Address ?: continue
                    if (ipv4.isLoopbackAddress) continue

                    val ip = ipv4.hostAddress ?: continue
                    if (!isPrivateIpv4(ip)) continue

                    buildLocalIpCandidate(ifName = ifName, ip = ip, source = "interfaces")
                        ?.let(candidates::add)
                }
            }

            if (candidates.isEmpty()) return null

            val best = selectBestLocalIpCandidate(candidates)
            Log.d(TAG, "Resolved local IP candidates=$candidates best=$best")
            best?.ip
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving hotspot IP via NetworkInterface", e)
            null
        }
    }

    internal fun buildLocalIpCandidate(
        ifName: String,
        ip: String?,
        source: String,
    ): LocalIpCandidate? {
        val normalizedIp = ip?.trim().orEmpty()
        if (normalizedIp.isBlank() || !isPrivateIpv4(normalizedIp)) return null
        return LocalIpCandidate(
            ifName = ifName.ifBlank { "unknown" },
            ip = normalizedIp,
            source = source,
            score = scoreLocalIpCandidate(ifName, normalizedIp),
        )
    }

    internal fun selectBestLocalIpCandidate(candidates: List<LocalIpCandidate>): LocalIpCandidate? =
        candidates.maxWithOrNull(
            compareBy<LocalIpCandidate> { it.score }
                .thenByDescending { isWifiInterfaceName(it.ifName) }
                .thenByDescending { !isHotspotInterfaceName(it.ifName) }
                .thenByDescending { !it.ip.endsWith(".1") },
        )

    internal fun scoreLocalIpCandidate(
        ifName: String,
        ip: String,
    ): Int {
        val normalizedName = ifName.lowercase(Locale.ROOT)
        var score = 0

        if (isWifiInterfaceName(normalizedName)) score += 120
        if (isHotspotInterfaceName(normalizedName)) score += 45
        if (
            normalizedName.contains("rndis") ||
            normalizedName.contains("usb") ||
            normalizedName.contains("eth")
        ) {
            score += 20
        }
        if (ip.endsWith(".1")) score -= 15

        score +=
            when {
                ip.startsWith("192.168.") -> 10
                ip.startsWith("10.") -> 8
                ip.startsWith("172.") -> 6
                else -> 0
            }
        return score
    }

    private fun isWifiInterfaceName(ifName: String): Boolean {
        val normalized = ifName.lowercase(Locale.ROOT)
        return normalized.contains("wlan") || normalized.contains("swlan") || normalized.contains("wifi")
    }

    private fun isHotspotInterfaceName(ifName: String): Boolean {
        val normalized = ifName.lowercase(Locale.ROOT)
        return normalized.contains("ap") || normalized.contains("softap")
    }

    internal fun isLocalPeerInterfaceName(ifName: String): Boolean {
        val normalized = ifName.lowercase(Locale.ROOT)
        return isWifiInterfaceName(normalized) ||
            isHotspotInterfaceName(normalized) ||
            normalized.contains("rndis") ||
            normalized.contains("usb") ||
            normalized.contains("eth")
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val parts = ip.split(".")
            val second = parts.getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }
}
