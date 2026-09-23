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
import java.io.File
import java.util.Locale

/**
 * Dumps everything needed to tell why the app is not doing what was expected:
 *
 *     adb shell am broadcast -n dev.autotune.tv/.diag.DiagnosticsReceiver \
 *         -a dev.autotune.tv.DIAGNOSE --include-stopped-packages
 *     adb logcat -d -s AutotuneDiag
 *
 * Both parts of that command are load-bearing, and each covers a restriction
 * that fails silently - the broadcast still reports "completed" and produces no
 * output, which looks exactly like the receiver not existing:
 *
 *  - `-n <component>` makes the broadcast explicit. Since Android 8 the system
 *    refuses to deliver an implicit broadcast to a manifest-declared receiver in
 *    a background app, and logs only `BroadcastQueue: Background execution not
 *    allowed` where nobody is looking.
 *  - `--include-stopped-packages` reaches an app that has never been launched,
 *    which is where every app sits after `adb install`.
 *
 * "I can't hear a difference" has half a dozen causes that look identical from
 * the sofa - capture never granted, the effect refusing to attach, the TV
 * passing audio through to a soundbar untouched, the service simply not
 * running. Each one is obvious from a state dump and invisible without one.
 */
class DiagnosticsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Anything in here that throws would otherwise leave no trace at all:
        // the broadcast still reports "completed", and the absence of output
        // looks exactly like the receiver never running.
        val report = runCatching { buildReport(context) }.getOrElse { error ->
            "=== Autotune diagnostics failed ===\n" + error.stackTraceToString()
        }

        // Line by line: logcat truncates long single messages.
        report.lineSequence().forEach { Log.i(TAG, it) }

        // Also written to a file, because logcat is not always readable - some
        // TV firmware drops third-party output, and a filtered logcat that shows
        // nothing is indistinguishable from a receiver that never fired:
        //   adb shell cat /sdcard/Android/data/dev.autotune.tv/files/diagnostics.txt
        runCatching {
            val target = File(context.getExternalFilesDir(null), FILE_NAME)
            target.writeText(report)
            Log.i(TAG, "written to ${target.absolutePath}")
        }.onFailure { Log.w(TAG, "could not write the report to a file", it) }
    }

    private fun buildReport(context: Context): String {
        val settings = SettingsRepository(context)
        val status = StabilizerStatusBus.status.value
        val audio = context.getSystemService(AudioManager::class.java)
        val state = status.state

        return buildString {
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
            append(verdict(status, audio))
        }
    }

    /** The one sentence worth reading first. */
    private fun verdict(status: dev.autotune.tv.service.StabilizerStatus, audio: AudioManager?): String {
        val running = status.running
        val source = status.sourceLabel
        val processor = status.processorLabel

        // Capture that is attached but has only ever read zeroes, while the
        // system says audio is playing, is the signature of an app that opts out
        // of playback capture. The API cannot report that - an opted-out stream
        // arrives as silence, not as an error - so it has to be inferred.
        val capturingNothing = source != null &&
            audio?.isMusicActive == true &&
            status.captureStartedAtMs > 0L &&
            System.currentTimeMillis() - status.captureStartedAtMs > SETTLE_MS &&
            System.currentTimeMillis() - status.lastSignalAtMs > SETTLE_MS

        return when {
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
            capturingNothing ->
                "VERDICT  Capture is attached but has read nothing but silence while the system says " +
                    "audio is playing. Either the app being played opts out of capture (Netflix, Prime " +
                    "Video and Disney+ all do, and an opted-out stream arrives as silence rather than an " +
                    "error), or the sound is not coming from an Android app at all - live TV through the " +
                    "tuner and anything on an HDMI input bypass Android's audio entirely, and neither " +
                    "capture nor the effect can reach them. Test with YouTube."
            !status.state.hasSignal ->
                "VERDICT  Set up correctly, but nothing is playing right now. Start something and run " +
                    "this again."
            else ->
                "VERDICT  Fully working. If you still hear no difference, the audio is likely leaving " +
                    "the TV untouched over HDMI - try 'Adjust the TV volume directly'."
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "AutotuneDiag"
        const val FILE_NAME = "diagnostics.txt"

        /** Grace period before silence is taken to mean something. */
        const val SETTLE_MS = 5_000L
    }
}
