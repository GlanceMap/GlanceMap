package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class RouteLibraryRecordingSaveTest {
    private lateinit var context: IsolatedContext

    @Before
    fun createIsolatedLibrary() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedContext(base)
    }

    @After
    fun removeTestFiles() = context.removeTestFiles()

    @Test
    fun separateRepositoryInstancesPreserveConcurrentSavesAndIdempotentRetry() =
        runBlocking {
            val ids = List(8) { UUID.randomUUID().toString() }
            ids
                .map { id ->
                    async(Dispatchers.IO) { RouteLibraryRepository(context).saveRecordedGpx("REC", gpx, id) }
                }.awaitAll()
            val repository = RouteLibraryRepository(context)
            assertEquals(
                ids.toSet(),
                repository
                    .load()
                    .routes
                    .map { it.id }
                    .toSet(),
            )
            repository.saveRecordedGpx("REC", gpx, ids.first())
            assertEquals(ids.size, repository.load().routes.size)
        }

    @Test
    fun interruptedIndexReplacementPreservesPreviouslySavedRecording() =
        runBlocking {
            val id = UUID.randomUUID().toString()
            val repository = RouteLibraryRepository(context)
            repository.saveRecordedGpx("REC", gpx, id)
            val index = AtomicFile(File(PhoneOfflineStorage(context).routesDirectory(), "routes.json"))
            index.startWrite().use { it.write("{broken".toByteArray()) }
            assertEquals(listOf(id), repository.load().routes.map { it.id })
        }

    private class IsolatedContext(
        base: Context,
    ) : ContextWrapper(base) {
        private val prefix = "rec-test-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = directory

        override fun getSharedPreferences(
            name: String,
            mode: Int,
        ): SharedPreferences {
            val isolated = "$prefix-$name"
            synchronized(preferenceNames) { preferenceNames += isolated }
            return super.getSharedPreferences(isolated, mode)
        }

        fun removeTestFiles() {
            directory.deleteRecursively()
            preferenceNames.forEach { super.deleteSharedPreferences(it) }
        }
    }

    private val gpx =
        """
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><trkseg><trkpt lat="48" lon="11"><ele>500</ele></trkpt>
          <trkpt lat="48.001" lon="11.001"><ele>505</ele></trkpt></trkseg></trk>
        </gpx>
        """.trimIndent().toByteArray()
}
