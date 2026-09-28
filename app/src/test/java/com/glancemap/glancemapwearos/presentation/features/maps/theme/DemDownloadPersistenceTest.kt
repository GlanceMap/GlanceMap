package com.glancemap.glancemapwearos.presentation.features.maps.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class DemDownloadPersistenceTest {
    @Test
    fun copyReportsProgressAndPreservesDownloadedBytes() {
        val dir = Files.createTempDirectory("dem-progress").toFile()
        val payload = ByteArray(160 * 1024) { index -> (index % 251).toByte() }
        val context = downloadContext(dir = dir, resumeOffset = 0L)
        val progress = mutableListOf<Pair<Long, Long?>>()

        copyDemResponse(
            connection = ByteArrayConnection(payload),
            context = context,
            response =
                DemDownloadResponse(
                    code = HttpURLConnection.HTTP_OK,
                    append = false,
                    expectedTotalBytes = payload.size.toLong(),
                ),
            onProgress = { bytesDone, totalBytes -> progress += bytesDone to totalBytes },
        )

        assertTrue(progress.size >= 2)
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        assertArrayEquals(payload, context.part.readBytes())
        dir.deleteRecursively()
    }

    @Test
    fun resumedCopyReportsCombinedByteCount() {
        val dir = Files.createTempDirectory("dem-progress-resume").toFile()
        val existing = ByteArray(48 * 1024) { 1 }
        val resumed = ByteArray(80 * 1024) { 2 }
        val context = downloadContext(dir = dir, resumeOffset = existing.size.toLong())
        context.part.writeBytes(existing)
        val expectedTotal = existing.size.toLong() + resumed.size.toLong()
        val progress = mutableListOf<Long>()

        copyDemResponse(
            connection = ByteArrayConnection(resumed),
            context = context,
            response =
                DemDownloadResponse(
                    code = HttpURLConnection.HTTP_PARTIAL,
                    append = true,
                    expectedTotalBytes = expectedTotal,
                ),
            onProgress = { bytesDone, _ -> progress += bytesDone },
        )

        assertEquals(expectedTotal, progress.last())
        assertArrayEquals(existing + resumed, context.part.readBytes())
        dir.deleteRecursively()
    }

    private fun downloadContext(
        dir: File,
        resumeOffset: Long,
    ): DemDownloadContext =
        DemDownloadContext(
            sourceId = "mapsforge_dem3",
            url = "https://example.test/N46E006.hgt.zip",
            target = File(dir, "N46E006.hgt.zip"),
            part = File(dir, ".N46E006.hgt.zip.part"),
            tileName = "N46E006",
            resumeOffset = resumeOffset,
        )
}

private class ByteArrayConnection(
    private val payload: ByteArray,
) : HttpURLConnection(URL("https://example.test/dem")) {
    override fun getInputStream(): InputStream = ByteArrayInputStream(payload)

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}
