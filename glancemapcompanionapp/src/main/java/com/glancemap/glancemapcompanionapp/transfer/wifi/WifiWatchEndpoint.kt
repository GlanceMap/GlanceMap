package com.glancemap.glancemapcompanionapp.transfer.wifi

import java.net.URI

data class WifiWatchEndpoint(
    val host: String,
    val port: Int,
    val token: String,
) {
    fun encode(): String {
        val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return "wifi://$authority?t=$token"
    }

    fun uploadUrl(): String {
        val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return "http://$authority/upload?t=$token"
    }

    companion object {
        fun parse(value: String): WifiWatchEndpoint? =
            runCatching {
                val uri = URI(value)
                val host =
                    uri.host
                        ?.removePrefix("[")
                        ?.removeSuffix("]")
                        ?.takeIf { it.isNotBlank() }
                val token =
                    uri.query
                        ?.split('&')
                        ?.firstNotNullOfOrNull { pair ->
                            val key = pair.substringBefore('=', "")
                            val valuePart = pair.substringAfter('=', "")
                            if (key == "t") valuePart.takeIf { it.isNotBlank() } else null
                        }
                when {
                    uri.scheme != "wifi" -> null
                    host == null || uri.port <= 0 || token == null -> null
                    else -> WifiWatchEndpoint(host, uri.port, token)
                }
            }.getOrNull()
    }
}
