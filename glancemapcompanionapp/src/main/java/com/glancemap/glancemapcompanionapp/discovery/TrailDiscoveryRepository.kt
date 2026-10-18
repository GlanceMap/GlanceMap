package com.glancemap.glancemapcompanionapp.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

internal interface PublishedTrailProvider {
    val provider: TrailProvider
    val instanceLabel: String
        get() = provider.label
    val unavailableReason: String?
        get() = null

    suspend fun discover(query: TrailDiscoveryQuery): List<PublishedTrail>
}

internal enum class TrailSourceOutcome { SUCCESS, UNAVAILABLE, FAILED }

internal data class TrailSourceStatus(
    val provider: TrailProvider,
    val label: String,
    val outcome: TrailSourceOutcome,
    val reason: String? = null,
    val failure: TrailDiscoveryFailure? = null,
)

internal data class TrailDiscoveryReply(
    val trails: List<PublishedTrail> = emptyList(),
    val statuses: List<TrailSourceStatus> = emptyList(),
)

internal class TrailDiscoveryRepository(
    private val providers: List<PublishedTrailProvider>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val cache = linkedMapOf<Pair<String, TrailDiscoveryQuery>, Pair<Long, List<PublishedTrail>>>()

    suspend fun discover(
        query: TrailDiscoveryQuery,
        enabled: Set<TrailProvider>,
    ): TrailDiscoveryReply =
        withContext(Dispatchers.IO) {
            val results =
                supervisorScope {
                    providers
                        .filter { it.provider in enabled }
                        .map { source ->
                            async { discoverSource(source, query) }
                        }.awaitAll()
                }
            coroutineContext.ensureActive()
            TrailDiscoveryReply(mergePublishedTrails(results.flatMap { it.first }), results.map { it.second })
        }

    @Suppress("TooGenericExceptionCaught") // External adapters fail independently; cancellation always propagates.
    private suspend fun discoverSource(
        source: PublishedTrailProvider,
        query: TrailDiscoveryQuery,
    ): Pair<List<PublishedTrail>, TrailSourceStatus> {
        val unavailable = source.unavailableReason
        if (unavailable != null) {
            return emptyList<PublishedTrail>() to
                TrailSourceStatus(
                    source.provider,
                    source.instanceLabel,
                    TrailSourceOutcome.UNAVAILABLE,
                    unavailable,
                )
        }
        return try {
            val key = source.instanceLabel to query
            val cached = synchronized(cache) { cache[key] }?.takeIf { nowMillis() - it.first < 300000L }
            val trails =
                cached?.second ?: withTimeoutOrNull(25000) { source.discover(query) }
                    ?: throw TrailDiscoveryException(TrailDiscoveryFailure.TIMED_OUT)
            coroutineContext.ensureActive()
            synchronized(cache) {
                if (cache.size >= 24) cache.remove(cache.keys.first())
                cache[key] = nowMillis() to trails
            }
            trails to TrailSourceStatus(source.provider, source.instanceLabel, TrailSourceOutcome.SUCCESS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emptyList<PublishedTrail>() to
                TrailSourceStatus(
                    source.provider,
                    source.instanceLabel,
                    TrailSourceOutcome.FAILED,
                    failure = (failure as? TrailDiscoveryException)?.failure,
                )
        }
    }
}

internal class UnavailableTrailProvider(
    override val provider: TrailProvider,
    override val unavailableReason: String,
) : PublishedTrailProvider {
    override suspend fun discover(query: TrailDiscoveryQuery): List<PublishedTrail> = emptyList()
}
