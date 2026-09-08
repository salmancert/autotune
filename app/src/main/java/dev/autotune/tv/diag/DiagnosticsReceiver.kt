package dev.autotune.tv.diag

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.autotune.tv.capture.AnalysisSourceFactory
import dev.autotune.tv.service.StabilizerStatusBus
import dev.autotune.tv.session.PlaybackMonitor
import dev.autotune.tv.settings.SettingsRepository
import java.util.Locale

/**
 * Dumps everything needed to tell why the app is not doing what was expected:
 *
 *     adb shell am broadcast -a dev.autotune.tv.DIAGNOSE
 *     adb logcat -d -s AutotuneDiag
 *
 * "I can't hear a difference" has half a dozen causes that look identical from
 * the sofa - capture never granted, the effect refusing to attach, the TV
 * passing audio through to a soundbar untouched, the service simply not
 * running. Each one is obvious from a state dump and invisible without one.
 */
class DiagnosticsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsRepository(context)
        val status = StabilizerStatusBus.status.value
        val audio = context.getSystemService(AudioManager::class.java)
        val state = status.state

        val report = buildString {
            appendLine("=== Autotune diagnostics ===")
            appendLine("device        ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("service       ${if (status.running) "RUNNING" else "NOT RUNNING - open the app once"}")
            appendLine("analysis      ${status.sourceLabel ?: "NONE - press Grant audio capture"}")
            appendLine("output stage  ${status.processorLabel ?: "NONE ATTACHED - no effect could be applied"}")
            appendLine("bypassed      ${status.bypassed}")
            appendLine("playing app   ${status.playingPackage ?: "unknown"}  profile ${state.profile.label}")
            appendLine()
            appendLine("settings      enabled=${settings.enabled} preset=${settings.preset.label} strength=${settings.strength}")
            appendLine("              target=${settings.targetDialogueLufs} LUFS, volumeControl=${settings.useVolumeControl}")
            appendLine()
            appendLine("permissions   RECORD_AUDIO=${granted(context, Manifest.permission.RECORD_AUDIO)}")
            appendLine("              notificationAccess=${PlaybackMonitor(context).hasAccess()}")
            appendLine("              microphonePresent=${AnalysisSourceFactory.hasMicrophone(context)}")
            appendLine()
            if (audio != null) {
                appendLine("volume        media=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
                appendLine("              isVolumeFixed=${audio.isVolumeFixed}  musicActive=${audio.isMusicActive}")
            }
            appendLine()
            if (status.running) {
                appendLine(
                    String.format(
                        Locale.ROOT,
                        "live          %s  dialogue %.0f%% music %.0f%% effects %.0f%%",
                        if (state.hasSignal) "signal" else "SILENT - is anything playing?",
                        state.speechProbability * 100f,
                        state.musicProbability * 100f,
                        state.effectsProbability * 100f,
                    ),
                )
                appendLine(
                    String.format(
                        Locale.ROOT,
                        "              %.1f LUFS, gain %+.1f dB, eq %+.1f dB, trim %.1f dB",
                        state.momentaryLufs,
                        state.gainDb,
                        state.dialogueEqDb,
                        state.sustainedTrimDb,
                    ),
                )
            }
            appendLine()
            append(verdict(status.running, status.sourceLabel, status.processorLabel, audio))
        }

        // Line by line: logcat truncates long single messages.
        report.lineSequence().forEach { Log.i(TAG, it) }
    }

    /** The one sentence worth reading first. */
    private fun verdict(
        running: Boolean,
        source: String?,
        processor: String?,
        audio: AudioManager?,
    ): String = when {
        !running ->
            "VERDICT  Not running. Open Autotune on the TV once; it does not start itself until then."
        processor == null ->
            "VERDICT  Nothing is being applied - no audio effect would attach on this device. " +
                "Turn on 'Adjust the TV volume directly' in the app."
        audio?.isVolumeFixed == true ->
            "VERDICT  The device reports a fixed output volume, which usually means audio is passed " +
                "through to a soundbar or receiver. Effects on the output mix will not be audible."
        source == null ->
            "VERDICT  Running the fixed preset only - no analysis. Press 'Grant audio capture' " +
                "(needed again after every restart). Netflix and Prime Video can never be analysed."
        else ->
            "VERDICT  Fully working. If you still hear no difference, the audio is likely leaving " +
                "the TV untouched over HDMI - try 'Adjust the TV volume directly'."
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "AutotuneDiag"
    }
}
