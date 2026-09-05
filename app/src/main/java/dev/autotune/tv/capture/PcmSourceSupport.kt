package dev.autotune.tv.capture

import android.media.AudioRecord
import dev.autotune.core.dsp.AudioBuffers

/**
 * Shared plumbing for the `AudioRecord`-based sources: read 16-bit PCM at
 * whatever rate the device gave us, downmix, and resample to the analysis rate.
 */
internal class PcmReader(
    private val record: AudioRecord,
    private val deviceSampleRate: Int,
    private val channels: Int,
    private val analysisSampleRate: Int,
    bufferSamples: Int,
) {
    private val pcm = ShortArray(bufferSamples * channels)
    private val mono = FloatArray(bufferSamples)

    fun read(out: FloatArray): Int {
        val wanted = minOf(pcm.size, (out.size * deviceSampleRate / analysisSampleRate) * channels)
        val read = record.read(pcm, 0, wanted)
        if (read <= 0) {
            // ERROR_INVALID_OPERATION and friends are fatal; a 0-length read is not.
            return if (read == 0) 0 else -1
        }
        val frames = AudioBuffers.pcm16ToMono(pcm, read, channels, mono)
        return AudioBuffers.resampleLinear(mono, frames, deviceSampleRate, out, analysisSampleRate)
    }
}
