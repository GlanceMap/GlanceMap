package com.glancemap.glancemapwearos.core.service.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpxHttpParsingTest {
    @Test
    fun parsesRequestLineAndQuery() {
        val request = GpxHttpRequest.parse("POST /upload?t=123456&name=route.gpx HTTP/1.1")
        assertEquals("POST", request?.method)
        assertEquals("/upload", request?.path)
        assertEquals("123456", request?.query?.get("t"))
        assertEquals("route.gpx", request?.query?.get("name"))
    }

    @Test
    fun rejectsMalformedRequestLine() {
        assertNull(GpxHttpRequest.parse("nonsense"))
    }
}
