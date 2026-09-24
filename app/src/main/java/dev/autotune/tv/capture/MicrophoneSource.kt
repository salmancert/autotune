package dev.autotune.tv.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import dev.autotune.core.features.AnalysisFormat

/**
 * Listens to the room with a microphone.
 *
 * The last resort, and the only path that works for DRM-protected apps, for
 * live TV through the tuner, for HDMI inputs, and for TVs whose firmware
 * accepts a playback capture and then feeds it silence. It is a closed loop -
 * the mic hears Autotune's own correction - so the service subtracts the
 * applied gain back out before deciding what to do next.
 */
class MicrophoneSource private constructor(
    private val record: AudioRecord,
    private val reader: PcmReader,
    private val effects: List<AudioEffect>,
    override val label: String,
) : AnalysisSource {

    override val kind: SourceKind = SourceKind.MICROPHONE

    override fun read(out: FloatArray): Int = reader.read(out)

    override fun close() {
        effects.forEach { runCatching { it.release() } }
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    companion object {
        private const val TAG = "MicrophoneSource"

        /**
         * Capture sources, least processed first.
         *
         * The order matters more on a TV than on a phone. A set with hands-free
         * voice control has a far-field microphone array, and the stream the
         * assistant wants from it has been beamformed, noise-suppressed, gain-
         * controlled and - the fatal one - echo-cancelled against the TV's own
         * output. That processing exists to remove the television's sound so a
         * person talking over it can be understood, which is the exact opposite
         * of what this app needs: it would subtract the only signal we want.
         *
         * `UNPROCESSED` promises none of it and is what to ask for. `CAMCORDER`
         * is the next best bet, being meant to record a scene rather than a
         * caller. `MIC` is last because it is where the vendor's processing is
         * most likely to be applied by default. `VOICE_RECOGNITION` and
         * `VOICE_COMMUNICATION` are deliberately absent: echo cancellation is
         * the point of both.
         */
        private val SOURCES: List<Pair<Int, String>> = listOf(
            Pair(MediaRecorder.AudioSource.UNPROCESSED, "unprocessed"),
            Pair(MediaRecorder.AudioSource.CAMCORDER, "camcorder"),
            Pair(MediaRecorder.AudioSource.MIC, "mic"),
        )

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

            for ((audioSource, name) in SOURCES) {
                val record = try {
                    AudioRecord(audioSource, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                } catch (error: Exception) {
                    Log.w(TAG, "microphone source $name unavailable", error)
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                val effects = disableProcessing(record.audioSessionId)
                try {
                    record.startRecording()
                } catch (error: Exception) {
                    Log.w(TAG, "microphone source $name could not start", error)
                    effects.forEach { runCatching { it.release() } }
                    record.release()
                    continue
                }
                Log.i(TAG, "listening to the room via $name")
                return MicrophoneSource(
                    record,
                    PcmReader(
                        record,
                        deviceSampleRate = rate,
                        channels = 1,
                        analysisSampleRate = rate,
                        bufferSamples = bufferBytes / 2,
                    ),
                    effects,
                    "room microphone ($name)",
                )
            }
            return null
        }

        /**
         * Turns off any input processing the platform attached on its own.
         *
         * Asking for a less processed source is a request, not a guarantee -
         * plenty of devices ignore it and hand back the same processed stream.
         * These three effects can be switched off explicitly once the record
         * exists, and each one would otherwise corrupt the measurement:
         * automatic gain control flattens the loudness differences this app is
         * built to find, noise suppression eats the quiet dialogue it is meant
         * to lift, and echo cancellation removes the television's own sound.
         *
         * The handles are kept and released with the record: an [AudioEffect]
         * that gets collected takes its setting with it.
         */
        private fun disableProcessing(sessionId: Int): List<AudioEffect> {
            val effects = mutableListOf<AudioEffect>()
            fun disable(name: String, available: () -> Boolean, create: () -> AudioEffect?) {
                runCatching {
                    if (!available()) return@runCatching
                    val effect = create() ?: return@runCatching
                    effect.enabled = false
                    effects += effect
                    Log.i(TAG, "$name disabled on the analysis input")
                }.onFailure { Log.w(TAG, "could not disable $name", it) }
            }
            disable("automatic gain control", AutomaticGainControl::isAvailable) {
                AutomaticGainControl.create(sessionId)
            }
            disable("noise suppression", NoiseSuppressor::isAvailable) {
                NoiseSuppressor.create(sessionId)
            }
            disable("echo cancellation", AcousticEchoCanceler::isAvailable) {
                AcousticEchoCanceler.create(sessionId)
            }
            return effects
        }
    }
}
