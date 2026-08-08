package com.glancemap.glancemapcompanionapp.transfer

import android.net.Uri
import android.util.Log
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.datalayer.DataLayerPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone-side helper: asks the watch whether a filename already exists.
 *
 * Contract:
 * Phone -> Watch:  PATH_CHECK_EXISTS
 *   JSON: { "id": "<requestId>", "name": "<uriEncodedFileName>" }
 *
 * Watch -> Phone: PATH_CHECK_EXISTS_RESULT
 *   JSON: { "id": "<requestId>", "exists": true/false }
 */
class FileExistenceChecker(
    private val sendMessage: suspend (nodeId: String, path: String, payload: ByteArray) -> Unit,
) {
    private val pendingChecks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingBatchChecks = ConcurrentHashMap<String, CompletableDeferred<List<Boolean>>>()
    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val responsiveNodesUntilMs = ConcurrentHashMap<String, Long>()
    private val recoveringNodesUntilMs = ConcurrentHashMap<String, Long>()

    fun markNodeRecovering(
        nodeId: String,
        durationMs: Long = RECOVERING_NODE_DEFAULT_MS,
        reason: String = "unspecified",
    ) {
        if (nodeId.isBlank()) return
        val untilMs = System.currentTimeMillis() + durationMs.coerceAtLeast(1L)
        recoveringNodesUntilMs.merge(nodeId, untilMs) { old, new -> maxOf(old, new) }
        PhoneTransferDiagnostics.warn(
            "Exists",
            "Mark node recovering node=$nodeId durationMs=$durationMs reason=$reason",
        )
    }

    suspend fun awaitResponsive(
        nodeId: String,
        timeoutMs: Long,
        reason: String,
        prewarm: Boolean = true,
    ): Boolean {
        if (nodeId.isBlank()) return false
        val deadlineMs = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        var prewarmed = false

        PhoneTransferDiagnostics.warn(
            "Exists",
            "Wait for watch responsiveness node=$nodeId timeoutMs=$timeoutMs reason=$reason",
        )

        while (System.currentTimeMillis() <= deadlineMs) {
            if (prewarm && !prewarmed) {
                prewarmWatch(nodeId)
                prewarmed = true
            }

            if (ensureWatchResponsive(nodeId, force = true)) {
                recoveringNodesUntilMs.remove(nodeId)
                PhoneTransferDiagnostics.log(
                    "Exists",
                    "Watch responsive again node=$nodeId reason=$reason",
                )
                return true
            }

            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0L) break
            delay(minOf(RECOVERY_POLL_INTERVAL_MS, remainingMs))
        }

        PhoneTransferDiagnostics.warn(
            "Exists",
            "Watch still not responsive node=$nodeId timeoutMs=$timeoutMs reason=$reason",
        )
        return false
    }

    suspend fun check(
        nodeId: String,
        fileName: String,
    ): Boolean? {
        waitForRecoveryIfNeeded(nodeId, "single_check:$fileName")
        ensureWatchResponsive(nodeId)
        val firstAttempt = runSingleCheck(nodeId, fileName, CHECK_TIMEOUT_MS)
        if (firstAttempt != null) return firstAttempt

        Log.d(TAG, "Single exists check timed out for '$fileName'. Prewarming watch and retrying.")
        PhoneTransferDiagnostics.warn("Exists", "Single check timeout file=$fileName; prewarm and retry")
        prewarmAndWait(nodeId)
        return runSingleCheck(nodeId, fileName, RETRY_CHECK_TIMEOUT_MS)
    }

    suspend fun checkBatch(
        nodeId: String,
        fileNames: List<String>,
    ): List<Boolean>? {
        if (fileNames.isEmpty()) return emptyList()
        if (fileNames.size == 1) {
            return listOf(check(nodeId, fileNames.first()) ?: return null)
        }

        waitForRecoveryIfNeeded(nodeId, "batch_check:${fileNames.size}")
        ensureWatchResponsive(nodeId)
        val firstAttempt = runBatchCheck(nodeId, fileNames, BATCH_CHECK_TIMEOUT_MS)
        if (firstAttempt != null) return firstAttempt

        Log.d(TAG, "Batch exists check timed out. Prewarming watch and retrying batch check.")
        PhoneTransferDiagnostics.warn("Exists", "Batch check timeout count=${fileNames.size}; prewarm and retry")
        prewarmAndWait(nodeId)
        val secondAttempt = runBatchCheck(nodeId, fileNames, RETRY_BATCH_CHECK_TIMEOUT_MS)
        if (secondAttempt != null) return secondAttempt

        Log.d(TAG, "Retried batch exists check still failed/timed out, falling back to single checks")
        PhoneTransferDiagnostics.warn("Exists", "Batch check fallback to single checks count=${fileNames.size}")
        return fallbackToSingleChecks(nodeId, fileNames)
    }

    private suspend fun runSingleCheck(
        nodeId: String,
        fileName: String,
        timeoutMs: Long,
    ): Boolean? {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingChecks[requestId] = deferred

        val payload =
            JSONObject()
                .apply {
                    put("id", requestId)
                    put("name", Uri.encode(fileName))
                }.toString()
                .toByteArray(Charsets.UTF_8)

        return try {
            Log.d(TAG, "Checking exists on watch: '$fileName' (id=$requestId)")
            PhoneTransferDiagnostics.log("Exists", "Check file=$fileName requestId=$requestId")
            sendMessage(nodeId, DataLayerPaths.PATH_CHECK_EXISTS, payload)

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                Log.d(TAG, "Exists check timed out for '$fileName' after ${timeoutMs}ms")
                PhoneTransferDiagnostics.warn("Exists", "Check timeout file=$fileName timeoutMs=$timeoutMs")
            }
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Exists check failed to send/await for '$fileName'", e)
            PhoneTransferDiagnostics.error("Exists", "Check send/await failed file=$fileName", e)
            null
        } finally {
            pendingChecks.remove(requestId)?.cancel()
        }
    }

    private suspend fun runBatchCheck(
        nodeId: String,
        fileNames: List<String>,
        timeoutMs: Long,
    ): List<Boolean>? {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<List<Boolean>>()
        pendingBatchChecks[requestId] = deferred

        val items =
            JSONArray().apply {
                fileNames.forEachIndexed { index, fileName ->
                    put(
                        JSONObject().apply {
                            put("index", index)
                            put("name", Uri.encode(fileName))
                        },
                    )
                }
            }
        val payload =
            JSONObject()
                .apply {
                    put("id", requestId)
                    put("items", items)
                }.toString()
                .toByteArray(Charsets.UTF_8)

        return try {
            Log.d(TAG, "Checking ${fileNames.size} file(s) on watch in batch (id=$requestId)")
            PhoneTransferDiagnostics.log("Exists", "Batch check requestId=$requestId count=${fileNames.size}")
            sendMessage(nodeId, DataLayerPaths.PATH_CHECK_EXISTS_BATCH, payload)

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            when {
                result == null -> {
                    Log.d(TAG, "Batch exists check timed out after ${timeoutMs}ms (id=$requestId)")
                    PhoneTransferDiagnostics.warn("Exists", "Batch check timeout requestId=$requestId timeoutMs=$timeoutMs")
                    null
                }
                result.size != fileNames.size -> {
                    Log.w(
                        TAG,
                        "Batch exists check returned ${result.size}/${fileNames.size} items for id=$requestId; falling back",
                    )
                    PhoneTransferDiagnostics.warn(
                        "Exists",
                        "Batch check incomplete requestId=$requestId returned=${result.size}/${fileNames.size}",
                    )
                    null
                }
                else -> result
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Batch exists check failed to send/await", e)
            PhoneTransferDiagnostics.error("Exists", "Batch check send/await failed requestId=$requestId", e)
            null
        } finally {
            pendingBatchChecks.remove(requestId)?.cancel()
        }
    }

    /**
     * Call from your service MessageClient listener when PATH_CHECK_EXISTS_RESULT arrives.
     */
    fun handleExistsResult(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val requestId = json.getString("id")
            val exists = json.optBoolean("exists", false)
            pendingChecks.remove(requestId)?.complete(exists)
            PhoneTransferDiagnostics.log("Exists", "Check result requestId=$requestId exists=$exists")
        }.onFailure {
            Log.w(TAG, "Failed to parse exists result", it)
            PhoneTransferDiagnostics.error("Exists", "Failed to parse exists result", it)
        }
    }

    fun handleBatchExistsResult(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val requestId = json.getString("id")
            val expectedCount = json.optInt("count", -1)
            val items = json.optJSONArray("items") ?: JSONArray()
            val indexed = mutableMapOf<Int, Boolean>()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val index = item.optInt("index", -1)
                if (index < 0) continue
                indexed[index] = item.optBoolean("exists", false)
            }

            val size = if (expectedCount >= 0) expectedCount else indexed.keys.maxOrNull()?.plus(1) ?: 0
            if (size == 0 || indexed.size < size) {
                Log.w(TAG, "Incomplete batch exists result for id=$requestId")
                PhoneTransferDiagnostics.warn(
                    "Exists",
                    "Incomplete batch result requestId=$requestId expected=$size actual=${indexed.size}",
                )
                pendingBatchChecks.remove(requestId)?.complete(emptyList())
                return@runCatching
            }
            val ordered = MutableList(size) { false }
            indexed.forEach { (index, exists) ->
                if (index in ordered.indices) {
                    ordered[index] = exists
                }
            }
            pendingBatchChecks.remove(requestId)?.complete(ordered)
            PhoneTransferDiagnostics.log("Exists", "Batch result requestId=$requestId size=$size")
        }.onFailure {
            Log.w(TAG, "Failed to parse batch exists result", it)
            PhoneTransferDiagnostics.error("Exists", "Failed to parse batch exists result", it)
        }
    }

    fun handlePingResult(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val requestId = json.getString("id")
            val nodeId = json.optString("nodeId", "")
            pendingPings.remove(requestId)?.complete(true)
            if (nodeId.isNotBlank()) {
                responsiveNodesUntilMs[nodeId] = System.currentTimeMillis() + RESPONSIVE_CACHE_MS
            }
            PhoneTransferDiagnostics.log("Exists", "Ping reply requestId=$requestId node=$nodeId")
        }.onFailure {
            Log.w(TAG, "Failed to parse ping result", it)
            PhoneTransferDiagnostics.error("Exists", "Failed to parse ping result", it)
        }
    }

    private suspend fun fallbackToSingleChecks(
        nodeId: String,
        fileNames: List<String>,
    ): List<Boolean>? {
        ensureWatchResponsive(nodeId, force = true)
        val results = ArrayList<Boolean>(fileNames.size)
        for (fileName in fileNames) {
            val exists = check(nodeId, fileName) ?: return null
            results += exists
        }
        return results
    }

    private suspend fun prewarmAndWait(nodeId: String) {
        prewarmWatch(nodeId)
        delay(PREWARM_SETTLE_DELAY_MS)
        ensureWatchResponsive(nodeId, force = true)
    }

    private suspend fun waitForRecoveryIfNeeded(
        nodeId: String,
        reason: String,
    ) {
        val recoveryUntilMs = recoveringNodesUntilMs[nodeId] ?: 0L
        if (recoveryUntilMs <= System.currentTimeMillis()) {
            recoveringNodesUntilMs.remove(nodeId)
            return
        }

        PhoneTransferDiagnostics.warn(
            "Exists",
            "Node in recovery window node=$nodeId reason=$reason",
        )
        awaitResponsive(
            nodeId = nodeId,
            timeoutMs = RECOVERY_WAIT_BEFORE_EXISTS_MS,
            reason = reason,
            prewarm = true,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prewarmWatch(nodeId: String) {
        try {
            sendMessage(nodeId, DataLayerPaths.PATH_PREPARE_CHANNEL, byteArrayOf())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.d(TAG, "Watch prewarm message failed (non-fatal): ${error.message}")
            PhoneTransferDiagnostics.warn("Exists", "Prewarm failed node=$nodeId msg=${error.message}")
        }
    }

    private suspend fun ensureWatchResponsive(
        nodeId: String,
        force: Boolean = false,
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        if (!force && (responsiveNodesUntilMs[nodeId] ?: 0L) > nowMs) {
            return true
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingPings[requestId] = deferred
        val payload =
            JSONObject()
                .put("id", requestId)
                .toString()
                .toByteArray(Charsets.UTF_8)

        return try {
            sendMessage(nodeId, DataLayerPaths.PATH_PING, payload)
            val ok = withTimeoutOrNull(PING_TIMEOUT_MS) { deferred.await() } == true
            if (ok) {
                responsiveNodesUntilMs[nodeId] = System.currentTimeMillis() + RESPONSIVE_CACHE_MS
                PhoneTransferDiagnostics.log("Exists", "Ping ok node=$nodeId")
            } else {
                Log.d(TAG, "Watch ping timed out for node=$nodeId")
                PhoneTransferDiagnostics.warn("Exists", "Ping timeout node=$nodeId")
            }
            ok
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Watch ping failed for node=$nodeId: ${e.message}")
            PhoneTransferDiagnostics.error("Exists", "Ping failed node=$nodeId", e)
            false
        } finally {
            pendingPings.remove(requestId)?.cancel()
        }
    }

    companion object {
        private const val TAG = "FileExistenceChecker"
        private const val CHECK_TIMEOUT_MS = 4_000L
        private const val RETRY_CHECK_TIMEOUT_MS = 8_000L
        private const val BATCH_CHECK_TIMEOUT_MS = 4_000L
        private const val RETRY_BATCH_CHECK_TIMEOUT_MS = 8_000L
        private const val PING_TIMEOUT_MS = 4_000L
        private const val PREWARM_SETTLE_DELAY_MS = 350L
        private const val RESPONSIVE_CACHE_MS = 15_000L
        private const val RECOVERING_NODE_DEFAULT_MS = 90_000L
        private const val RECOVERY_WAIT_BEFORE_EXISTS_MS = 60_000L
        private const val RECOVERY_POLL_INTERVAL_MS = 1_500L
    }
}
