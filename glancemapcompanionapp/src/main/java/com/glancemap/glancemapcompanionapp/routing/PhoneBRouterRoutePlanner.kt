package com.glancemap.glancemapcompanionapp.routing

import android.content.Context
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.trailcore.routing.BRouterEngine
import com.glancemap.trailcore.routing.BRouterFileLayout
import com.glancemap.trailcore.routing.BRouterRouteOutput
import com.glancemap.trailcore.routing.BRouterRouteRequest
import com.glancemap.trailcore.routing.normalizeBRouterErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android adapter for the shared BRouter engine using the phone's downloaded routing packs. */
internal class PhoneBRouterRoutePlanner(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val storage = PhoneOfflineStorage(appContext)

    @Suppress("TooGenericExceptionCaught") // BRouter and asset loading expose varied failure types.
    suspend fun createRoute(request: BRouterRouteRequest): BRouterRouteOutput =
        withContext(Dispatchers.IO) {
            try {
                ensureBundledProfilesInstalled()
                val stamp = System.currentTimeMillis()
                val engine = createEngine()
                val attempt = engine.route(request)
                engine.output(
                    attempt = attempt,
                    title = "BRouter route $stamp",
                    fileName = "route-$stamp.gpx",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException(
                    normalizeBRouterErrorMessage(error.message.orEmpty()),
                    error,
                )
            }
        }

    private fun ensureBundledProfilesInstalled() {
        val profilesDirectory = storage.profilesDirectory()
        PROFILE_FILE_NAMES.forEach { fileName ->
            copyAssetIfNeeded(
                assetPath = "profiles2/$fileName",
                targetFile = File(profilesDirectory, fileName),
            )
        }
        copyAssetIfNeeded(
            assetPath = "profiles2/hiking-mountain.brf",
            targetFile = File(profilesDirectory, "dummy.brf"),
        )
    }

    private fun createEngine(): BRouterEngine =
        BRouterEngine(
            BRouterFileLayout(
                segmentsDirectory = storage.routingDirectory(),
                profilesDirectory = storage.profilesDirectory(),
            ),
        )

    private fun copyAssetIfNeeded(
        assetPath: String,
        targetFile: File,
    ) {
        if (targetFile.isFile && targetFile.length() > 0L) return
        targetFile.parentFile?.mkdirs()
        appContext.assets.open(assetPath).use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private companion object {
        val PROFILE_FILE_NAMES =
            listOf(
                "lookups.dat",
                "hiking-mountain.brf",
                "trekking.brf",
                "fastbike.brf",
                "fastbike-verylowtraffic.brf",
                "gravel.brf",
                "mtb.brf",
            )
    }
}
