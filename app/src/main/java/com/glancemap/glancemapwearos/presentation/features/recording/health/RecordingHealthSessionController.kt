package com.glancemap.glancemapwearos.presentation.features.recording.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class RecordingHealthOwner { NONE, THIS_APP, OTHER_APP }

/** Implementations deliver callbacks on Main, including the final metrics before ENDED. */
internal interface RecordingHealthListener {
    fun onUpdate(
        phase: RecordingHealthPhase,
        samples: List<RecordingHealthSample>,
        reason: String?,
    )

    fun onFailure(error: Throwable)

    fun onAvailability(message: String)
}

internal interface RecordingHealthClient {
    suspend fun capabilities(): RecordingHealthDeviceCapabilities

    suspend fun owner(): RecordingHealthOwner

    suspend fun registerListener(listener: RecordingHealthListener)

    suspend fun clearListener()

    suspend fun start(configuration: RecordingHealthConfiguration)

    suspend fun pause()

    suspend fun resume()

    suspend fun flush()

    suspend fun end()
}

// Keep serialized IPC handling together; SDK failures cannot abort GPX and cancellation still propagates.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
internal class RecordingHealthSessionController(
    private val client: RecordingHealthClient,
    private val onStatus: (RecordingHealthStatus) -> Unit,
    private val onSamples: (List<RecordingHealthSample>) -> Unit,
    private val operationTimeoutMillis: Long = 10_000L,
) : RecordingHealthController {
    private val commands = Mutex()
    private var status = RecordingHealthStatus(RecordingHealthPhase.IDLE)
    private var generation = 0L
    private var registered = false
    private var startAttempted = false
    private var endRequestCompleted = false
    private var ending = false
    private var ended = CompletableDeferred<Unit>()
    private var selectedKinds: Set<RecordingHealthMetric> = emptySet()

    override suspend fun start(request: RecordingHealthRequest): RecordingHealthStatus =
        withContext(Dispatchers.Main.immediate) {
            commands.withLock {
                if (startAttempted || (registered && ending)) return@withLock status
                try {
                    withTimeout(operationTimeoutMillis) {
                        clearListener()
                        ending = false
                        publish(RecordingHealthStatus(RecordingHealthPhase.STARTING))
                        val owner = client.owner()
                        if (owner != RecordingHealthOwner.NONE) return@withTimeout publishConflict(owner)
                        val configuration = selectRecordingHealthConfiguration(request, client.capabilities())
                        if (configuration == null) {
                            return@withTimeout publish(
                                RecordingHealthStatus(
                                    RecordingHealthPhase.UNSUPPORTED,
                                    message = "No requested watch metrics supported",
                                ),
                            )
                        }
                        selectedKinds = configuration.activeKinds
                        ended = CompletableDeferred()
                        endRequestCompleted = false
                        generation += 1L
                        registered = true
                        client.registerListener(listener(generation))
                        // There is no atomic check-and-start in the SDK; check again immediately before starting.
                        val latestOwner = client.owner()
                        if (latestOwner != RecordingHealthOwner.NONE) {
                            clearListener()
                            return@withTimeout publishConflict(latestOwner)
                        }
                        startAttempted = true
                        client.start(configuration)
                        status
                    }
                } catch (error: TimeoutCancellationException) {
                    cleanupFailedSession()
                    publishError("start", error)
                } catch (error: CancellationException) {
                    cleanupFailedSession()
                    throw error
                } catch (error: Exception) {
                    cleanupFailedSession()
                    publishError("start", error)
                }
            }
        }

    override suspend fun pause() = command("pause") { client.pause() }

    override suspend fun resume() = command("resume") { client.resume() }

    override suspend fun flush() = command("flush") { client.flush() }

    override suspend fun end(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            commands.withLock {
                ending = true
                if (registered || startAttempted) publish(RecordingHealthStatus(RecordingHealthPhase.ENDING))
                try {
                    finishExercise()
                    true
                } catch (error: TimeoutCancellationException) {
                    publishError("end", error)
                    false
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        try {
                            finishExercise()
                        } catch (cleanupError: Exception) {
                            publishError("end", cleanupError)
                        }
                    }
                    throw error
                } catch (error: Exception) {
                    publishError("end", error)
                    false
                }
            }
        }

    private suspend fun command(
        name: String,
        action: suspend () -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            commands.withLock {
                if (!startAttempted || ending) return@withLock
                try {
                    withTimeout(operationTimeoutMillis) { action() }
                } catch (error: TimeoutCancellationException) {
                    cleanupFailedSession()
                    publishError(name, error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    cleanupFailedSession()
                    publishError(name, error)
                }
            }
        }
    }

    private fun listener(callbackGeneration: Long): RecordingHealthListener {
        val endedSignal = ended
        return object : RecordingHealthListener {
            override fun onUpdate(
                phase: RecordingHealthPhase,
                samples: List<RecordingHealthSample>,
                reason: String?,
            ) {
                if (callbackGeneration != generation || !registered || !startAttempted) return
                if (phase == RecordingHealthPhase.ENDED) startAttempted = false
                if (samples.isNotEmpty()) onSamples(samples)
                if (ending && phase != RecordingHealthPhase.ENDED) return
                val activeKinds =
                    if (phase == RecordingHealthPhase.ACTIVE ||
                        phase == RecordingHealthPhase.PAUSED
                    ) {
                        selectedKinds
                    } else {
                        emptySet()
                    }
                publish(RecordingHealthStatus(phase, activeKinds, reason))
                if (phase == RecordingHealthPhase.ENDED) {
                    endedSignal.complete(Unit)
                }
            }

            override fun onFailure(error: Throwable) {
                if (callbackGeneration == generation && registered) publishError("callback", error)
            }

            override fun onAvailability(message: String) {
                if (callbackGeneration == generation && registered) publish(status.copy(message = message))
            }
        }
    }

    private suspend fun finishExercise() {
        withTimeout(operationTimeoutMillis) {
            if (startAttempted && !ended.isCompleted) {
                if (!endRequestCompleted) {
                    client.end()
                    endRequestCompleted = true
                }
                // Future completion alone is insufficient: consume the final flushed ExerciseUpdate first.
                ended.await()
            }
            clearListener()
        }
    }

    private suspend fun cleanupFailedSession() {
        withContext(NonCancellable) {
            ending = true
            try {
                withTimeout(operationTimeoutMillis) {
                    if (startAttempted && client.owner() == RecordingHealthOwner.THIS_APP) {
                        finishExercise()
                    } else {
                        startAttempted = false
                        clearListener()
                    }
                }
            } catch (error: Exception) {
                // Retain this generation's callback after a timeout so a late final batch can still be consumed.
                publishError("session cleanup", error)
            }
        }
    }

    private suspend fun clearListener() {
        if (!registered) return
        client.clearListener()
        registered = false
        generation += 1L
    }

    private fun publishConflict(owner: RecordingHealthOwner): RecordingHealthStatus =
        publish(
            RecordingHealthStatus(
                phase =
                    if (owner == RecordingHealthOwner.OTHER_APP) {
                        RecordingHealthPhase.EXTERNAL_CONFLICT
                    } else {
                        RecordingHealthPhase.OWNED_CONFLICT
                    },
                message = "An existing exercise must be resolved before starting watch metrics",
            ),
        )

    private fun publishError(
        operation: String,
        error: Throwable,
    ): RecordingHealthStatus =
        publish(
            RecordingHealthStatus(RecordingHealthPhase.ERROR, message = "$operation: ${error.javaClass.simpleName}"),
        )

    private fun publish(next: RecordingHealthStatus): RecordingHealthStatus {
        status = next
        onStatus(next)
        return next
    }
}
