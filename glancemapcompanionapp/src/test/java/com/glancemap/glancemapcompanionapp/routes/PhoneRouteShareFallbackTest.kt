package com.glancemap.glancemapcompanionapp.routes

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneRouteShareFallbackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sdCardRouteCanBeOpenedWhenProviderRejectsItsOriginalLocation() {
        val source = temporary.newFile("sd-route.gpx").apply { writeText("<gpx>route</gpx>") }
        val cache = temporary.newFolder("cache")
        val result =
            phoneRouteShareUri(source, cache) { file ->
                if (file == source) throw IllegalArgumentException("no configured root")
                file
            }
        assertNotEquals(source, result)
        assertEquals("<gpx>route</gpx>", result.readText())
        assertEquals("<gpx>route</gpx>", source.readText())
        assertTrue(result.canonicalPath.startsWith(cache.canonicalPath + File.separator))
    }

    @Test fun supportedLocationUsesOriginalFileWithoutCopying() {
        val source = temporary.newFile("route.gpx")
        val cache = temporary.newFolder("cache")
        assertEquals(source, phoneRouteShareUri(source, cache) { it })
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun missingSourceDoesNotReturnAnIncompleteCopy() {
        val source = File(temporary.root, "missing.gpx")
        val cache = temporary.newFolder("cache")
        assertTrue(runCatching { phoneRouteShareUri(source, cache) { throw IllegalArgumentException() } }.isFailure)
        assertTrue(cache.walkTopDown().none { it.isFile })
    }
}
