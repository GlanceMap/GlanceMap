package com.glancemap.glancemapwearos.core.service.wifi

import com.glancemap.shared.transfer.WifiTransferContract
import java.security.MessageDigest
import java.security.SecureRandom

object GpxWifiProtocol {
    private const val MAX_FILE_NAME_LENGTH = 128

    fun generateToken(random: SecureRandom): String =
        (0 until WifiTransferContract.TOKEN_DIGITS)
            .map { random.nextInt(10) }
            .joinToString("")

    fun sanitizeFileName(raw: String?): String? {
        val base =
            raw
                .orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
        val safe =
            base
                .takeIf {
                    it.isNotBlank() &&
                        it != "." &&
                        it != ".." &&
                        it.endsWith(".gpx", ignoreCase = true)
                }?.map { ch -> if (ch.isLetterOrDigit() || ch in "._- ()") ch else '_' }
                ?.joinToString("")
                ?.take(MAX_FILE_NAME_LENGTH)
        return safe?.takeIf { it.endsWith(".gpx", ignoreCase = true) && it.length > 4 }
    }

    fun looksLikeGpx(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("").lowercase()
        return "<gpx" in text || "<trk" in text
    }

    fun uploadPageUrl(
        host: String,
        port: Int,
        token: String,
    ): String {
        val safeHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$safeHost:$port/?${WifiTransferContract.QUERY_TOKEN}=$token"
    }

    fun constantTimeEquals(
        a: String?,
        b: String?,
    ): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
