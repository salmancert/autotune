package dev.autotune.tv.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import dev.autotune.core.features.AnalysisFormat

/**
 * Picks the best analysis source this device and install actually allow,
 * in order of fidelity.
 */
object AnalysisSourceFactory {

    /**
     * [ruledOut] names sources that were tried and delivered nothing but
     * silence. A source that opens successfully and then produces only zeroes
     * is not hypothetical - playback capture does exactly that on TVs whose
     * firmware does not really implement it - and without this the app would
     * hold that dead source forever rather than trying the next one.
     */
    fun create(
        context: Context,
        projection: MediaProjection?,
        allowMicrophone: Boolean,
        format: AnalysisFormat = AnalysisFormat.DEFAULT,
        ruledOut: Set<SourceKind> = emptySet(),
    ): AnalysisSource? {
        if (!hasRecordPermission(context)) return null

        // 1. Exact, per-app capture - when the user has granted projection and
        //    the app being watched has not opted out.
        if (SourceKind.PLAYBACK_CAPTURE !in ruledOut &&
            projection != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            PlaybackCaptureSource.create(projection, format)?.let { return it }
        }

        // 2. The whole output mix, including DRM playback - privileged installs only.
        if (SourceKind.OUTPUT_MIX !in ruledOut) {
            OutputMixSource.create(format)?.let { return it }
        }

        // 3. The room, if the user opted in and a microphone is attached.
        if (SourceKind.MICROPHONE !in ruledOut && allowMicrophone && hasMicrophone(context)) {
            MicrophoneSource.create(format)?.let { return it }
        }

        return null
    }

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether any audio input exists - not whether the TV shipped with one.
     *
     * `FEATURE_MICROPHONE` is a static declaration the manufacturer bakes into
     * the device, and most TVs declare it false. It stays false when a USB
     * microphone is plugged in, so keying off it would refuse to use a
     * microphone that is physically present and working. On a set where the
     * streaming apps block capture, that USB microphone is the only route to
     * analysing anything at all, so this asks what inputs are actually attached.
     */
    fun hasMicrophone(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val attached = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS) }
            .getOrDefault(emptyArray())
            .any { it.type in USABLE_INPUTS }
        return attached || context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }

    /** Input devices that can hear a room. Telephony and loopback cannot. */
    fun describeInputs(context: Context): String {
        val manager = context.getSystemService(AudioManager::class.java) ?: return "unknown"
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS) }
            .getOrDefault(emptyArray())
        if (devices.isEmpty()) return "none attached"
        return devices.joinToString(", ") { device ->
            val name = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                else -> "type ${device.type}"
            }
            if (device.type in USABLE_INPUTS) name else "$name (unusable)"
        }
    }

    private val USABLE_INPUTS = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )
}
