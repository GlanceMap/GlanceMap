package com.glancemap.glancemapcompanionapp.transfer.service.internal

import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferResult
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal class AckRegistry {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TransferResult>>()
    private val registeredAtMs = ConcurrentHashMap<String, Long>()

    fun register(id: String): CompletableDeferred<TransferResult> {
        val d = CompletableDeferred<TransferResult>()
        pending[id] = d
        registeredAtMs[id] = SystemClock.elapsedRealtime()
        return d
    }

    fun remove(id: String) {
        pending.remove(id)?.cancel()
        registeredAtMs.remove(id)
    }

    fun complete(
        id: String,
        result: TransferResult,
    ): Boolean {
        val deferred = pending.remove(id) ?: return false
        registeredAtMs.remove(id)
        if (!deferred.isCompleted) deferred.complete(result)
        return true
    }

    fun completeAll(result: TransferResult) {
        pending.values.forEach { d ->
            if (!d.isCompleted) d.complete(result)
        }
        pending.clear()
        registeredAtMs.clear()
    }

    fun handleAck(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val id = json.getString("id")
            val status = json.optString("status", "UNKNOWN")
            val detail = json.optString("detail", "")

            if (status == "DONE" || status == "ERROR") {
                val result = transferResultForAck(status, detail) ?: return@runCatching
                val receivedAtMs = SystemClock.elapsedRealtime()
                val ackElapsedMs = registeredAtMs.remove(id)?.let { receivedAtMs - it }
                val deferred = pending.remove(id)
                PhoneTransferDiagnostics.log(
                    "Ack",
                    "event=ack_received transferId=$id status=$status " +
                        "ackElapsedMs=${ackElapsedMs ?: "na"} matched=${deferred != null}",
                )
                deferred?.complete(result)
                    ?: PhoneTransferDiagnostics.log(
                        "Ack",
                        "event=ack_ignored transferId=$id status=$status reason=not_pending",
                    )
            }
        }.onFailure {
            Log.w("AckRegistry", "Failed to parse ACK payload", it)
            PhoneTransferDiagnostics.error("Ack", "Failed to parse ACK payload", it)
        }
    }
}

internal fun transferResultForAck(
    status: String,
    detail: String,
): TransferResult? =
    when (status) {
        "DONE" -> TransferResult(true, "Transfer successful")
        "ERROR" -> TransferResult(false, detail.ifBlank { "Watch reported error" })
        else -> null
    }
