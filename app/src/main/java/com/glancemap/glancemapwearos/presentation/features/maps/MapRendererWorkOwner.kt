package com.glancemap.glancemapwearos.presentation.features.maps

import com.glancemap.glancemapwearos.core.maps.DemSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal data class MapRendererConfiguration(
    val elevationLabelsMetric: Boolean,
    val mapLabelTextScale: Float,
    val themeFile: File?,
    val mapsforgeThemeName: String?,
    val bundledThemeId: String,
    val hillShadingEnabled: Boolean,
    val reliefOverlayEnabled: Boolean,
    val demSource: DemSource,
)

/** Serialize renderer state on its caller; IO resources cannot outlive an abandoned preparation. */
internal class MapRendererWorkOwner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    @Volatile private var destroyed = false

    suspend fun <T> run(block: suspend () -> T): T? =
        mutex.withLock {
            if (destroyed) return@withLock null
            try {
                val result = block()
                checkAlive()
                result
            } catch (_: RendererDisposedException) {
                // Activity destruction must not cancel the settings collector using this renderer.
                null
            }
        }

    suspend fun <T> read(block: () -> T): T {
        val value =
            withContext(ioDispatcher) {
                checkAlive()
                block()
            }
        checkAlive()
        return value
    }

    suspend fun <T : Any> prepare(
        release: (T) -> Unit,
        block: () -> T,
    ): T {
        var prepared: T? = null
        try {
            val value =
                withContext(ioDispatcher) {
                    checkAlive()
                    block().also { prepared = it }
                }
            checkAlive()
            return value
        } catch (cancelled: CancellationException) {
            prepared?.let { resource ->
                withContext(NonCancellable + ioDispatcher) {
                    runCatching { release(resource) }.exceptionOrNull()?.let(cancelled::addSuppressed)
                }
            }
            throw cancelled
        }
    }

    suspend fun release(block: () -> Unit) {
        withContext(NonCancellable + ioDispatcher) { block() }
    }

    fun destroy() {
        destroyed = true
    }

    private fun checkAlive() {
        if (destroyed) throw RendererDisposedException()
    }

    private class RendererDisposedException : CancellationException("Renderer destroyed")
}
