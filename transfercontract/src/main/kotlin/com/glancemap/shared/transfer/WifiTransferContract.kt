package com.glancemap.shared.transfer

object WifiTransferContract {
    const val SERVICE_TYPE = "_glancemap._tcp."
    const val TXT_VERSION = "v"
    const val TXT_NAME = "name"
    const val TXT_TOKEN = "token"
    const val PATH_INDEX = "/"
    const val PATH_UPLOAD = "/upload"
    const val QUERY_TOKEN = "t"
    const val QUERY_NAME = "name"
    const val HEADER_SHA256 = "X-GlanceMap-SHA256"
    const val HEADER_TRANSFER_ID = "X-GlanceMap-Transfer-Id"
    const val TOKEN_DIGITS = 6
    const val MAX_UPLOAD_BYTES = 64L * 1024L * 1024L
    const val HTTP_CONNECT_TIMEOUT_MS = 15_000
    const val HTTP_READ_TIMEOUT_MS = 300_000
    const val MDNS_RESOLVE_TIMEOUT_MS = 5_000L
    const val UDP_DISCOVERY_PORT = 8766
    const val UDP_DISCOVERY_REQUEST = "GLANCEMAP_DISCOVER_V1"
}
