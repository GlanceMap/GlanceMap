package com.glancemap.glancemapwearos

import android.app.Application
import android.content.res.Configuration
import com.glancemap.glancemapwearos.core.localization.AppLanguageSettings
import com.glancemap.glancemapwearos.core.service.diagnostics.CrashDiagnosticsStore
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferPrewarmHoldManager
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferSessionState
import com.glancemap.glancemapwearos.core.service.transfer.storage.StalePartialTransferCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GlanceMapWearApp : Application() {
    // Global app-wide IO scope (NOT tied to any Activity/Service lifecycle)
    val applicationScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO,
        )

    internal val transferSessionState: TransferSessionState = TransferSessionState()
    internal val transferPrewarmHoldManager: TransferPrewarmHoldManager by lazy {
        TransferPrewarmHoldManager(this)
    }

    lateinit var container: AppContainer
        private set

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguageSettings.applyLegacy(this, newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguageSettings.initialize(this)
        CrashDiagnosticsStore.install(this)
        // Initialize the container when the app starts
        container = AppContainer(this, applicationScope)
        applicationScope.launch {
            StalePartialTransferCleaner.cleanStale(this@GlanceMapWearApp)
        }
    }
}
