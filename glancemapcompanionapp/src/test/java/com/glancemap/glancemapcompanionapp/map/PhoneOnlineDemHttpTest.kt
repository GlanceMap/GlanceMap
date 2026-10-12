package com.glancemap.glancemapcompanionapp.map

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneOnlineDemHttpTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun responsesRejectHttpFailuresAndOversizedPayloads() {
        val request = Request.Builder().url("https://example.invalid/dem.png").build()
        response(request, 404, byteArrayOf(1)).use { assertNull(it.boundedDemBytes()) }
        response(request, 200, ByteArray(1024 * 1024 + 1)).use { assertNull(it.boundedDemBytes()) }
        response(request, 200, byteArrayOf(1, 2, 3)).use {
            assertArrayEquals(byteArrayOf(1, 2, 3), it.boundedDemBytes())
        }
    }

    @Test
    fun expiredDiskCacheSurvivesNewClientAndWorksWithoutNetwork() =
        runBlocking {
            val folder = temporaryFolder.newFolder()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val bytes = byteArrayOf(1, 2, 3)
            server.createContext("/dem.png") { exchange ->
                exchange.responseHeaders.add("Cache-Control", "public, max-age=0")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/dem.png").build()
            val firstCache = Cache(folder, 1024 * 1024)
            val first = OkHttpClient.Builder().cache(firstCache).build()
            try {
                assertArrayEquals(bytes, first.newCall(request).readDemBytes())
            } finally {
                server.stop(0)
                firstCache.close()
                first.dispatcher.executorService.shutdown()
                first.connectionPool.evictAll()
            }
            val nextCache = Cache(folder, 1024 * 1024)
            val next = OkHttpClient.Builder().cache(nextCache).build()
            try {
                val cached =
                    request
                        .newBuilder()
                        .cacheControl(
                            CacheControl
                                .Builder()
                                .onlyIfCached()
                                .maxStale(Int.MAX_VALUE, TimeUnit.SECONDS)
                                .build(),
                        ).build()
                assertArrayEquals(bytes, next.newCall(cached).readDemBytes())
                assertEquals(0, nextCache.networkCount())
            } finally {
                nextCache.close()
                next.dispatcher.executorService.shutdown()
                next.connectionPool.evictAll()
            }
        }

    @Test
    fun coroutineCancellationCancelsUnderlyingHttpCall() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        response(chain.request(), 200, byteArrayOf(1))
                    }.build()
            val call = client.newCall(Request.Builder().url("https://example.invalid/dem.png").build())
            val pending = async { call.readDemBytes() }
            try {
                withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
                pending.cancelAndJoin()
                assertTrue(call.isCanceled())
            } finally {
                release.countDown()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    private fun response(
        request: Request,
        code: Int,
        bytes: ByteArray,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(bytes.toResponseBody())
            .build()
}
