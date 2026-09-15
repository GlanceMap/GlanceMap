package com.glancemap.glancemapwearos.core.service.transfer.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import java.io.InputStream

internal interface TransferRuntimeHost {
    val context: Context
    val appScope: CoroutineScope

    fun onTransferStarted()

    fun onTransferFinished()

    @Suppress("LongParameterList")
    suspend fun saveFile(
        fileName: String,
        inputStream: InputStream,
        expectedSize: Long? = null,
        resumeOffset: Long = 0L,
        keepPartialOnFailure: Boolean = false,
        computeSha256: Boolean = true,
        diagnosticContext: String? = null,
        onProgress: (Long) -> Unit,
    ): String?

    fun getPartialSize(fileName: String): Long

    fun deletePartial(fileName: String): Boolean

    fun truncatePartial(
        fileName: String,
        expectedSize: Long,
    ): Boolean

    fun computePartialFileSha256(fileName: String): String?

    suspend fun promotePartialToFinal(fileName: String): Boolean

    suspend fun deleteByName(fileName: String)

    fun computeFinalFileSha256(
        fileName: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): String?

    fun acquireWakeLock(
        tag: String,
        timeoutMs: Long,
    ): PowerManager.WakeLock

    fun releaseWakeLock(wakeLock: PowerManager.WakeLock)

    fun holdPrewarmWakeLock(
        reason: String,
        timeoutMs: Long,
    )

    fun releasePrewarmWakeLock(reason: String)

    fun acquireWifiLock(tag: String): WifiManager.WifiLock

    fun releaseWifiLock(wifiLock: WifiManager.WifiLock)
}
