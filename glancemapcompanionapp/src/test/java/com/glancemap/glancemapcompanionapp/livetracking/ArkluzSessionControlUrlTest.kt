package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArkluzSessionControlUrlTest {
    @Test
    fun locationTimeUsesEpochMilliseconds() {
        val url = buildArkluzLocationUrl(update())

        assertEquals("1750000000000", url.queryParameter("time"))
    }

    @Test
    fun locationAltitudeIsRoundedToOneDecimalPlace() {
        val url = buildArkluzLocationUrl(update(altitudeMeters = 1234.567890123))

        assertEquals("1234.6", url.queryParameter("alt"))
    }

    @Test
    fun pauseIncludesPauseAndDateIdWithoutStartingNewActivity() {
        val url = buildArkluzLocationUrl(update(pause = true, dateId = "20260622-42"))

        assertTrue("pause" in url.queryParameterNames)
        assertFalse("start" in url.queryParameterNames)
        assertFalse("resume" in url.queryParameterNames)
        assertEquals("20260622-42", url.queryParameter("date_id"))
    }

    @Test
    fun resumeIncludesResumeAndDateIdWithoutStartingNewActivity() {
        val url = buildArkluzLocationUrl(update(resume = true, dateId = "20260622-42"))

        assertTrue("resume" in url.queryParameterNames)
        assertFalse("start" in url.queryParameterNames)
        assertFalse("pause" in url.queryParameterNames)
        assertEquals("20260622-42", url.queryParameter("date_id"))
    }

    @Test
    fun catchUpPointAlwaysUsesZeroCellularSignalAndKeepsGpsTime() {
        val original = update(gsmSignalPercent = 75, altitudeMeters = 1234.5, speedMetersPerSecond = 7.5f)
        val url = buildArkluzLocationUrl(original.asCatchUpPoint())

        assertEquals("0", url.queryParameter("gsm_signal"))
        assertEquals(original.latitude.toString(), url.queryParameter("lat"))
        assertEquals(original.longitude.toString(), url.queryParameter("lon"))
        assertEquals(original.accuracyMeters.toString(), url.queryParameter("acc"))
        assertEquals(original.epochMilliseconds.toString(), url.queryParameter("time"))
        assertEquals(original.altitudeMeters?.let(::formatArkluzAltitudeMeters), url.queryParameter("alt"))
        assertEquals(original.speedMetersPerSecond.toString(), url.queryParameter("speed"))
    }

    @Test
    fun developmentEndpointIsUsedByLocationRequests() {
        val url = buildArkluzLocationUrl(update(trackingUrl = ArkluzTrackingEndpoint.DEVELOPMENT.url))

        assertEquals("/dev/trk", url.encodedPath)
    }

    @Suppress("LongParameterList")
    private fun update(
        pause: Boolean = false,
        resume: Boolean = false,
        dateId: String? = null,
        altitudeMeters: Double? = null,
        speedMetersPerSecond: Float? = null,
        trackingUrl: String = "https://arkluz.com/trk",
        gsmSignalPercent: Int = -1,
    ) = ArkluzLocationUpdate(
        trackingUrl = trackingUrl,
        latitude = 45.0,
        longitude = 6.0,
        altitudeMeters = altitudeMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        accuracyMeters = 5f,
        epochMilliseconds = 1_750_000_000_000,
        batteryPercent = 80,
        gsmSignalPercent = gsmSignalPercent,
        group = "Alpes",
        participantPassword = "secret",
        userName = "André",
        notificationEmails = "",
        alertEmails = "",
        stuckAlarmMinutes = "15",
        start = false,
        stop = false,
        pause = pause,
        resume = resume,
        dateId = dateId,
    )
}
