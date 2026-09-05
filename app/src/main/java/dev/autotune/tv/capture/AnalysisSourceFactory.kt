package dev.autotune.tv.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import dev.autotune.core.features.AnalysisFormat

/**
 * Picks the best analysis source this device and install actually allow,
 * in order of fidelity.
 */
object AnalysisSourceFactory {

    fun create(
        context: Context,
        projection: MediaProjection?,
        allowMicrophone: Boolean,
        format: AnalysisFormat = AnalysisFormat.DEFAULT,
    ): AnalysisSource? {
        if (!hasRecordPermission(context)) return null

        // 1. Exact, per-app capture - when the user has granted projection and
        //    the app being watched has not opted out.
        if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PlaybackCaptureSource.create(projection, format)?.let { return it }
        }

        // 2. The whole output mix, including DRM playback - privileged installs only.
        OutputMixSource.create(format)?.let { return it }

        // 3. The room, if the user opted in and the device has a microphone.
        if (allowMicrophone && hasMicrophone(context)) {
            MicrophoneSource.create(format)?.let { return it }
        }

        return null
    }

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasMicrophone(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
}
