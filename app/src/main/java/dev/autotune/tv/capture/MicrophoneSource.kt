package dev.autotune.tv.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.autotune.core.features.AnalysisFormat

/**
 * Listens to the room with the device microphone.
 *
 * The last resort, and the only path that works for DRM-protected apps on an
 * unprivileged install. It is a closed loop - the mic hears Autotune's own
 * correction - so the service subtracts the applied gain back out before
 * deciding what to do next.
 */
class MicrophoneSource private constructor(
    private val record: AudioRecord,
    private val reader: PcmReader,
) : AnalysisSource {

    override val kind: SourceKind = SourceKind.MICROPHONE
    override val label: String = "room microphone"

    override fun read(out: FloatArray): Int = reader.read(out)

    override fun close() {
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    companion object {
        private const val TAG = "MicrophoneSource"

        @SuppressLint("MissingPermission")
        fun create(format: AnalysisFormat = AnalysisFormat.DEFAULT): MicrophoneSource? {
            val rate = format.sampleRate
            val minimum = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) return null
            val bufferBytes = maxOf(minimum, format.hopSize * 8 * 2)

            // UNPROCESSED first where the device offers it: automatic gain
            // control and noise suppression would fight the very level changes
            // we are trying to measure. Not every TV implements it, hence MIC.
            for (audioSource in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
                val record = try {
                    AudioRecord(audioSource, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                } catch (error: Exception) {
                    Log.w(TAG, "microphone source $audioSource unavailable", error)
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                return try {
                    record.startRecording()
                    MicrophoneSource(
                        record,
                        PcmReader(
                            record,
                            deviceSampleRate = rate,
                            channels = 1,
                            analysisSampleRate = rate,
                            bufferSamples = bufferBytes / 2,
                        ),
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "microphone could not start", error)
                    record.release()
                    null
                }
            }
            return null
        }
    }
}
