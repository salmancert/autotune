package dev.autotune.tv.capture

import android.media.audiofx.Visualizer
import android.util.Log
import dev.autotune.core.dsp.AudioBuffers
import dev.autotune.core.features.AnalysisFormat

/**
 * Reads the global output mix through a `Visualizer` attached to session 0.
 *
 * This is the only path that sees DRM-protected playback, because it taps the
 * mix after decoding. It needs `CAPTURE_AUDIO_OUTPUT`, a signature permission,
 * so it only succeeds on a privileged/system install (see README for the
 * `adb install`-to-`/system/priv-app` route on a rooted box). On an ordinary
 * install the constructor throws and the factory moves on to the next source.
 *
 * The waveform is 8-bit and arrives in bursts rather than as a continuous
 * stream, so the envelope statistics are an approximation - good enough to tell
 * dialogue from a score, not good enough to be called a real capture.
 */
class OutputMixSource private constructor(
    private val visualizer: Visualizer,
    private val captureBytes: ByteArray,
    private val deviceSampleRate: Int,
    private val analysisSampleRate: Int,
) : AnalysisSource {

    override val kind: SourceKind = SourceKind.OUTPUT_MIX
    override val label: String = "output mix"

    private val mono = FloatArray(captureBytes.size)

    /** Wall-clock pacing, since the Visualizer hands back snapshots on demand. */
    private val pollIntervalMs: Long =
        (1000L * captureBytes.size / deviceSampleRate).coerceAtLeast(10L)

    override fun read(out: FloatArray): Int {
        val status = visualizer.getWaveForm(captureBytes)
        if (status != Visualizer.SUCCESS) return -1
        val frames = AudioBuffers.pcm8ToMono(captureBytes, captureBytes.size, mono)
        val written = AudioBuffers.resampleLinear(mono, frames, deviceSampleRate, out, analysisSampleRate)
        Thread.sleep(pollIntervalMs)
        return written
    }

    override fun close() {
        runCatching { visualizer.enabled = false }
        runCatching { visualizer.release() }
    }

    companion object {
        private const val TAG = "OutputMixSource"
        private const val GLOBAL_OUTPUT_SESSION = 0

        fun create(format: AnalysisFormat = AnalysisFormat.DEFAULT): OutputMixSource? = try {
            val visualizer = Visualizer(GLOBAL_OUTPUT_SESSION)
            val captureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer.captureSize = captureSize
            visualizer.enabled = true
            val rate = visualizer.samplingRate / 1000 // reported in milliHertz
            OutputMixSource(
                visualizer = visualizer,
                captureBytes = ByteArray(captureSize),
                deviceSampleRate = if (rate > 0) rate else 44_100,
                analysisSampleRate = format.sampleRate,
            )
        } catch (error: Exception) {
            // RuntimeException("Cannot initialize Visualizer engine") on a
            // normal install: expected, not an error worth shouting about.
            Log.i(TAG, "output mix capture unavailable: ${error.message}")
            null
        }
    }
}
