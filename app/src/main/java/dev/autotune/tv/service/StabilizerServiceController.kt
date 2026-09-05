package dev.autotune.tv.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Start/stop helpers so callers do not have to know the intent protocol. */
object StabilizerServiceController {

    /**
     * [userInitiated] is not cosmetic: Android only allows a microphone or
     * projection foreground service to start when the user did something to
     * cause it, so a boot start and a start from the UI are genuinely different.
     */
    fun start(context: Context, userInitiated: Boolean = false) {
        val intent = Intent(context, StabilizerService::class.java).apply {
            action = StabilizerService.ACTION_START
            putExtra(StabilizerService.EXTRA_USER_INITIATED, userInitiated)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, StabilizerService::class.java).apply {
            action = StabilizerService.ACTION_STOP
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun submitProjection(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, StabilizerService::class.java).apply {
            action = StabilizerService.ACTION_SET_PROJECTION
            putExtra(StabilizerService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StabilizerService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
