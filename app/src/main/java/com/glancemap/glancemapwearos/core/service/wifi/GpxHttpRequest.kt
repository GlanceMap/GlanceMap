package com.glancemap.glancemapwearos.core.service.wifi

import java.net.URLDecoder

data class GpxHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
) {
    companion object {
        fun parse(line: String): GpxHttpRequest? {
            val parts = line.trim().split(' ')
            if (parts.size < 2) return null
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query =
                target
                    .substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotBlank() }
                    .mapNotNull { pair ->
                        val key = pair.substringBefore('=', "")
                        val value = pair.substringAfter('=', "")
                        if (key.isBlank()) {
                            null
                        } else {
                            key to URLDecoder.decode(value, "UTF-8")
                        }
                    }.toMap()
            return GpxHttpRequest(method, path, query)
        }
    }
}
