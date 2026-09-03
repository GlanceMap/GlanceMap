package com.glancemap.glancemapcompanionapp.diagnostics

object PhoneDownloadDiagnostics {
    private const val TAG = "PhoneDownload"

    fun log(
        component: String,
        message: String,
    ) {
        PhoneDebugCapture.log(TAG, "[$component] $message")
    }

    fun warn(
        component: String,
        message: String,
    ) {
        log(component, "WARN $message")
    }

    fun error(
        component: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val detail =
            throwable?.let { error ->
                buildString {
                    append(" exception=${error::class.java.simpleName}")
                    error.message
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append(" error=$it") }
                }
            }.orEmpty()
        log(component, "ERROR $message$detail")
    }
}
