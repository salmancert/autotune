package dev.autotune.tv

import android.app.Application
import dev.autotune.tv.service.StabilizerServiceController
import dev.autotune.tv.settings.SettingsRepository

class AutotuneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsRepository(this)
        // Covers the case where the app is updated or force-stopped and then
        // reopened: boot is not the only way the service should come back.
        if (settings.enabled) {
            StabilizerServiceController.start(this)
        }
    }
}
