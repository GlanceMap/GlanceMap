package com.glancemap.glancemapcompanionapp

import android.app.Application
import android.content.res.Configuration
import com.glancemap.glancemapcompanionapp.localization.PhoneAppLanguageSettings
import com.glancemap.glancemapcompanionapp.map.PhoneCustomThemes

class PhoneGlanceMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneAppLanguageSettings.initialize(this)
        PhoneCustomThemes.initialize(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        PhoneAppLanguageSettings.applyLegacy(this, newConfig)
    }
}
