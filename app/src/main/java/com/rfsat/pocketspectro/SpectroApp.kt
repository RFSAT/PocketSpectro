package com.rfsat.pocketspectro

import android.app.Application
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.data.LocationTracker
import com.rfsat.pocketspectro.data.SettingsStore

class SpectroApp : Application() {
    lateinit var engine: AcquisitionEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = AcquisitionEngine(this, SettingsStore(this), LocationTracker(this))
    }
}
