package com.glancemap.glancemapcompanionapp.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

@RunWith(AndroidJUnit4::class)
class PhoneOfflineMapRevisionTest {
    @Test
    fun sameSizeAtomicReplacementHasANewRevisionInAppStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOfNotNull(context.cacheDir, context.externalCacheDir).distinctBy(File::getAbsolutePath).forEach { root ->
            val directory = Files.createTempDirectory(root.toPath(), "phone-map-revision-").toFile()
            try {
                val target = File(directory, "alps.map").apply { writeText("before") }
                val temporary = File(directory, "replacement.part").apply { writeText("after!") }
                val modified = FileTime.fromMillis(1_700_000_000_000L)
                Files.setLastModifiedTime(target.toPath(), modified)
                Files.setLastModifiedTime(temporary.toPath(), modified)
                val previous = PhoneOfflineMap(target)
                val previousIdentity = previous.rendererIdentity

                target.inputStream().use { openReader ->
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    val current = PhoneOfflineMap(target)

                    assertEquals("before", openReader.bufferedReader().readText())
                    assertEquals("after!", target.readText())
                    assertEquals(previous.selectionIdentity, current.selectionIdentity)
                    assertEquals(modified, Files.getLastModifiedTime(target.toPath()))
                    assertNotEquals(previous, current)
                    assertNotEquals(previousIdentity, current.rendererIdentity)
                    assertEquals(previousIdentity, previous.rendererIdentity)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
