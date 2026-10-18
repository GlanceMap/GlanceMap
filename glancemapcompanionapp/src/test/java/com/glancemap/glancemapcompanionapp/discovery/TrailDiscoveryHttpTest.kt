package com.glancemap.glancemapcompanionapp.discovery

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TrailDiscoveryHttpTest {
    private val request = Request.Builder().url("https://example.invalid/routes").build()

    @Test
    fun rateLimitsAndServerErrorsAreKnownFailuresWithoutRemoteMessages() {
        val cases =
            listOf(
                429 to TrailDiscoveryFailure.RATE_LIMITED,
                502 to TrailDiscoveryFailure.SERVICE_UNAVAILABLE,
                503 to TrailDiscoveryFailure.SERVICE_UNAVAILABLE,
            )
        for ((code, expected) in cases) {
            response(code, "Private server diagnostics".toResponseBody()).use {
                val failure = runCatching { it.boundedTrailResponse() }.exceptionOrNull()
                assertEquals(expected, (failure as TrailDiscoveryException).failure)
                assertEquals("Route source unavailable", failure.message)
            }
        }
        response(403, "Private server diagnostics".toResponseBody()).use {
            val failure = runCatching { it.boundedTrailResponse() }.exceptionOrNull()
            assertEquals("Route source unavailable", failure?.message)
        }
    }

    @Test
    fun knownAndStreamingResponseSizesKeepTheSameBound() {
        for (unknownSize in listOf(false, true)) {
            val body =
                if (unknownSize) {
                    object : ResponseBody() {
                        override fun contentType() = null

                        override fun contentLength() = -1L

                        override fun source(): BufferedSource = Buffer().writeUtf8("x".repeat(4 * 1024 * 1024 + 1))
                    }
                } else {
                    "x".repeat(4 * 1024 * 1024 + 1).toResponseBody()
                }
            response(200, body).use {
                val failure = runCatching { it.boundedTrailResponse() }.exceptionOrNull() as TrailDiscoveryException
                assertEquals(TrailDiscoveryFailure.RESPONSE_TOO_LARGE, failure.failure)
            }
        }
        response(200, "{}".toResponseBody()).use { assertEquals("{}", it.boundedTrailResponse()) }
    }

    @Test
    fun headerAndBodyTimeoutsAreReportedWithoutTheirExceptionText() =
        runBlocking {
            for (duringBody in listOf(false, true)) {
                val client =
                    OkHttpClient
                        .Builder()
                        .addInterceptor {
                            if (!duringBody) throw SocketTimeoutException("Private transport diagnostics")
                            val body =
                                object : ResponseBody() {
                                    override fun contentType() = null

                                    override fun contentLength() = -1L

                                    override fun source(): BufferedSource =
                                        object : ForwardingSource(Buffer()) {
                                            override fun read(
                                                sink: Buffer,
                                                byteCount: Long,
                                            ): Long = throw SocketTimeoutException("Private body diagnostics")
                                        }.buffer()
                                }
                            response(200, body)
                        }.build()
                try {
                    val failure =
                        runCatching { TrailDiscoveryHttp(client).read(request) }
                            .exceptionOrNull() as TrailDiscoveryException
                    assertEquals(TrailDiscoveryFailure.TIMED_OUT, failure.failure)
                    assertEquals("Route source unavailable", failure.message)
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }

    @Test
    fun unknownNetworkErrorsStayGenericAndPrivate() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { throw IOException("Private transport diagnostics") }
                    .build()
            try {
                val failure = runCatching { TrailDiscoveryHttp(client).read(request) }.exceptionOrNull()
                assertEquals(IOException::class.java, failure?.javaClass)
                assertEquals("Route source unavailable", failure?.message)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    @Test
    fun cancellationStillCancelsTheHttpCall() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val call = AtomicReference<Call>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        call.set(it.call())
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        response(200, "{}".toResponseBody())
                    }.build()
            val pending = async { TrailDiscoveryHttp(client).read(request) }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                }
                pending.cancelAndJoin()
                assertTrue(call.get().isCanceled())
            } finally {
                release.countDown()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    private fun response(
        code: Int,
        body: ResponseBody,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fixture")
            .body(body)
            .build()
}
