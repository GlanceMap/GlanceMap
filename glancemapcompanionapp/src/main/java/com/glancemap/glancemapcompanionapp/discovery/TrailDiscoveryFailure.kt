package com.glancemap.glancemapcompanionapp.discovery

import java.io.IOException

internal enum class TrailDiscoveryFailure { TIMED_OUT, RATE_LIMITED, SERVICE_UNAVAILABLE, RESPONSE_TOO_LARGE }

/** Carries only a known failure category; remote bodies, URLs and exception messages stay private. */
internal class TrailDiscoveryException(
    val failure: TrailDiscoveryFailure,
) : IOException("Route source unavailable")
