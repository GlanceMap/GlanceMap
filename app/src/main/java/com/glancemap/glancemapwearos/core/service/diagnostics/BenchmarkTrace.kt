package com.glancemap.glancemapwearos.core.service.diagnostics

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

internal object BenchmarkTrace {
    private const val MAX_SECTION_NAME_LENGTH = 127
    private val nextAsyncCookie = AtomicInteger(0)

    class AsyncMarker internal constructor(
        internal val sectionName: String,
        internal val cookie: Int,
    )

    fun mark(sectionName: String) {
        begin(sectionName)
        end()
    }

    fun begin(sectionName: String) {
        Trace.beginSection(sectionName.safeTraceName())
    }

    fun end() {
        Trace.endSection()
    }

    fun beginAsync(sectionName: String): AsyncMarker {
        val safeName = sectionName.safeTraceName()
        val cookie = nextAsyncCookie.updateAndGet { current -> if (current == Int.MAX_VALUE) 1 else current + 1 }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection(safeName, cookie)
        }
        return AsyncMarker(sectionName = safeName, cookie = cookie)
    }

    fun endAsync(marker: AsyncMarker) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection(marker.sectionName, marker.cookie)
        }
    }

    inline fun <T> section(
        sectionName: String,
        block: () -> T,
    ): T {
        begin(sectionName)
        return try {
            block()
        } finally {
            end()
        }
    }

    private fun String.safeTraceName(): String =
        if (length <= MAX_SECTION_NAME_LENGTH) {
            this
        } else {
            take(MAX_SECTION_NAME_LENGTH)
        }
}
