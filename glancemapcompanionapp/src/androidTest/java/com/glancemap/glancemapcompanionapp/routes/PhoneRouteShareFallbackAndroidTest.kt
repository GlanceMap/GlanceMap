package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PhoneRouteShareFallbackAndroidTest {
    @Test
    fun providerCanReadStagedRouteFromOutsideConfiguredRoots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDirectory = context.getDir("route-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val source = File(sourceDirectory, "route.gpx").apply { writeText("<gpx>route</gpx>") }
        var staged: File? = null
        val provider: (File) -> android.net.Uri = { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        try {
            assertTrue(runCatching { provider(source) }.isFailure)
            val uri =
                phoneRouteShareUri(source, context.cacheDir) { file ->
                    if (file != source) staged = file
                    provider(file)
                }
            assertEquals(
                "<gpx>route</gpx>",
                context.contentResolver
                    .openInputStream(uri)!!
                    .bufferedReader()
                    .use { it.readText() },
            )
            assertEquals("<gpx>route</gpx>", source.readText())
        } finally {
            staged?.delete()
            sourceDirectory.deleteRecursively()
        }
    }
}
