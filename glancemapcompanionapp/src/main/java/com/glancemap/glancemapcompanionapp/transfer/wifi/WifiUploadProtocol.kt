package com.glancemap.glancemapcompanionapp.transfer.wifi

import com.google.gson.JsonParser
import java.net.URLEncoder

object WifiUploadProtocol {
    data class Response(
        val ok: Boolean,
        val error: String?,
        val sha256: String?,
    )

    fun uploadUrl(
        endpoint: WifiWatchEndpoint,
        displayName: String,
    ): String {
        val encodedName = URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")
        return endpoint.uploadUrl() + "&name=" + encodedName
    }

    fun parseResponse(body: String): Response? =
        runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            Response(
                ok = json.get("ok")?.asBoolean == true,
                error = json.get("error")?.asString?.takeIf { it.isNotBlank() },
                sha256 = json.get("sha256")?.asString?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
}
