package com.glancemap.glancemapcompanionapp.livetracking

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

// OkHttp requires separate callbacks for call, DNS, connect, TLS and request timing.
@Suppress("TooManyFunctions")
internal class LiveTrackingHttpTimingListener : EventListener() {
    private var startedNanos = 0L
    private var dnsStartedNanos = 0L
    private val connectStartedNanos = mutableMapOf<Pair<InetSocketAddress, Proxy>, Long>()
    private val tlsStartedNanos = mutableMapOf<Thread, Long>()
    private var metadata: LiveTrackingDiagnosticRequest? = null
    private var dnsMillis = 0L
    private var connectMillis = 0L
    private var tlsMillis = 0L
    private var connections = 0
    private var requests = 0

    @Synchronized
    override fun callStart(call: Call) {
        startedNanos = System.nanoTime()
        metadata = call.request().tag(LiveTrackingDiagnosticRequest::class.java)
    }

    @Synchronized
    override fun dnsStart(
        call: Call,
        domainName: String,
    ) {
        dnsStartedNanos = System.nanoTime()
    }

    @Synchronized
    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) {
        dnsMillis += elapsedMillis(dnsStartedNanos)
    }

    @Synchronized
    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        connections += 1
        connectStartedNanos[inetSocketAddress to proxy] = System.nanoTime()
    }

    @Synchronized
    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        connectStartedNanos.remove(inetSocketAddress to proxy)?.let { connectMillis += elapsedMillis(it) }
    }

    @Synchronized
    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        connectStartedNanos.remove(inetSocketAddress to proxy)?.let { connectMillis += elapsedMillis(it) }
    }

    @Synchronized
    override fun secureConnectStart(call: Call) {
        tlsStartedNanos[Thread.currentThread()] = System.nanoTime()
    }

    @Synchronized
    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) {
        tlsStartedNanos.remove(Thread.currentThread())?.let { tlsMillis += elapsedMillis(it) }
    }

    @Synchronized
    override fun requestHeadersStart(call: Call) {
        requests += 1
    }

    override fun callEnd(call: Call) = record("completed")

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) = record("failed")

    @Synchronized
    private fun record(outcome: String) {
        PhoneDebugCapture.log(
            "LiveTracking",
            "http_timing outcome=$outcome durationMs=${elapsedMillis(startedNanos)} " +
                "dnsMs=$dnsMillis connectMs=$connectMillis tlsMs=$tlsMillis " +
                "connections=$connections requests=$requests " +
                "operation=${metadata?.operation?.name ?: "unknown"} pointId=${metadata?.pointId ?: "na"} " +
                "fixTsMs=${metadata?.fixTimestampEpochMillis ?: "na"}",
        )
    }

    private fun elapsedMillis(start: Long): Long = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
}
