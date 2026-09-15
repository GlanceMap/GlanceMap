package com.glancemap.glancemapcompanionapp.transfer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.glancemap.glancemapcompanionapp.isGpxMimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

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

    private const val SHA_CACHE_MAX_ENTRIES = 32
    private const val HASH_UPDATE_INTERVAL_MS = 500L
    private const val HASH_UPDATE_STEP_BYTES = 8L * 1024L * 1024L

    private val shaCacheLock = Any()
    private val sha256Cache =
        object : LinkedHashMap<String, String>(SHA_CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > SHA_CACHE_MAX_ENTRIES
        }

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
        return resolveTransferDisplayName(rawName, context.contentResolver.getType(uri)) to size
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

    suspend fun computeSha256(
        context: Context,
        uri: Uri,
        knownSize: Long? = null,
        awaitIfPaused: (suspend () -> Unit)? = null,
        onProgress: ((Float, String) -> Unit)? = null,
    ): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val coroutineContext = currentCoroutineContext()
            val totalBytes = knownSize?.takeIf { it > 0L }
            var copied = 0L
            var lastUiBytes = 0L
            var lastUiMs = 0L

            onProgress?.invoke(0f, formatHashProgress(copied, totalBytes))

            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    awaitIfPaused?.invoke()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read <= 0) continue

                    digest.update(buffer, 0, read)
                    copied += read

                    val now = SystemClock.elapsedRealtime()
                    val timeOk = (now - lastUiMs) >= HASH_UPDATE_INTERVAL_MS
                    val bytesOk = (copied - lastUiBytes) >= HASH_UPDATE_STEP_BYTES
                    val done = totalBytes != null && copied >= totalBytes
                    if (timeOk || bytesOk || done) {
                        lastUiMs = now
                        lastUiBytes = copied
                        val progress =
                            if (totalBytes != null) {
                                (copied.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
                            } else {
                                0f
                            }
                        onProgress?.invoke(progress, formatHashProgress(copied, totalBytes))
                    }
                }
            } ?: return null

            onProgress?.invoke(1f, formatHashProgress(copied, totalBytes))
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }.getOrNull()
    }

    suspend fun computeSha256Cached(
        context: Context,
        uri: Uri,
        knownDisplayName: String? = null,
        knownSize: Long? = null,
        awaitIfPaused: (suspend () -> Unit)? = null,
        onProgress: ((Float, String) -> Unit)? = null,
    ): String? {
        val metadata = queryShaCacheMetadata(context, uri)
        val effectiveSize = knownSize ?: metadata.size
        val cacheKey =
            buildShaCacheKey(
                uri = uri,
                displayName = knownDisplayName ?: metadata.displayName,
                size = effectiveSize,
                lastModified = metadata.lastModified,
            )

        synchronized(shaCacheLock) {
            val cached = sha256Cache[cacheKey]
            if (cached != null) {
                onProgress?.invoke(1f, "Checksum ready (cached)")
                return cached
            }
        }

        onProgress?.invoke(0f, "Computing checksum…")
        val computed =
            computeSha256(
                context = context,
                uri = uri,
                knownSize = effectiveSize,
                awaitIfPaused = awaitIfPaused,
                onProgress = onProgress,
            ) ?: return null
        synchronized(shaCacheLock) {
            sha256Cache[cacheKey] = computed
        }
        return computed
    }

    /**
     * High-performance stream copy with progress + speed.
     * Safe for very large files (streaming).
     *
     * ✅ Adds a stall watchdog that *closes the output stream* to break blocked writes.
     */
    suspend fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        bufferBytes: Int,
        awaitIfPaused: (suspend () -> Unit)? = null,
        onProgress: (Float, String) -> Unit,
    ): Long =
        coroutineScope {
            val buffer = ByteArray(bufferBytes)

            val copied = AtomicLong(0L)
            val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime())

            var lastBytesForSpeed = 0L
            var lastTimeForSpeed = System.currentTimeMillis()

            val watchdog =
                launch {
                    while (isActive) {
                        delay(1000L)
                        val now = SystemClock.elapsedRealtime()
                        val since = now - lastProgressAt.get()
                        if (since > STALL_TIMEOUT_MS) {
                            runCatching { output.close() } // breaks blocked write
                            this@coroutineScope.cancel(CancellationException("Transfer stalled for ${STALL_TIMEOUT_MS}ms"))
                        }
                    }
                }

            try {
                while (true) {
                    coroutineContext.ensureActive()
                    awaitIfPaused?.invoke()

                    val read = input.read(buffer)
                    if (read < 0) break

                    output.write(buffer, 0, read)

                    val newTotal = copied.addAndGet(read.toLong())
                    lastProgressAt.set(SystemClock.elapsedRealtime())

                    val nowMs = System.currentTimeMillis()
                    val timeDelta = nowMs - lastTimeForSpeed
                    val bytesDelta = newTotal - lastBytesForSpeed

                    if (timeDelta >= UPDATE_INTERVAL_MS ||
                        bytesDelta >= UPDATE_INTERVAL_BYTES ||
                        (totalBytes > 0 && newTotal >= totalBytes)
                    ) {
                        val speedMiBps =
                            if (timeDelta >= SPEED_WARMUP_MIN_MS) {
                                val speedBytesPerSec = (bytesDelta * 1000L) / timeDelta
                                speedBytesPerSec.toDouble() / (1024.0 * 1024.0)
                            } else {
                                null
                            }

                        val progress =
                            if (totalBytes > 0) {
                                (newTotal.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
                            } else {
                                0.0
                            }

                        val text = buildProgressText(newTotal, totalBytes, speedMiBps)

                        onProgress(progress.toFloat(), text)
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

    private fun formatBytes(bytes: Long): String {
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

    private data class ShaCacheMetadata(
        val displayName: String?,
        val size: Long?,
        val lastModified: Long?,
    )

    private fun queryShaCacheMetadata(
        context: Context,
        uri: Uri,
    ): ShaCacheMetadata {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use ShaCacheMetadata(displayName = null, size = null, lastModified = null)
                }

                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                val displayName =
                    if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }

                val size =
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        null
                    }

                val lastModified =
                    if (modifiedIndex != -1 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        null
                    }

                ShaCacheMetadata(displayName = displayName, size = size, lastModified = lastModified)
            } ?: ShaCacheMetadata(displayName = null, size = null, lastModified = null)
        }.getOrElse { ShaCacheMetadata(displayName = null, size = null, lastModified = null) }
    }

    private fun buildShaCacheKey(
        uri: Uri,
        displayName: String?,
        size: Long?,
        lastModified: Long?,
    ): String =
        buildString {
            append(uri.toString())
            append('|')
            append(displayName ?: "")
            append('|')
            append(size ?: -1L)
            append('|')
            append(lastModified ?: -1L)
        }

    private fun formatHashProgress(
        copied: Long,
        total: Long?,
    ): String =
        if (total != null && total > 0L) {
            "Checksum: ${formatBytes(copied)} / ${formatBytes(total)}"
        } else {
            "Checksum: ${formatBytes(copied)}"
        }
}
