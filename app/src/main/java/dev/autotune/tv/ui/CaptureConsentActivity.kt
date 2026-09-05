package dev.autotune.tv.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.autotune.tv.R
import dev.autotune.tv.capture.AnalysisSourceFactory
import dev.autotune.tv.service.StabilizerServiceController

/**
 * Invisible activity that collects the two consents capture needs: the
 * microphone permission and the MediaProjection grant.
 *
 * The projection token cannot be persisted - Android issues a fresh one per
 * grant - so this is also what the notification points at after a reboot.
 */
class CaptureConsentActivity : AppCompatActivity() {

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            StabilizerServiceController.submitProjection(this, result.resultCode, data)
        } else {
            Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_LONG).show()
            StabilizerServiceController.start(this, userInitiated = true)
        }
        finish()
    }

    private val requestRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { launchProjectionRequest() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AnalysisSourceFactory.hasRecordPermission(this)) {
            requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionRequest()
        }
    }

    private fun launchProjectionRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Playback capture does not exist before API 29; the service will
            // fall back to the output mix or the microphone on its own.
            StabilizerServiceController.start(this, userInitiated = true)
            finish()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent: Intent = manager.createScreenCaptureIntent()
        runCatching { requestProjection.launch(intent) }.onFailure {
            StabilizerServiceController.start(this, userInitiated = true)
            finish()
        }
    }
}
