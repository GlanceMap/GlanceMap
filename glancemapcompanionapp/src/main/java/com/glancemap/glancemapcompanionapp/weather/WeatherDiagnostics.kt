package com.glancemap.glancemapcompanionapp.weather

import com.google.gson.JsonParseException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A redacted failure class for the opt-in local diagnostic report; it never records hostnames or text. */
internal fun Throwable.weatherDiagnosticReason(): String =
    generateSequence(this) { error -> error.cause }
        .take(MAX_CAUSE_DEPTH)
        .mapNotNull { error -> error.weatherDiagnosticReasonOrNull() }
        .firstOrNull()
        ?: "unexpected"

private fun Throwable.weatherDiagnosticReasonOrNull(): String? =
    when (this) {
        is UnknownHostException -> "dns"
        is SocketTimeoutException -> "timeout"
        is SSLException -> "tls"
        is JsonParseException -> "response"
        else -> null
    }

private const val MAX_CAUSE_DEPTH = 5
