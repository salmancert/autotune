package dev.autotune.tv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.autotune.tv.service.StabilizerServiceController
import dev.autotune.tv.settings.SettingsRepository

/**
 * Brings the stabiliser up with the TV.
 *
 * `LOCKED_BOOT_COMPLETED` is included so the service starts on devices with
 * direct boot before the user unlocks anything; `MY_PACKAGE_REPLACED` covers an
 * update, after which the service would otherwise stay dead until the app is
 * opened.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val settings = SettingsRepository(context)
                if (!settings.enabled || !settings.startOnBoot) return
                Log.i(TAG, "starting stabiliser after ${intent.action}")
                // Not user-initiated: at boot the platform only lets us run the
                // output processing and any capture that needs no consent.
                StabilizerServiceController.start(context, userInitiated = false)
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
